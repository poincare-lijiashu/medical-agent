"""G98 上线前联调自检（仅标准库，不打印任何口令/令牌/密钥）。

用途：在院内/预生产环境，用真实账号跑一遍 Agent 运营链路的**只读检查**：
- 登录与权限（admin/qc）
- 模型合规（只允许 local/domestic）
- 联网来源白名单（非空、开关状态可见）
- 技能库指标 / Prometheus 导出
- 睡眠记忆扫描（只读）
- 运营建议列表
- 仓库产物（Vue dist、Prometheus 规则、当前 HEAD）

用法：
    python scripts/agent_go_live_check.py --base-url http://127.0.0.1:8001 \
        --username admin01 --password ***
    python scripts/agent_go_live_check.py --dry-run   # 只查仓库产物，不连服务

退出码：0 全部通过；1 存在 FAIL；2 参数/网络导致无法完成。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT_PREFIX = "/api/v1/agent"


def _request(base_url: str, path: str, method: str = "GET", token: str | None = None,
             body: dict | None = None, timeout: float = 15.0):
    url = base_url.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001 —— 网络异常转为可读状态，不打印堆栈
        return 0, f"__ERROR__:{type(e).__name__}"


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


def check_prometheus(text: str) -> list[tuple[str, bool, str]]:
    required = ("medassist_agent_experiences_total",
                "medassist_agent_replay_pass_ratio",
                "medassist_agent_unused_published_skills",
                "medassist_agent_operational_alerts",
                "medassist_agent_consult_requests_total",
                "medassist_agent_skill_usage_total")
    missing = [m for m in required if m not in text]
    return [("metrics.prometheus", not missing,
             "missing=" + ",".join(missing) if missing else "required metrics present")]


def check_repo() -> list[tuple[str, bool, str]]:
    out = []
    dist = os.path.join(REPO_ROOT, "frontend-vue", "dist", "index.html")
    out.append(("repo.vue_dist", os.path.isfile(dist), dist))
    rules = os.path.join(REPO_ROOT, "deploy", "prometheus",
                         "medassist-agent-ops.rules.yml")
    out.append(("repo.prometheus_rules", os.path.isfile(rules), rules))
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


def run(base_url: str, username: str, password: str, timeout: float) -> int:
    results: list[tuple[str, bool, str]] = check_repo()

    status, raw = _request(base_url, "/api/v1/auth/login", "POST",
                           body={"username": username, "password": password},
                           timeout=timeout)
    results.append(("auth.login", status == 200, f"http={status}"))
    token = (_json(raw) or {}).get("access_token")
    if not token:
        _print(results)
        print("RESULT FAIL (no token; check username/password and service)")
        return 2

    status, raw = _request(base_url, AGENT_PREFIX + "/model-policy", token=token,
                           timeout=timeout)
    results.append(("model.policy_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_model_policy(_json(raw) or {}))

    status, raw = _request(base_url, AGENT_PREFIX + "/web-policy", token=token,
                           timeout=timeout)
    results.append(("web.policy_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_web_policy(_json(raw) or {}))

    status, raw = _request(base_url, AGENT_PREFIX + "/skill-library/metrics",
                           token=token, timeout=timeout)
    results.append(("skills.metrics_endpoint", status == 200, f"http={status}"))

    status, raw = _request(base_url, AGENT_PREFIX
                           + "/skill-library/metrics/prometheus",
                           token=token, timeout=timeout)
    results.append(("metrics.prometheus_endpoint", status == 200, f"http={status}"))
    if status == 200:
        results.extend(check_prometheus(raw))

    status, raw = _request(base_url, AGENT_PREFIX + "/sleep-memory/scan", "POST",
                           token=token, body={}, timeout=timeout)
    results.append(("memory.scan_readonly", status == 200, f"http={status}"))

    status, raw = _request(base_url, AGENT_PREFIX + "/skill-advices?status=OPEN",
                           token=token, timeout=timeout)
    results.append(("advice.list_endpoint", status == 200, f"http={status}"))

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
    return run(args.base_url, args.username, args.password, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
