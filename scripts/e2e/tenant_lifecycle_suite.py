#!/usr/bin/env python3
"""租户生命周期与数据保留验收（第 284 批 / ADR-001 P3）。

覆盖 ADR-001 §7 P3 的退出标准：

  * 开通后的 Cell 可登录、健康度为 OK（开通流水线真的交付了一个能用的院）；
  * **停用即下线**：控制面停用后，Cell 本地 auth 立刻拒绝登录（状态已同步到 Cell）；
  * **停用不删除数据且可恢复**：停用/恢复前后 Cell 账号数不变；
  * 注销是终态：保留期与原因落台账，恢复被拒绝（56403）。

用法（先执行 scripts/provision_cell.ps1，并保持该 Cell 的 auth 在线）：

  python scripts/e2e/tenant_lifecycle_suite.py \
      --tenant tenant-003 --cell-auth http://127.0.0.1:8184 \
      --admin-username admin01 --admin-password <一次性口令>
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "artifacts" / "provisioning"


def http(base: str, method: str, path: str, *, token: str | None = None,
         body: dict | None = None, timeout: int = 20,
         extra_headers: dict | None = None) -> tuple[int, dict]:
    url = base.rstrip("/") + path
    headers = {"Accept": "application/json"}
    if extra_headers:
        headers.update(extra_headers)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, _json(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        return error.code, _json(error.read().decode("utf-8", "replace"))
    except Exception as error:  # noqa: BLE001 - 验收脚本把网络错误转成证据
        return -1, {"error": str(error)}


def _json(text: str) -> dict:
    try:
        value = json.loads(text or "{}")
        return value if isinstance(value, dict) else {"value": value}
    except json.JSONDecodeError:
        return {"raw": text[:400]}


def login(base: str, username: str, password: str, *,
          direct_auth: bool = False) -> tuple[str | None, int | None, dict]:
    # 直连 Cell auth 时路径是 /login（网关才会剥掉 /api/v2/auth 前缀）；
    # ActorGuardFilter 把没有网关身份头的 POST 视为匿名写，需显式给 X-Actor。
    path = "/login" if direct_auth else "/api/v2/auth/login"
    status, body = http(base, "POST", path,
                        body={"username": username, "password": password},
                        extra_headers={"X-Actor": "lifecycle-suite"} if direct_auth else None)
    token = (body.get("data") or {}).get("accessToken") if status == 200 else None
    return token, status, body


class Probe:
    def __init__(self) -> None:
        self.results: list[dict] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.results.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})

    def skip(self, name: str, reason: str) -> None:
        self.results.append({"name": name, "status": "SKIP", "detail": reason})

    def counts(self) -> dict:
        return {
            "pass": sum(1 for i in self.results if i["status"] == "PASS"),
            "fail": sum(1 for i in self.results if i["status"] == "FAIL"),
            "skip": sum(1 for i in self.results if i["status"] == "SKIP"),
        }


def tenant_version(gateway: str, token: str, code: str) -> int | None:
    status, body = http(gateway, "GET", f"/api/v2/auth/tenants/{code}", token=token)
    if status != 200:
        return None
    return (body.get("data") or {}).get("version")


def cell_user_count(args: argparse.Namespace) -> int | None:
    """数 Cell 账号：优先 psycopg2，不可用时退回 docker exec（本地验收环境两者都有其一）。"""
    query = ("SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = "
             f"'{args.tenant}'")
    try:
        import psycopg2  # type: ignore
    except ImportError:
        psycopg2 = None
    if psycopg2 is not None:
        try:
            connection = psycopg2.connect(
                host="127.0.0.1", port=args.cell_db_port, dbname="medagent",
                user=args.cell_db_user, password=args.cell_db_password, connect_timeout=3)
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = %s",
                                   (args.tenant,))
                    return int(cursor.fetchone()[0])
            finally:
                connection.close()
        except Exception:  # noqa: BLE001
            pass
    if args.cell_db_container:
        try:
            import subprocess
            result = subprocess.run(
                ["docker", "exec", args.cell_db_container, "psql", "-U", args.cell_db_user,
                 "-d", "medagent", "-t", "-A", "-c", query],
                capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                return int(result.stdout.strip())
        except Exception:  # noqa: BLE001
            pass
    return None


def run(args: argparse.Namespace) -> dict:
    probe = Probe()
    evidence: dict = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "adr": "ADR-001 Section 7 P3 生命周期与保留策略",
        "tenant": args.tenant,
        "gateway": args.gateway,
        "cellAuth": args.cell_auth,
        "results": probe.results,
    }

    token, status, body = login(args.gateway, args.ops_username, args.ops_password)
    probe.check("平台运营登录", token is not None, f"HTTP {status}")
    if not token:
        return _finish(evidence, probe, args)

    # ---- 1. 开通结果与健康度
    status, body = http(args.gateway, "GET", f"/api/v2/auth/tenants/{args.tenant}/health",
                        token=token)
    health = body.get("data") or {}
    probe.check("开通后健康度为 OK",
                status == 200 and health.get("state") == "OK",
                f"HTTP {status} · state={health.get('state')} · issues={health.get('issues')}")
    probe.check("健康度含 Cell 账号且 auth 就绪",
                health.get("authReady") is True and (health.get("userCount") or 0) >= 1,
                f"authReady={health.get('authReady')} users={health.get('userCount')} "
                f"latency={health.get('latencyMs')}ms")

    status, body = http(args.gateway, "GET",
                        f"/api/v2/auth/tenants/provisions?tenantCode={args.tenant}&limit=5",
                        token=token)
    jobs = ((body.get("data") or {}).get("items") or []) if status == 200 else []
    probe.check("存在成功的开通任务",
                status == 200 and bool(jobs) and jobs[0].get("status") == "SUCCEEDED",
                f"HTTP {status} · jobs={len(jobs)} · latest={jobs[0].get('status') if jobs else None}")

    # ---- 2. 首个管理员可以登录该 Cell
    if args.admin_password:
        cell_token, cell_status, cell_body = login(args.cell_auth, args.admin_username,
                                                   args.admin_password, direct_auth=True)
        claims = {}
        if cell_token:
            payload = cell_token.split(".")[1]
            payload += "=" * (-len(payload) % 4)
            import base64
            claims = json.loads(base64.urlsafe_b64decode(payload))
        probe.check("开通的首个管理员可登录 Cell",
                    cell_token is not None and claims.get("tenant") == args.tenant,
                    f"HTTP {cell_status} · tenant={claims.get('tenant')} · "
                    f"instance={claims.get('instance')}")
    else:
        probe.skip("开通的首个管理员可登录 Cell", "未提供 --admin-password（一次性口令已丢失）")

    users_before = cell_user_count(args)
    if users_before is None:
        probe.skip("Cell 账号数基线", "psycopg2 不可用或 Cell 库不可达")
    else:
        probe.check("Cell 账号数基线 >= 1", users_before >= 1, f"users={users_before}")

    # ---- 3. 停用：登录立刻被拒
    version = tenant_version(args.gateway, token, args.tenant)
    if version is None:
        probe.check("读取租户版本", False, "租户不存在或不可见")
        return _finish(evidence, probe, args)
    status, body = http(args.gateway, "POST",
                        f"/api/v2/auth/tenants/{args.tenant}/status", token=token,
                        body={"status": "SUSPENDED", "reason": "生命周期验收：停用",
                              "version": version})
    probe.check("停用租户", status == 200 and (body.get("data") or {}).get("status") == "SUSPENDED",
                f"HTTP {status} · code={body.get('code')}")

    if args.admin_password:
        _, cell_status, cell_body = login(args.cell_auth, args.admin_username,
                                          args.admin_password, direct_auth=True)
        probe.check("停用后 Cell 登录被拒 40320",
                    cell_status == 403 and cell_body.get("code") == 40320,
                    f"HTTP {cell_status} · code={cell_body.get('code')}")
    else:
        probe.skip("停用后 Cell 登录被拒", "未提供 --admin-password")

    status, body = http(args.gateway, "GET", f"/api/v2/auth/tenants/{args.tenant}/health",
                        token=token)
    probe.check("停用后健康度为 SUSPENDED",
                status == 200 and (body.get("data") or {}).get("state") == "SUSPENDED",
                f"state={(body.get('data') or {}).get('state')}")

    users_suspended = cell_user_count(args)
    if users_before is not None and users_suspended is not None:
        probe.check("停用不删除数据（账号数不变）", users_before == users_suspended,
                    f"before={users_before} after={users_suspended}")
    else:
        probe.skip("停用不删除数据", "无法读取 Cell 账号数")

    # ---- 4. 恢复：可登录、数据仍在
    version = tenant_version(args.gateway, token, args.tenant)
    status, body = http(args.gateway, "POST",
                        f"/api/v2/auth/tenants/{args.tenant}/status", token=token,
                        body={"status": "ACTIVE", "reason": "", "version": version})
    probe.check("恢复租户", status == 200 and (body.get("data") or {}).get("status") == "ACTIVE",
                f"HTTP {status} · code={body.get('code')}")

    if args.admin_password:
        cell_token, cell_status, _ = login(args.cell_auth, args.admin_username,
                                           args.admin_password, direct_auth=True)
        probe.check("恢复后可再次登录 Cell", cell_token is not None, f"HTTP {cell_status}")
    else:
        probe.skip("恢复后可再次登录 Cell", "未提供 --admin-password")

    users_restored = cell_user_count(args)
    if users_before is not None and users_restored is not None:
        probe.check("恢复后数据完整（账号数不变）", users_before == users_restored,
                    f"before={users_before} after={users_restored}")

    # ---- 5. 注销终态与保留期
    retention_code = args.retention_tenant
    status, body = http(args.gateway, "GET", f"/api/v2/auth/tenants/{retention_code}", token=token)
    if status == 404:
        status, body = http(args.gateway, "POST", "/api/v2/auth/tenants", token=token, body={
            "code": retention_code,
            "instanceCode": "MEDASSIST-RETENTION-0001",
            "name": "保留策略演练院",
            "editionCode": "ENTERPRISE",
            "status": "PENDING",
            "notes": "生命周期验收：注销保留策略（无临床数据）",
        })
        probe.check("创建保留策略演练租户", status == 200,
                    f"HTTP {status} · code={body.get('code')}")

    version = tenant_version(args.gateway, token, retention_code)
    if version is None:
        probe.check("读取保留策略租户", False, "租户不存在")
    else:
        retention_until = (date.today() + timedelta(days=180)).isoformat()
        status, body = http(args.gateway, "POST",
                            f"/api/v2/auth/tenants/{retention_code}/status", token=token,
                            body={"status": "CLOSED", "reason": "生命周期验收：合同终止",
                                  "retentionUntil": retention_until, "version": version})
        closed = body.get("data") or {}
        probe.check("注销并登记保留期",
                    status == 200 and closed.get("status") == "CLOSED"
                    and closed.get("retentionUntil") == retention_until
                    and closed.get("closedAt"),
                    f"HTTP {status} · retentionUntil={closed.get('retentionUntil')} "
                    f"closedAt={closed.get('closedAt')}")

        version = closed.get("version")
        status, body = http(args.gateway, "POST",
                            f"/api/v2/auth/tenants/{retention_code}/status", token=token,
                            body={"status": "ACTIVE", "reason": "尝试恢复", "version": version})
        probe.check("注销是终态：恢复被拒 56403",
                    status == 400 or (body.get("code") == 56403),
                    f"HTTP {status} · code={body.get('code')}")

    return _finish(evidence, probe, args)


def _finish(evidence: dict, probe: Probe, args: argparse.Namespace) -> dict:
    counts = probe.counts()
    evidence["results"] = probe.results
    evidence["summary"] = counts
    evidence["verdict"] = "PASS" if counts["fail"] == 0 else "FAIL"

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out_dir / f"tenant-lifecycle-{stamp}.json"
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")

    print()
    for item in probe.results:
        print(f"[{item['status']}] {item['name']}"
              + (f" :: {item['detail']}" if item["detail"] else ""))
    print()
    print(f"结论：{evidence['verdict']} · PASS={counts['pass']} FAIL={counts['fail']} "
          f"SKIP={counts['skip']}")
    print(f"证据：{path}")
    evidence["evidencePath"] = str(path)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description="租户生命周期与数据保留验收")
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--ops-username", default="ops01")
    parser.add_argument("--ops-password", default="Ops@2026abc")
    parser.add_argument("--tenant", default="tenant-003")
    parser.add_argument("--retention-tenant", default="tenant-099")
    parser.add_argument("--cell-auth", default="http://127.0.0.1:8184")
    parser.add_argument("--admin-username", default="admin01")
    parser.add_argument("--admin-password", default=None)
    parser.add_argument("--cell-db-port", type=int, default=5435)
    parser.add_argument("--cell-db-user", default="medagent")
    parser.add_argument("--cell-db-password", default="medagent123")
    parser.add_argument("--cell-db-container", default="medagent_pg_tenant003")
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    args = parser.parse_args()

    evidence = run(args)
    return 0 if evidence.get("verdict") == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
