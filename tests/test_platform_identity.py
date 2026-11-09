"""统一身份同步（第 129 批）测试：Java 令牌的 dept 声明 + 撤销/停用同步。

覆盖三条演示事故的直接根因：
1. Java 令牌里的科室中文名要被采纳（否则 Java 独有账号 dept 为空、看不到本科室数据）；
2. Java 侧登出/踢下线的 jti 要在同步周期后立即 401（不等到令牌 TTL）；
3. Java 侧停用账号后，即便令牌未过期也要 401。
"""
from __future__ import annotations

import time

import httpx
import jwt
import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from starlette.requests import Request

from backend.api import deps
from backend.config import settings
from backend.core import platform_identity


@pytest.fixture(autouse=True)
def _isolate_sync_state():
    platform_identity._reset_for_tests()
    yield
    platform_identity._reset_for_tests()


def _java_token(username: str = "java_only_user", roles=("DOCTOR",), dept: str | None = None,
                jti: str = "jti-1") -> str:
    now = int(time.time())
    payload = {"sub": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "username": username,
               "roles": list(roles), "jti": jti, "iat": now, "exp": now + 600, "org": "D003"}
    if dept:
        payload["dept"] = dept
    return jwt.encode(payload, settings.auth_jwt_secret, algorithm="HS256")


def test_java_token_dept_claim_is_adopted():
    """Java 独有账号（本地无记录）必须能取到令牌里的中文科室。"""
    user = deps._user_from_token(_java_token(dept="口腔科"))
    assert user["username"] == "java_only_user"
    assert user["role"] == "doctor"
    assert user["dept"] == "口腔科"


def test_revoked_jti_rejected(monkeypatch):
    """同步到撤销名单后，该 jti 立即 401。"""
    monkeypatch.setattr(platform_identity, "_ever_synced", True)
    monkeypatch.setattr(platform_identity, "_revoked", {"jti-x": time.time() + 600})
    with pytest.raises(HTTPException) as exc:
        deps._user_from_token(_java_token(jti="jti-x"))
    assert exc.value.status_code == 401


def test_disabled_account_rejected(monkeypatch):
    """同步到停用清单后，未过期令牌也立即 401（不再有 30 分钟窗口）。"""
    monkeypatch.setattr(platform_identity, "_ever_synced", True)
    monkeypatch.setattr(platform_identity, "_disabled", {"java_only_user"})
    with pytest.raises(HTTPException) as exc:
        deps._user_from_token(_java_token())
    assert exc.value.status_code == 401


def test_never_synced_does_not_block(monkeypatch):
    """Java 不可达/未配置时不得阻断业务：从未同步成功则放行。"""
    assert platform_identity.check("java_only_user", "jti-1") is True
    assert deps._user_from_token(_java_token())["username"] == "java_only_user"


def test_sync_once_parses_and_enforces(monkeypatch):
    """一次同步即可把撤销 jti 与停用账号纳入判定，且请求带内部令牌。"""
    monkeypatch.setattr(settings, "platform_auth_base_url", "http://auth.test")
    monkeypatch.setattr(settings, "internal_api_token", "internal-token")
    seen: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.headers.get("x-internal-token", "")))
        if request.url.path.endswith("/internal/revocations"):
            return httpx.Response(200, json={"code": 0, "data": {
                "revocations": [{"jti": "j1", "expiresAt": "2030-01-01T00:00:00Z"}],
                "serverTime": 1760000000000}})
        return httpx.Response(200, json={"code": 0, "data": ["Disabled01"]})

    monkeypatch.setattr(platform_identity, "_new_client",
                        lambda timeout_s: httpx.Client(transport=httpx.MockTransport(handler)))

    assert platform_identity.sync_once() is True
    assert all(tok == "internal-token" for _, tok in seen)
    assert platform_identity.check("anyone", "j1") is False
    assert platform_identity.check("disabled01", "j2") is False   # 大小写归一
    assert platform_identity.check("active01", "j2") is True


def test_sync_once_unconfigured_is_noop(monkeypatch):
    monkeypatch.setattr(settings, "platform_auth_base_url", "")
    assert platform_identity.sync_once() is False
    assert platform_identity.check("anyone", "j1") is True


# ---------- 角色映射：护士只读 / 非临床 guest ----------

def _req(method: str, path: str) -> Request:
    return Request({"type": "http", "method": method, "path": path,
                    "headers": [], "query_string": b"",
                    "server": ("testserver", 80), "client": ("127.0.0.1", 1234)})


def _call_require_user(token: str, method: str, path: str) -> dict:
    creds = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)
    return deps.require_user(_req(method, path), creds)


def test_nurse_read_only_blocks_writes():
    token = _java_token(username="nurse01", roles=("NURSE",))
    # 读：放行（require_user 本身通过）
    assert _call_require_user(token, "GET", "/api/v1/medical/overview")["role"] == "nurse"
    # 写：入口处 403
    with pytest.raises(HTTPException) as exc:
        _call_require_user(token, "POST", "/api/v1/medical/prescriptions")
    assert exc.value.status_code == 403
    # 会话管理必须放行，否则护士无法登出
    assert _call_require_user(token, "POST", "/api/v1/auth/logout")["role"] == "nurse"


def test_nurse_satisfies_doctor_on_read_only():
    token = _java_token(username="nurse01", roles=("NURSE",))
    dep = deps.require_role("doctor")
    user = _call_require_user(token, "GET", "/api/v1/medical/case-archive/mine")
    # 直接调用内层依赖：读取端点在安全方法下应放行
    assert dep(_req("GET", "/api/v1/medical/case-archive/mine"), user)["role"] == "nurse"
    with pytest.raises(HTTPException) as exc:
        dep(_req("POST", "/api/v1/medical/qc/record"), user)
    assert exc.value.status_code == 403


def test_non_clinical_and_unknown_roles_map_to_guest():
    assert deps._user_from_token(_java_token(username="cashier_x", roles=("REGISTRAR",)))["role"] == "guest"
    assert deps._user_from_token(_java_token(username="mystery_x", roles=("SOMETHING_NEW",)))["role"] == "guest"
