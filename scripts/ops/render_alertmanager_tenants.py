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

用法：
  python scripts/ops/render_alertmanager_tenants.py \
      --contacts deploy/observability/alertmanager/tenant-contacts.example.yaml \
      --out artifacts/observability/alertmanager.generated.yml \
      --auth-url http://127.0.0.1:8080 --token <平台运营令牌>
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


def render(contacts: dict, template: str) -> str:
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

    text = template
    text = text.replace("{{DEFAULT_WEBHOOK}}", str(default.get("webhook", "")))
    text = text.replace("{{CRITICAL_WEBHOOK}}", str(default.get("critical", "")))
    text = text.replace("{{IM_WEBHOOK}}", str(default.get("im", "")))
    text = text.replace("{{TENANT_ROUTES}}", "\n".join(routes))
    text = text.replace("{{TENANT_RECEIVERS}}", "\n".join(receivers))
    return text


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

    text = render(contacts, template_path.read_text(encoding="utf-8"))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if not args.check:
        out.write_text(text, encoding="utf-8")

    print(f"租户路由：{len(tenants)} 条 · 接收器：{len(tenants) + 3} 个"
          f" · 输出：{'(check only)' if args.check else out}")
    print(f"覆写默认渠道：{contacts.get('default', {}).get('webhook')}")
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
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
