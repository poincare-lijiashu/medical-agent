#!/usr/bin/env python3
"""按院渲染 Alertmanager 路由（第 287 批 / ADR-001 §3「告警按院路由」）。

为什么要有渲染器而不是手写 yaml：
  * 每开一家医院都要加一条 route + receiver，手写必漏；
  * 漏配的表现是"告警产生但不送达"，比没有告警更危险；
  * 院方联系人应来自配置（值班机器人），而不是散落在告警配置里。

输入：
  * contacts yaml：default(critical/im) + tenants.<code>.webhook
  * 模板：deploy/observability/alertmanager/alertmanager.tenants.template.yml
校验：
  * 租户编码、webhook 协议、receiver 命名；
  * 可选 --auth-url/--token：拉取租户台账，ACTIVE 租户必须有值班渠道（否则退出码 2）。
平台闭环（评测 D-09）：
  * 传 --platform-url/--platform-token 后，渲染出一条 continue 路由，
    把所有告警同时投递到平台 {@code POST /api/v2/ops/hooks/alerts/{sourceCode}}：
    平台侧按 fingerprint 去重、节流并自动开单，院方渠道仍独立收到一份。
  * --require-platform：产线渲染必须带平台通道，缺失即失败（避免"告警只进日志"）。

用法：
  python scripts/ops/render_alertmanager_tenants.py \
      --contacts deploy/observability/alertmanager/tenant-contacts.example.yaml \
      --out artifacts/observability/alertmanager.generated.yml \
      --auth-url http://127.0.0.1:8080 --token <平台运营令牌> \
      --platform-url http://127.0.0.1:8080 --platform-token <接入源令牌> \
      --source-code alertmanager --require-platform
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_TEMPLATE = REPO / "deploy/observability/alertmanager/alertmanager.tenants.template.yml"
DEFAULT_OUT = REPO / "artifacts/observability/alertmanager.generated.yml"
TENANT_CODE = re.compile(r"^[a-z0-9][a-z0-9-]{1,31}$")
SOURCE_CODE = re.compile(r"^[a-z0-9][a-z0-9-]{1,31}$")
SEVERITIES = {"info", "warning", "critical"}


def _fail(message: str, code: int = 2) -> int:
    print(f"[FAIL] {message}", file=sys.stderr)
    return code


def _read_yaml(path: Path) -> dict:
    try:
        import yaml  # type: ignore
    except ImportError:
        raise SystemExit("需要 PyYAML：pip install pyyaml")
    value = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(value, dict):
        raise ValueError(f"{path} 顶层必须是映射")
    return value


def fetch_active_tenants(auth_url: str, token: str) -> set[str]:
    url = auth_url.rstrip("/") + "/api/v2/auth/tenants"
    request = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(request, timeout=15) as response:
        payload = json.loads(response.read().decode("utf-8"))
    data = payload.get("data") or {}
    items = data.get("items") or []
    return {item["code"] for item in items if item.get("status") == "ACTIVE"}


def validate(contacts: dict) -> list[str]:
    issues: list[str] = []
    default = contacts.get("default") or {}
    for key in ("webhook", "critical", "im"):
        url = str(default.get(key, ""))
        if not url.startswith(("http://", "https://")):
            issues.append(f"default.{key} 必须是 http(s) URL：{url or '(空)'}")
    tenants = contacts.get("tenants") or {}
    if not isinstance(tenants, dict):
        return issues + ["tenants 必须是映射（tenant_code -> {webhook}）"]
    for code, entry in tenants.items():
        if not TENANT_CODE.match(str(code)):
            issues.append(f"租户编码不合法：{code}")
        webhook = str((entry or {}).get("webhook", ""))
        if not webhook.startswith(("http://", "https://")):
            issues.append(f"{code}.webhook 必须是 http(s) URL：{webhook or '(空)'}")
        severity = (entry or {}).get("severity")
        if severity is not None and severity not in SEVERITIES:
            issues.append(f"{code}.severity 只能是 {sorted(SEVERITIES)}：{severity}")
    return issues


def render(contacts: dict, template: str, platform: dict | None = None) -> str:
    default = contacts.get("default") or {}
    tenants = contacts.get("tenants") or {}

    routes: list[str] = []
    receivers: list[str] = []
    for code in sorted(tenants):
        entry = tenants[code] or {}
        routes.extend([
            f'    - matchers: [\'tenant="{code}"\']',
            f"      receiver: {code}-oncall",
            "      continue: true",
        ])
        receivers.extend([
            f"  - name: {code}-oncall",
            "    webhook_configs:",
            f'      - url: "{entry.get("webhook")}"',
            "        send_resolved: true",
            "        max_alerts: 20",
        ])

    platform_route, platform_receiver = _platform_blocks(platform)

    text = template
    text = text.replace("{{DEFAULT_WEBHOOK}}", str(default.get("webhook", "")))
    text = text.replace("{{CRITICAL_WEBHOOK}}", str(default.get("critical", "")))
    text = text.replace("{{IM_WEBHOOK}}", str(default.get("im", "")))
    text = text.replace("{{TENANT_ROUTES}}", "\n".join(routes))
    text = text.replace("{{TENANT_RECEIVERS}}", "\n".join(receivers))
    text = text.replace("{{PLATFORM_ROUTE}}", platform_route)
    text = text.replace("{{PLATFORM_RECEIVER}}", platform_receiver)
    return text


def _platform_blocks(platform: dict | None) -> tuple[str, str]:
    """平台工单通道：一条 continue 路由 + 一个带 Bearer 的 receiver。

    为什么用 continue 顶层路由而不是给每个 receiver 加第二个 webhook：
    租户/严重度组合会随院数增长，逐条复制必然漏配；顶层 continue 是"所有告警
    都额外抄送平台一次"的唯一写法。
    """
    if not platform:
        return "", ""
    url = str(platform.get("url", "")).rstrip("/")
    token = str(platform.get("token", ""))
    source = str(platform.get("source_code", "alertmanager"))
    route = "\n".join([
        "    - receiver: medassist-platform",
        "      continue: true",
        "      group_wait: 10s",
        "      repeat_interval: 1h",
    ])
    receiver = "\n".join([
        "  - name: medassist-platform",
        "    webhook_configs:",
        f'      - url: "{url}/api/v2/ops/hooks/alerts/{source}"',
        "        send_resolved: true",
        "        max_alerts: 20",
        "        http_config:",
        "          authorization:",
        "            type: Bearer",
        f'            credentials: "{token}"',
    ])
    return route, receiver


def validate_platform(platform: dict) -> list[str]:
    issues: list[str] = []
    url = str(platform.get("url", ""))
    if not url.startswith(("http://", "https://")):
        issues.append(f"平台地址必须是 http(s) URL：{url or '(空)'}")
    token = str(platform.get("token", ""))
    if len(token) < 16:
        issues.append("平台接入源令牌缺失或过短（至少 16 字符）")
    source = str(platform.get("source_code", ""))
    if not SOURCE_CODE.match(source):
        issues.append(f"接入源编码不合法：{source or '(空)'}")
    return issues


def run(args: argparse.Namespace) -> int:
    contacts_path = Path(args.contacts)
    template_path = Path(args.template)
    if not contacts_path.exists():
        return _fail(f"联系人文件不存在：{contacts_path}")
    if not template_path.exists():
        return _fail(f"模板不存在：{template_path}")

    contacts = _read_yaml(contacts_path)
    issues = validate(contacts)
    if issues:
        for issue in issues:
            print(f"[FAIL] {issue}", file=sys.stderr)
        return 2

    tenants = contacts.get("tenants") or {}
    if args.auth_url:
        if not args.token:
            return _fail("使用 --auth-url 时必须同时提供 --token")
        try:
            active = fetch_active_tenants(args.auth_url, args.token)
        except Exception as error:  # noqa: BLE001 - 渲染器把网络错误作为校验失败
            return _fail(f"拉取租户台账失败：{type(error).__name__}: {error}")
        missing = sorted(code for code in active if code not in tenants)
        unknown = sorted(code for code in tenants if code not in active)
        if unknown:
            print(f"[WARN] 联系人里有非 ACTIVE/未知租户：{', '.join(unknown)}")
        if missing:
            message = "以下 ACTIVE 租户缺少值班渠道：" + ", ".join(missing)
            if not args.allow_missing:
                return _fail(message + "（如为演练可用 --allow-missing）")
            print(f"[WARN] {message}")

    platform = None
    if args.platform_url or args.platform_token:
        platform = {
            "url": args.platform_url or "",
            "token": args.platform_token or "",
            "source_code": args.source_code,
        }
        platform_issues = validate_platform(platform)
        if platform_issues:
            for issue in platform_issues:
                print(f"[FAIL] {issue}", file=sys.stderr)
            return 2
    elif args.require_platform:
        return _fail("产线渲染必须配置平台工单通道：--platform-url 与 --platform-token 缺失")

    text = render(contacts, template_path.read_text(encoding="utf-8"), platform)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if not args.check:
        out.write_text(text, encoding="utf-8")

    print(f"租户路由：{len(tenants)} 条 · 接收器：{len(tenants) + 3 + (1 if platform else 0)} 个"
          f" · 输出：{'(check only)' if args.check else out}")
    print(f"覆写默认渠道：{contacts.get('default', {}).get('webhook')}")
    if platform:
        print(f"平台工单通道：{platform['url']}/api/v2/ops/hooks/alerts/{platform['source_code']}")
    else:
        print("[WARN] 未配置平台工单通道：告警只到院方渠道，不会自动开单（产线请加 --platform-url/--platform-token）")
    print("下一步：amtool check-config 校验后挂载为 Alertmanager --config.file")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="按院渲染 Alertmanager 路由")
    parser.add_argument("--contacts",
                        default=str(REPO / "deploy/observability/alertmanager/tenant-contacts.example.yaml"))
    parser.add_argument("--template", default=str(DEFAULT_TEMPLATE))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--auth-url", default=None,
                        help="可选：控制面网关地址，用于校验 ACTIVE 租户覆盖")
    parser.add_argument("--token", default=None)
    parser.add_argument("--allow-missing", action="store_true",
                        help="允许 ACTIVE 租户缺少值班渠道（只告警不失败）")
    parser.add_argument("--check", action="store_true", help="只校验与渲染，不写文件")
    parser.add_argument("--platform-url", default=None,
                        help="平台控制面地址（如 http://127.0.0.1:8080），用于告警自动开单")
    parser.add_argument("--platform-token", default=None,
                        help="平台接入源令牌（Bearer），由 POST /ops/integrations/alert-sources 发放")
    parser.add_argument("--source-code", default="alertmanager",
                        help="平台接入源编码（与令牌一一对应），默认 alertmanager")
    parser.add_argument("--require-platform", action="store_true",
                        help="产线模式：未配置平台通道时直接失败")
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
