#!/usr/bin/env python3
"""凭据生命周期与首登强制改密验收（第 285 批）。

覆盖的不只是"改密接口能返回 200"，而是把一条真实凭据从"他人代设"走到"本人所有"：

  1. 管理员开户 → 新账号登录：`mustChangePassword=true`（代设口令是临时凭据）；
  2. 自助改密：当前口令错误 / 弱口令 / 与原口令相同全部被拒；
  3. 改密成功：标记清零、旧刷新令牌立即失效、新令牌可用、新口令可登录、旧口令被拒；
  4. 管理员重置（系统生成一次性口令）：新口令可登录且标记重新置位；
  5. 收尾停用测试账号（不删除数据，保留审计）。

用法（auth 直连 8081，网关非必需）：

  python scripts/e2e/password_lifecycle_suite.py \
      --auth http://127.0.0.1:8081 --tenant tenant-001 \
      --admin-username admin01 --admin-password Med@2026
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "artifacts" / "provisioning"

ADMIN_PERMISSIONS = ",".join([
    "auth:user:read", "auth:user:create", "auth:user:update", "auth:user:disable",
    "auth:role:assign", "auth:user:reset_password",
])


def http(base: str, method: str, path: str, *, token: str | None = None,
         body: dict | None = None, headers: dict | None = None,
         timeout: int = 20) -> tuple[int, dict]:
    url = base.rstrip("/") + path
    final = {"Accept": "application/json"}
    if headers:
        final.update(headers)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        final["Content-Type"] = "application/json"
    if token:
        final["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, headers=final, method=method)
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


def data_of(body: dict) -> dict:
    value = body.get("data")
    return value if isinstance(value, dict) else {}


def code_of(body: dict) -> int | None:
    value = body.get("code")
    return value if isinstance(value, int) else None


class Probe:
    def __init__(self) -> None:
        self.results: list[dict] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.results.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})

    def counts(self) -> dict:
        return {
            "pass": sum(1 for i in self.results if i["status"] == "PASS"),
            "fail": sum(1 for i in self.results if i["status"] == "FAIL"),
        }


def login(base: str, tenant: str, username: str, password: str) -> tuple[int, dict]:
    return http(base, "POST", "/login", body={"username": username, "password": password},
                headers={"X-Tenant": tenant, "X-Actor": "password-suite"})


def me(base: str, tenant: str, user_id: str, token: str) -> tuple[int, dict]:
    return http(base, "GET", "/me", token=token,
                headers={"X-Tenant": tenant, "X-User-Id": user_id, "X-Actor": "password-suite"})


def change(base: str, tenant: str, user_id: str, token: str,
           current: str, new: str) -> tuple[int, dict]:
    return http(base, "POST", "/me/password", token=token,
                body={"currentPassword": current, "newPassword": new},
                headers={"X-Tenant": tenant, "X-User-Id": user_id,
                         "X-Actor": "password-suite", "X-Client-Ip": "127.0.0.1"})


def admin_headers(tenant: str, actor: str) -> dict:
    return {"X-Tenant": tenant, "X-Actor": actor, "X-Permissions": ADMIN_PERMISSIONS,
            "X-Client-Ip": "127.0.0.1"}


def run(args: argparse.Namespace) -> int:
    probe = Probe()
    base, tenant = args.auth, args.tenant
    stamp = time.strftime("%H%M%S")
    username = f"e2e_pwd_{stamp}"
    initial_password = "InitPass123"
    final_password = "Ward2026#Round"

    # ---- 0. 管理员登录（直连 auth 也要显式给 actor/权限头，网关平时负责注入） ----
    status, body = login(base, tenant, args.admin_username, args.admin_password)
    probe.check("管理员登录", status == 200 and bool(data_of(body).get("accessToken")),
                f"http={status} code={code_of(body)}")
    if status != 200:
        return report(probe)
    admin_token = data_of(body)["accessToken"]

    # ---- 1. 开户：管理员代设口令 → 新账号登录应带首登标记 ----
    status, body = http(base, "POST", "/users", token=admin_token,
                        headers=admin_headers(tenant, args.admin_username),
                        body={"username": username, "password": initial_password,
                              "displayName": "改密验收账号", "roles": ["QC"]})
    created = data_of(body)
    probe.check("开户成功", status == 200 and bool(created.get("id")),
                f"http={status} code={code_of(body)}")
    if status != 200:
        return report(probe)
    user_id = created["id"]
    probe.check("开户即标记首登必改", created.get("mustChangePassword") is True,
                f"mustChangePassword={created.get('mustChangePassword')}")

    status, body = login(base, tenant, username, initial_password)
    first_login = data_of(body)
    probe.check("临时口令可登录", status == 200 and bool(first_login.get("accessToken")),
                f"http={status}")
    probe.check("登录响应要求改密",
                (first_login.get("user") or {}).get("mustChangePassword") is True,
                f"mustChangePassword={(first_login.get('user') or {}).get('mustChangePassword')}")
    if status != 200:
        return report(probe)
    access, refresh = first_login["accessToken"], first_login["refreshToken"]

    status, body = me(base, tenant, user_id, access)
    probe.check("/me 返回待改密", status == 200 and data_of(body).get("mustChangePassword") is True,
                f"http={status}")

    # ---- 2. 失败分支：当前口令错误 / 弱口令 / 与原口令相同 ----
    status, body = change(base, tenant, user_id, access, "WrongPass1", final_password)
    probe.check("当前口令错误被拒", status == 422 and "当前密码不正确" in str(body.get("message")),
                f"http={status} code={code_of(body)} message={body.get('message')}")

    status, body = change(base, tenant, user_id, access, initial_password, "12345678")
    probe.check("弱口令被拒", status == 422, f"http={status} code={code_of(body)}")

    status, body = change(base, tenant, user_id, access, initial_password, initial_password)
    probe.check("新旧口令相同被拒", status == 422, f"http={status} code={code_of(body)}")

    # ---- 3. 改密成功：清标记 + 旧刷新令牌失效 + 新令牌可用 ----
    status, body = change(base, tenant, user_id, access, initial_password, final_password)
    rotated = data_of(body)
    probe.check("改密成功并换发令牌",
                status == 200 and bool(rotated.get("accessToken"))
                and (rotated.get("user") or {}).get("mustChangePassword") is False,
                f"http={status} code={code_of(body)}")
    if status != 200:
        return report(probe)
    new_access, new_refresh = rotated["accessToken"], rotated["refreshToken"]
    probe.check("旧刷新令牌已失效",
                new_refresh != refresh
                and http(base, "POST", "/refresh", body={"refreshToken": refresh},
                         headers={"X-Tenant": tenant})[0] == 401,
                "旧 refresh token 换令牌应 401")

    status, body = me(base, tenant, user_id, new_access)
    probe.check("新访问令牌可用且标记清零",
                status == 200 and data_of(body).get("mustChangePassword") is False,
                f"http={status}")

    status, _ = login(base, tenant, username, final_password)
    probe.check("新口令可登录", status == 200, f"http={status}")
    status, _ = login(base, tenant, username, initial_password)
    probe.check("旧口令被拒", status == 401, f"http={status}")

    # ---- 4. 管理员重置（系统生成一次性口令）→ 标记重新置位 ----
    status, body = http(base, "POST", f"/users/{user_id}/reset-password", token=admin_token,
                        headers=admin_headers(tenant, args.admin_username), body={})
    reset = data_of(body)
    temp = reset.get("temporaryPassword")
    probe.check("重置返回一次性临时口令",
                status == 200 and reset.get("generated") is True
                and isinstance(temp, str) and len(temp) >= 12,
                f"http={status} generated={reset.get('generated')} length={len(temp or '')}")

    if temp:
        status, body = login(base, tenant, username, temp)
        probe.check("临时口令可登录且再次要求改密",
                    status == 200
                    and (data_of(body).get("user") or {}).get("mustChangePassword") is True,
                    f"http={status}")

    # ---- 5. 收尾：停用测试账号（不删除数据，审计可追溯） ----
    status, body = http(base, "POST", f"/users/{user_id}/disable", token=admin_token,
                        headers=admin_headers(tenant, args.admin_username))
    probe.check("收尾停用测试账号", status == 200, f"http={status} code={code_of(body)}")

    return report(probe, username=username)


def report(probe: Probe, *, username: str | None = None) -> int:
    counts = probe.counts()
    print("=" * 68)
    print("凭据生命周期与首登强制改密验收（§285）")
    if username:
        print(f"测试账号：{username}")
    for item in probe.results:
        print(f"  [{item['status']}] {item['name']}"
              + (f" · {item['detail']}" if item["detail"] else ""))
    print(f"合计：PASS {counts['pass']} / FAIL {counts['fail']}")
    print("=" * 68)
    out = DEFAULT_OUT / "password-lifecycle-result.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"counts": counts, "results": probe.results,
                               "username": username, "at": time.strftime("%Y-%m-%dT%H:%M:%S")},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if counts["fail"] == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="凭据生命周期与首登强制改密验收")
    parser.add_argument("--auth", default="http://127.0.0.1:8081")
    parser.add_argument("--tenant", default="tenant-001")
    parser.add_argument("--admin-username", default="admin01")
    parser.add_argument("--admin-password", default="Med@2026")
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
