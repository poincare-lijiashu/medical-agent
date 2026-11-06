"""W3.1 JWT httpOnly cookie 认证（转型计划书 WF-3）。

验收口径：token 不再出现在 JS 可达存储——
1) 登录/刷新把 access/refresh 令牌写 httpOnly Set-Cookie，响应体不再返回令牌；
2) cookie 认证可用（同源 StaticFiles 场景，浏览器自动携带）；
3) Bearer header 兼容保留（脚本/评测/服务间调用场景）；
4) /auth/logout 清除 cookie。
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.core.auth import create_access_token
from backend.main import app


@pytest.fixture()
def test_client():
    # M-29 整改：conftest 已把 users 表隔离到 tmp_path（空表），需显式注入演示账号
    from backend.core.auth import seed_default_users
    seed_default_users()
    return TestClient(app)


def _login(client: TestClient, username: str = "doctor01", password: str = "Med@2026"):
    return client.post("/api/v1/auth/login", json={"username": username, "password": password})


def test_login_sets_httponly_cookie(test_client: TestClient):
    r = _login(test_client)
    assert r.status_code == 200
    body = r.json()
    assert body["username"] == "doctor01"
    # Set-Cookie：httpOnly + SameSite=lax
    sc_at = r.headers.get("set-cookie", "")
    assert "medassist_at=" in sc_at
    assert "httponly" in sc_at.lower()
    assert "samesite=lax" in sc_at.lower()


def test_body_token_off_in_production_semantics(test_client: TestClient, monkeypatch):
    """生产语义：AUTH_TOKEN_IN_BODY 关闭时响应体不含令牌（默认配置即如此，
    conftest 为兼容既有测试显式开启，此处 monkeypatch 还原默认关）。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "auth_token_in_body", False)
    r = _login(test_client)
    assert r.status_code == 200
    assert r.json()["access_token"] == ""
    assert r.json()["refresh_token"] == ""
    assert "medassist_at=" in r.headers.get("set-cookie", "")


def test_cookie_auth_roundtrip(test_client: TestClient):
    assert _login(test_client).status_code == 200
    # TestClient 的 cookie jar 自动携带 httpOnly cookie（同源语义）
    r = test_client.get("/api/v1/auth/me")
    assert r.status_code == 200
    assert r.json()["username"] == "doctor01"


def test_bearer_header_still_supported(test_client: TestClient):
    tok = create_access_token("doctor01", "doctor")
    r = test_client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 200


def test_no_cookie_no_header_401(test_client: TestClient):
    r = test_client.get("/api/v1/auth/me")
    assert r.status_code == 401


def test_refresh_via_cookie(test_client: TestClient):
    assert _login(test_client).status_code == 200
    # 模拟 access 过期：仅持有 refresh cookie（httpx jar 需手工摘除 at）
    test_client.cookies.delete("medassist_at")
    r = test_client.post("/api/v1/auth/refresh", json={})
    assert r.status_code == 200
    assert "medassist_at=" in r.headers.get("set-cookie", "")


def test_logout_clears_cookie(test_client: TestClient):
    assert _login(test_client).status_code == 200
    r = test_client.post("/api/v1/auth/logout")
    assert r.status_code == 200
    # 登出后 cookie 已从 jar 移除（服务端 Set-Cookie 过期指令 + 客户端 jar 清空）
    r2 = test_client.get("/api/v1/auth/me")
    assert r2.status_code == 401
