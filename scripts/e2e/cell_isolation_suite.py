#!/usr/bin/env python3
"""跨 Cell 身份与数据隔离验收套件（第 283 批 / ADR-001 P2）。

这是 ADR-001 §3 契约表里"数据 / 身份"两行的**可重复执行证据**，不是单元测试：

  * A 院令牌遍历 B 院资源：租户详情、账号列表、迁移台账必须 404/403；
  * A 院令牌打 B 院入口：网关按 instance/tenant 拒绝（403 / 40321）；
  * A 院账号在 B 院库里登录：401（物理隔离的直接证据，不是"查不到"而是"没有这个人"）；
  * 未登记入口：403（入口比登录更根本）；
  * 可选：B 院账号（同一用户名在不同院同样存在）反向验证 B→A 也被拒绝。

设计约束：
  * 只用标准库（验收脚本不应依赖项目 Python 环境）；
  * 单条失败不影响其他探测，最终汇总退出码；
  * 证据落 artifacts/isolation/*.json，可直接附在验收报告里。

用法（主院 8080 + 二院 8180 多院入口模式）：
  python scripts/e2e/cell_isolation_suite.py \
      --cell-a http://127.0.0.1:8080 --host-a hospital-a.example \
      --cell-b http://127.0.0.1:8180 --host-b hospital-b.example \
      --username admin01 --password Med@2026
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "artifacts" / "isolation"


# ---------------------------------------------------------------- HTTP

def http(base: str, method: str, path: str, *, token: str | None = None,
         host: str | None = None, body: dict | None = None,
         timeout: int = 10) -> tuple[int, dict]:
    """返回 (HTTP 状态码, 解析后的响应体)。网络错误返回 (-1, {...})。"""
    url = base.rstrip("/") + path
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if host:
        headers["Host"] = host
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", "replace")
            return response.status, _json(text)
    except urllib.error.HTTPError as error:
        return error.code, _json(error.read().decode("utf-8", "replace"))
    except Exception as error:  # noqa: BLE001 - 验收脚本要把网络错误转成证据
        return -1, {"error": str(error)}


def _json(text: str) -> dict:
    try:
        value = json.loads(text or "{}")
        return value if isinstance(value, dict) else {"value": value}
    except json.JSONDecodeError:
        return {"raw": text[:400]}


def jwt_claims(token: str) -> dict:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def login(base: str, username: str, password: str, host: str | None) -> tuple[str | None, str]:
    status, body = http(base, "POST", "/api/v2/auth/login", host=host,
                        body={"username": username, "password": password})
    if status == 200:
        return (body.get("data") or {}).get("accessToken"), ""
    return None, f"HTTP {status} · code={body.get('code')} · {body.get('message') or body.get('raw') or ''}"


# ---------------------------------------------------------------- 结果收集

class Probe:
    def __init__(self) -> None:
        self.results: list[dict] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.results.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})

    def skip(self, name: str, reason: str) -> None:
        self.results.append({"name": name, "status": "SKIP", "detail": reason})

    def failed(self) -> bool:
        return any(item["status"] == "FAIL" for item in self.results)

    def counts(self) -> dict:
        return {
            "pass": sum(1 for i in self.results if i["status"] == "PASS"),
            "fail": sum(1 for i in self.results if i["status"] == "FAIL"),
            "skip": sum(1 for i in self.results if i["status"] == "SKIP"),
        }


def business_code(body: dict) -> int | None:
    value = body.get("code")
    return value if isinstance(value, int) else None


# ---------------------------------------------------------------- 主流程

def run(args: argparse.Namespace) -> dict:
    probe = Probe()
    evidence: dict = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "adr": "ADR-001 §3 数据/身份隔离契约",
        "cellA": args.cell_a,
        "cellB": args.cell_b,
        "hostA": args.host_a,
        "hostB": args.host_b,
        "results": probe.results,
    }

    # ---- 1. 主院登录与令牌声明
    token_a, error_a = login(args.cell_a, args.username, args.password, args.host_a)
    probe.check("A 院登录成功", token_a is not None, error_a)
    if not token_a:
        return _finish(evidence, probe, args)

    claims_a = jwt_claims(token_a)
    tenant_a = claims_a.get("tenant")
    instance_a = claims_a.get("instance")
    evidence["tenantA"] = tenant_a
    evidence["instanceA"] = instance_a
    probe.check("A 院令牌带 tenant 声明", bool(tenant_a), f"tenant={tenant_a}")
    probe.check("A 院令牌带 instance 声明", bool(instance_a), f"instance={instance_a}")

    status, body = http(args.cell_a, "GET", "/api/v2/auth/me", token=token_a, host=args.host_a)
    me = body.get("data") or {}
    probe.check("A 院 /auth/me 200 且租户一致",
                status == 200 and me.get("tenantCode") == tenant_a,
                f"HTTP {status} · tenantCode={me.get('tenantCode')}")

    # ---- 2. 主院账号列表只能看到本院
    status, body = http(args.cell_a, "GET", "/api/v2/auth/users?page=1&size=200",
                        token=token_a, host=args.host_a)
    users = ((body.get("data") or {}).get("items") or []) if status == 200 else []
    foreign = [u.get("username") for u in users if u.get("tenantCode") not in (None, tenant_a)]
    probe.check("账号列表只含本院账号", status == 200 and not foreign,
                f"HTTP {status} · 跨院账号={foreign[:5]}")

    # ---- 3. A 院令牌访问 B 院租户元数据（控制面）：必须 404
    if tenant_a != args.tenant_b:
        status, body = http(args.cell_a, "GET", f"/api/v2/auth/tenants/{args.tenant_b}",
                            token=token_a, host=args.host_a)
        probe.check("A 院令牌读 B 院租户详情 404",
                    status == 404, f"HTTP {status} · code={business_code(body)}")
        status, body = http(args.cell_a, "GET",
                            f"/api/v2/auth/tenants/{args.tenant_b}/migrations",
                            token=token_a, host=args.host_a)
        probe.check("A 院管理员读 B 院迁移台账被拒",
                    status in (403, 404), f"HTTP {status} · code={business_code(body)}")
    else:
        probe.skip("A 院令牌读 B 院租户详情", "本次只有一个租户（tenant_b == 当前租户）")

    # ---- 4. 未登记入口：403（入口比登录更根本；在 B 院入口上验证，单入口主院不做此判定）
    status_b, _ = http(args.cell_b, "GET", "/actuator/health", host=args.host_b, timeout=3)
    if status_b == -1:
        probe.skip("未登记 Host 访问被拒", f"B 院入口不可达（{args.cell_b}）")
    else:
        status, body = http(args.cell_b, "GET", "/api/v2/auth/me", host="unknown.example")
        probe.check("未登记 Host 访问被拒 403", status == 403,
                    f"HTTP {status} · code={business_code(body)}")

    # ---- 5. A 院令牌打 B 院入口：网关必须拒绝
    if status_b == -1:
        probe.skip("A 院令牌打 B 院入口", f"B 院入口不可达（{args.cell_b}）")
        probe.skip("A 院账号在 B 院库登录", "B 院入口不可达")
    else:
        status, body = http(args.cell_b, "GET", "/api/v2/auth/me", token=token_a, host=args.host_b)
        probe.check("A 院令牌打 B 院入口 403/40321",
                    status == 403 and business_code(body) == 40321,
                    f"HTTP {status} · code={business_code(body)}")

        token_wrong, error_wrong = login(args.cell_b, args.a_only_username,
                                         args.a_only_password, args.host_b)
        probe.check(f"A 院独有账号（{args.a_only_username}）在 B 院库登录 401",
                    token_wrong is None and "HTTP 401" in error_wrong,
                    error_wrong or "登录成功（不应发生：两院账号库物理隔离）")

        # ---- 6. B 院账号（可选）：反向验证 B→A
        if args.cell_b_username and args.cell_b_password:
            token_b, error_b = login(args.cell_b, args.cell_b_username,
                                     args.cell_b_password, args.host_b)
            probe.check("B 院登录成功", token_b is not None, error_b)
            if token_b:
                claims_b = jwt_claims(token_b)
                evidence["tenantB"] = claims_b.get("tenant")
                probe.check("B 院令牌租户与 A 院不同",
                            claims_b.get("tenant") != tenant_a,
                            f"tenantB={claims_b.get('tenant')} tenantA={tenant_a}")
                status, body = http(args.cell_b, "GET", "/api/v2/auth/me",
                                    token=token_b, host=args.host_b)
                probe.check("B 院 /auth/me 200",
                            status == 200 and (body.get("data") or {}).get("tenantCode")
                            == claims_b.get("tenant"),
                            f"HTTP {status}")
                status, body = http(args.cell_a, "GET", "/api/v2/auth/me",
                                    token=token_b, host=args.host_a)
                probe.check("B 院令牌打 A 院入口 403/40321",
                            status == 403 and business_code(body) == 40321,
                            f"HTTP {status} · code={business_code(body)}")
                status, body = http(args.cell_b, "GET",
                                    f"/api/v2/auth/tenants/{tenant_a}",
                                    token=token_b, host=args.host_b)
                probe.check("B 院令牌读 A 院租户详情 404",
                            status == 404, f"HTTP {status} · code={business_code(body)}")
                status, body = http(args.cell_b, "GET",
                                    "/api/v2/auth/users?page=1&size=200",
                                    token=token_b, host=args.host_b)
                users_b = ((body.get("data") or {}).get("items") or []) if status == 200 else []
                leaked = [u.get("username") for u in users_b
                          if u.get("tenantCode") not in (None, claims_b.get("tenant"))]
                probe.check("B 院账号列表只含本院账号",
                            status == 200 and not leaked,
                            f"HTTP {status} · 跨院账号={leaked[:5]}")
        else:
            probe.skip("B 院反向验证", "未提供 --cell-b-username/--cell-b-password")

    return _finish(evidence, probe, args)


def _finish(evidence: dict, probe: Probe, args: argparse.Namespace) -> dict:
    counts = probe.counts()
    evidence["results"] = probe.results
    evidence["summary"] = counts
    evidence["verdict"] = "PASS" if counts["fail"] == 0 else "FAIL"

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out_dir / f"cell-isolation-{stamp}.json"
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")

    print()
    for item in probe.results:
        mark = {"PASS": "PASS", "FAIL": "FAIL", "SKIP": "SKIP"}[item["status"]]
        print(f"[{mark}] {item['name']}" + (f" :: {item['detail']}" if item["detail"] else ""))
    print()
    print(f"结论：{evidence['verdict']} · PASS={counts['pass']} FAIL={counts['fail']} "
          f"SKIP={counts['skip']}")
    print(f"证据：{path}")
    evidence["evidencePath"] = str(path)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description="跨 Cell 身份与数据隔离验收")
    parser.add_argument("--cell-a", default="http://127.0.0.1:8080", help="主院入口（tenant-001）")
    parser.add_argument("--cell-b", default="http://127.0.0.1:8180", help="二院入口（tenant-002）")
    parser.add_argument("--host-a", default=None, help="主院入口 Host（多院入口模式）")
    parser.add_argument("--host-b", default="hospital-b.example", help="二院入口 Host")
    parser.add_argument("--tenant-b", default="tenant-002", help="二院租户编码")
    parser.add_argument("--username", default="admin01")
    parser.add_argument("--password", default="Med@2026")
    # 只存在于 A 院的账号：用于证明"B 院库里没有这个人"（物理隔离，而不是"查不到"）。
    # 默认 doctor01：A 院演示数据自带；二院 Cell 不会预置。
    parser.add_argument("--a-only-username", default="doctor01")
    parser.add_argument("--a-only-password", default="Med@2026")
    parser.add_argument("--cell-b-username", default=None)
    parser.add_argument("--cell-b-password", default=None)
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    args = parser.parse_args()

    evidence = run(args)
    return 0 if evidence.get("verdict") == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
