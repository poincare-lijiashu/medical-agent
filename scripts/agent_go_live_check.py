"""G98 上线前联调自检（仅标准库，不打印任何口令/令牌/密钥）。

用途：在院内/预生产环境，用真实账号跑一遍 Agent 运营链路的**只读检查**：
- 登录与权限（admin/qc）
- 模型合规（只允许 local/domestic）
- 联网来源白名单（非空、开关状态可见）
- 技能库指标 / Prometheus 导出
- 睡眠记忆扫描（只读）
- 运营建议列表
- 存储真源状态（`infra.pg`）；`--require-pg` / `AGENT_REQUIRE_PG=1` 时 PG 不可用即失败
- 仓库产物（Vue dist、Prometheus 规则、当前 HEAD）

用法：
    python scripts/agent_go_live_check.py --base-url http://127.0.0.1:8001 \
        --username admin01 --password *** --metrics-token ***
    python scripts/agent_go_live_check.py --dry-run   # 只查仓库产物，不连服务

登录支持两种会话：响应体 `access_token`（仅测试开关 AUTH_TOKEN_IN_BODY=true）或
生产默认的 httpOnly Cookie；`/metrics` 只接受 `METRICS_TOKEN` 或 body 令牌（按设计
不接受 cookie），未提供令牌时该项会显式 FAIL 并给出提示。

退出码：0 全部通过；1 存在 FAIL；2 参数/网络导致无法完成。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT_PREFIX = "/api/v1/agent"


def _request(base_url: str, path: str, method: str = "GET", token: str | None = None,
             body: dict | None = None, timeout: float = 15.0,
             cookie: str | None = None) -> tuple[int, str, dict]:
    url = base_url.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    if cookie:
        headers["Cookie"] = cookie
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, raw, _headers(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace"), _headers(e.headers)
    except Exception as e:  # noqa: BLE001 —— 网络异常转为可读状态，不打印堆栈
        return 0, f"__ERROR__:{type(e).__name__}", {}


def _headers(message) -> dict:
    """响应头统一小写；``set-cookie`` 保留全部值（登录会同时下发 at/rt 两个 cookie）。"""
    out = {str(k).lower(): v for k, v in (message or {}).items()}
    try:
        out["set-cookie"] = list(message.get_all("Set-Cookie") or [])
    except Exception:  # noqa: BLE001
        pass
    return out


def _session_cookie(values) -> str:
    """从 ``Set-Cookie`` 取访问令牌 cookie（丢弃 Path/HttpOnly/SameSite 等属性）。

    生产默认 ``AUTH_TOKEN_IN_BODY=false``：令牌只走 httpOnly Cookie，脚本必须能用
    同一会话继续只读检查，否则"上线自检"无法在真正的生产安全配置下运行。
    登录同时下发 at/rt 两个 cookie，必须选 at（refresh cookie 不能用于接口鉴权）。
    """
    if isinstance(values, str):
        values = [values]
    pairs = []
    for raw in values or []:
        pair = str(raw).split(";", 1)[0].strip()
        if "=" in pair:
            pairs.append(pair)
    for pair in pairs:
        if pair.split("=", 1)[0].strip() == "medassist_at":
            return pair
    return pairs[0] if pairs else ""


def _json(raw: str):
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return None


def check_model_policy(snapshot: dict) -> list[tuple[str, bool, str]]:
    allowed = snapshot.get("allowed") or []
    out = []
    classes = {str(p.get("class")) for p in allowed}
    out.append(("model.allowed_classes",
                bool(allowed) and classes <= {"local", "domestic"},
                f"allowed={len(allowed)} classes={sorted(classes)}"))
    foreign = [p.get("id") for p in allowed if p.get("class") not in ("local", "domestic")]
    out.append(("model.no_foreign_allowed", not foreign,
                "foreign_in_allowed=" + ",".join(str(x) for x in foreign) if foreign
                else "no foreign providers in allowed list"))
    return out


def check_web_policy(policy: dict) -> list[tuple[str, bool, str]]:
    domains = policy.get("allowedDomains") or []
    out = [("web.whitelist_present", len(domains) > 0, f"domains={len(domains)}")]
    required = {"nhc.gov.cn", "nmpa.gov.cn", "pubmed.ncbi.nlm.nih.gov"}
    missing = sorted(required - set(domains))
    out.append(("web.whitelist_authoritative", not missing,
                "missing=" + ",".join(missing) if missing else "core sources present"))
    out.append(("web.switch_visible", isinstance(policy.get("enabled"), bool),
                f"enabled={policy.get('enabled')}"))
    return out


_SKILL_METRICS = ("medassist_agent_experiences_total",
                  "medassist_agent_replay_pass_ratio",
                  "medassist_agent_unused_published_skills",
                  "medassist_agent_operational_alerts")
_CONSULT_METRICS = ("medassist_agent_consult_requests_total",
                    "medassist_agent_skill_usage_total",
                    "medassist_agent_consult_tokens_total")


def _metric_exported(name: str, text: str) -> bool:
    """指标族是否真的被导出：有样本行，或至少声明了 ``# TYPE``。

    为什么接受 TYPE 行：counter 在**首次自增前**只会输出 HELP/TYPE（本轮实测：
    刚启动的实例 `/metrics` 里 ``consult_requests_total`` 只有 TYPE 行）。把这种
    正常冷启动判成"指标缺失"会让上线自检假红；而"规则引用了根本不存在的指标族"
    仍然会被抓出来。
    """
    if re.search(rf"^# TYPE {re.escape(name)}\b", text, re.M):
        return True
    return bool(re.search(rf"^{re.escape(name)}(?:\{{| )", text, re.M))


def check_prometheus(text: str, full: bool = True) -> list[tuple[str, bool, str]]:
    """校验指标端点。

    评审 01-S6：``/metrics`` 是唯一抓取端点（skill + consult 全量）；Agent 侧的
    ``/skill-library/metrics/prometheus`` 只供人工查看 skill 域，故用 ``full=False``
    只要求 skill 指标——否则自检会把"正确的分工"误判为缺失。
    """
    required = _SKILL_METRICS + (_CONSULT_METRICS if full else ())
    missing = [m for m in required if not _metric_exported(m, text)]
    return [("metrics.prometheus", not missing,
             "missing=" + ",".join(missing) if missing else "required metrics present")]


_ALERT_METRIC = re.compile(r"\bmedassist_[a-z0-9_]+\b")


def check_alert_rules_alignment(metrics_text: str) -> list[tuple[str, bool, str]]:
    """评审 M-34：抓取端点实际导出的指标必须覆盖告警规则引用的全部指标。

    这是 M-14 那类事故的门禁——规则引用了 ``/metrics`` 没导出的指标时，
    告警**永远不会触发**，而此前自检只验证"服务可用"。
    """
    rules = os.path.join(REPO_ROOT, "deploy", "prometheus",
                         "medassist-agent-ops.rules.yml")
    if not os.path.isfile(rules):
        return [("alerts.rules_file", False, rules)]
    with open(rules, encoding="utf-8") as f:
        body = f.read()
    referenced = sorted(set(_ALERT_METRIC.findall(body)))
    missing = [name for name in referenced if not _metric_exported(name, metrics_text)]
    return [
        ("alerts.rules_file", True, f"alerts={body.count('alert:')}"),
        ("alerts.metrics_covered", not missing,
         "missing=" + ",".join(missing) if missing else f"{len(referenced)} metrics covered"),
    ]


def check_repo() -> list[tuple[str, bool, str]]:
    out = []
    dist = os.path.join(REPO_ROOT, "frontend-console", "dist", "index.html")
    out.append(("repo.console_dist", os.path.isfile(dist), dist))
    rules = os.path.join(REPO_ROOT, "deploy", "prometheus",
                         "medassist-agent-ops.rules.yml")
    out.append(("repo.prometheus_rules", os.path.isfile(rules), rules))
    if os.path.isfile(rules):
        with open(rules, encoding="utf-8") as f:
            body = f.read()
        out.append(("repo.prometheus_rules_alerts", "alert:" in body,
                    f"alerts={body.count('alert:')} metrics={len(set(_ALERT_METRIC.findall(body)))}"))
    # 评审 06-S8：本地抓取形态的两个必需文件（缺任一个，告警规则都拿不到数据）
    scrape = os.path.join(REPO_ROOT, "deploy", "observability", "prometheus", "prometheus.yml")
    out.append(("repo.prometheus_scrape_config", os.path.isfile(scrape), scrape))
    token_example = os.path.join(REPO_ROOT, "deploy", "observability", "prometheus",
                                 "metrics-token.example")
    out.append(("repo.metrics_token_example", os.path.isfile(token_example), token_example))
    try:
        head = subprocess.check_output(["git", "-C", REPO_ROOT, "rev-parse", "--short", "HEAD"],
                                       text=True).strip()
        out.append(("repo.git_head", bool(head), head))
    except Exception as e:  # noqa: BLE001
        out.append(("repo.git_head", False, type(e).__name__))
    return out


def _print(results: list[tuple[str, bool, str]]) -> int:
    failed = 0
    for name, ok, detail in results:
        print(("PASS " if ok else "FAIL ") + name + " = " + detail)
        if not ok:
            failed += 1
    return failed


def run(base_url: str, username: str, password: str, timeout: float,
        metrics_token: str = "", require_pg: bool = False) -> int:
    results: list[tuple[str, bool, str]] = check_repo()

    status, raw, headers = _request(base_url, "/api/v1/auth/login", "POST",
                                    body={"username": username, "password": password},
                                    timeout=timeout)
    results.append(("auth.login", status == 200, f"http={status}"))
    token = (_json(raw) or {}).get("access_token") or ""
    cookie = _session_cookie(headers.get("set-cookie") or "")
    results.append(("auth.session", bool(token or cookie),
                    "bearer" if token else ("cookie" if cookie else "none")))
    if not token and not cookie:
        _print(results)
        print("RESULT FAIL (no token/cookie; check username/password and service)")
        return 2

    def call(path: str, method: str = "GET", body: dict | None = None,
             as_scrape: bool = False):
        # /metrics 显式拒绝 cookie（评审 S5）：抓取必须用 METRICS_TOKEN 或 body 令牌
        use_token = (metrics_token or token) if as_scrape else token
        use_cookie = None if as_scrape and (metrics_token or token) else cookie
        return _request(base_url, path, method, token=use_token, body=body,
                        timeout=timeout, cookie=use_cookie)

    status, raw, _ = call(AGENT_PREFIX + "/model-policy")
    results.append(("model.policy_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_model_policy(_json(raw) or {}))

    status, raw, _ = call(AGENT_PREFIX + "/web-policy")
    results.append(("web.policy_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_web_policy(_json(raw) or {}))

    status, raw, _ = call(AGENT_PREFIX + "/skill-library/metrics")
    results.append(("skills.metrics_endpoint", status == 200, f"http={status}"))

    status, raw, _ = call(AGENT_PREFIX + "/skill-library/metrics/prometheus")
    results.append(("metrics.prometheus_endpoint", status == 200, f"http={status}"))
    if status == 200:
        # 评审 01-S6：该端点只导出 skill 域（consult 指标只在 /metrics）
        results.extend(check_prometheus(raw, full=False))

    # 评审 M-34：Prometheus 真正抓取的是 /metrics，必须单独验证它并做告警覆盖对齐
    status, metrics_raw, _ = call("/metrics", as_scrape=True)
    results.append(("metrics.scrape_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_prometheus(metrics_raw))
        results.extend(check_alert_rules_alignment(metrics_raw))
    elif status in (401, 403):
        results.append(("metrics.scrape_hint", False,
                        "抓取端点需 METRICS_TOKEN（推荐）或 AUTH_TOKEN_IN_BODY 令牌；"
                        "cookie 会话按设计不用于抓取"))

    status, raw, _ = call(AGENT_PREFIX + "/sleep-memory/scan", "POST", {})
    results.append(("memory.scan_readonly", status == 200, f"http={status}"))

    status, raw, _ = call(AGENT_PREFIX + "/skill-advices?status=OPEN")
    results.append(("advice.list_endpoint", status == 200, f"http={status}"))

    # 生产多副本必须确认 PG 是真源：服务"健康"但 PG 掉线时会静默回落 JSON，
    # 多实例之间互相看不到写入。--require-pg（或 AGENT_REQUIRE_PG=1）把这一项变成门禁。
    status, raw, _ = call("/api/v1/medical/admin/data")
    infra = (_json(raw) or {}).get("infra") or {}
    pg_ok = bool((infra.get("pg") or {}).get("ok"))
    results.append(("storage.pg", status == 200 and (pg_ok or not require_pg),
                    f"http={status} pg={pg_ok} require={require_pg}"))
    if status == 200:
        storage = infra.get("storage") or {}
        stale = list(storage.get("staleDomains") or [])
        detail = f"mode={storage.get('mode') or '?'} stale={len(stale)}"
        if stale:
            detail += ":" + ",".join(stale[:5]) + ("..." if len(stale) > 5 else "")
        # 按域 stale = 本进程写 PG 失败、读回落 JSON；多副本下别的实例看不到这些写入
        results.append(("storage.domains", not stale or not require_pg, detail))

    failed = _print(results)
    print("----------------------------------------")
    print(("RESULT PASS" if failed == 0 else f"RESULT FAIL ({failed})"))
    return 0 if failed == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="MedAssist Agent 上线前自检")
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL",
                                                        "http://127.0.0.1:8001"))
    parser.add_argument("--username", default=os.getenv("AGENT_USER", "admin01"))
    parser.add_argument("--password", default=os.getenv("AGENT_PASSWORD", ""))
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--metrics-token", default=os.getenv("METRICS_TOKEN", ""),
                        help="抓取令牌（/metrics 用；也可用环境变量 METRICS_TOKEN）")
    parser.add_argument("--require-pg", action="store_true",
                        default=(os.getenv("AGENT_REQUIRE_PG", "").strip().lower()
                                 in ("1", "on", "true", "yes")),
                        help="要求 PG 必须可用（生产多副本应开启；也可用 AGENT_REQUIRE_PG=1）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只检查仓库产物，不连接服务")
    args = parser.parse_args(argv)

    if args.dry_run:
        failed = _print(check_repo())
        print(("RESULT PASS" if failed == 0 else f"RESULT FAIL ({failed})"))
        return 0 if failed == 0 else 1
    if not args.password:
        print("FAIL auth.password = 未提供（使用 --password 或 AGENT_PASSWORD）")
        return 2
    return run(args.base_url, args.username, args.password, args.timeout,
               metrics_token=(args.metrics_token or "").strip(),
               require_pg=bool(args.require_pg))


if __name__ == "__main__":
    sys.exit(main())
