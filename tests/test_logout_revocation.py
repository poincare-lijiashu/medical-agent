"""评测 04 P1-7：登出必须**服务端**失效本次会话（jti 撤销）。

验收口径：
1. 登出后，同一会话的**刷新令牌**不能再续签（此前只清 cookie，RT 仍可用 7 天）；
2. 登出后，同一会话的**访问令牌**立即 401（不必等 720 分钟自然过期）；
3. 撤销按会话隔离：A 设备登出不影响 B 设备；
4. 撤销记录进共享 KVStore：清掉本实例内存快路径后仍拒绝（模拟另一副本）；
5. Bearer 客户端（无 cookie）用 body 携带刷新令牌同样能撤销。
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from backend.api import deps
from backend.main import app

DEMO_USER = "doctor01"
DEMO_PASS = "Med@2026"


def _client() -> TestClient:
    from backend.core.auth import seed_default_users
    seed_default_users()
    return TestClient(app)


def _login(c: TestClient) -> dict:
    r = c.post("/api/v1/auth/login", json={"username": DEMO_USER, "password": DEMO_PASS})
    assert r.status_code == 200, r.text
    return r.json()


def test_logout_revokes_both_tokens_of_the_session():
    c = _client()
    tokens = _login(c)
    at, rt = tokens["access_token"], tokens["refresh_token"]
    assert c.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + at}).status_code == 200

    assert c.post("/api/v1/auth/logout").status_code == 200

    # 刷新令牌已被服务端撤销（不是只清 cookie）
    assert c.post("/api/v1/auth/refresh", json={"refresh_token": rt}).status_code == 401
    # 访问令牌同样立即失效
    assert c.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + at}).status_code == 401


def test_logout_only_affects_current_session():
    c1, c2 = _client(), _client()
    t1, t2 = _login(c1), _login(c2)

    assert c1.post("/api/v1/auth/logout").status_code == 200

    assert c2.get("/api/v1/auth/me",
                  headers={"Authorization": "Bearer " + t2["access_token"]}).status_code == 200
    assert c2.post("/api/v1/auth/refresh",
                   json={"refresh_token": t2["refresh_token"]}).status_code == 200
    assert c1.get("/api/v1/auth/me",
                  headers={"Authorization": "Bearer " + t1["access_token"]}).status_code == 401


def test_revocation_is_visible_to_other_replicas():
    """清掉本实例内存快路径后仍拒绝 → 撤销写进了共享 KVStore（多副本一致）。"""
    c = _client()
    tokens = _login(c)
    at = tokens["access_token"]
    assert c.post("/api/v1/auth/logout").status_code == 200

    deps._revoked_jti.clear()  # 模拟"另一副本自己的内存里没有这条记录"
    r = c.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + at})
    assert r.status_code == 401
    assert "会话已退出" in r.json()["detail"]


def test_bearer_client_can_logout_with_body_refresh_token():
    """无 cookie 的脚本/服务间调用：Authorization + body 刷新令牌也能撤销。"""
    c1 = _client()
    tokens = _login(c1)
    at, rt = tokens["access_token"], tokens["refresh_token"]

    fresh = TestClient(app)  # 全新 cookie jar：模拟另一台无状态客户端
    r = fresh.post("/api/v1/auth/logout",
                   headers={"Authorization": "Bearer " + at},
                   json={"refresh_token": rt})
    assert r.status_code == 200
    assert fresh.post("/api/v1/auth/refresh", json={"refresh_token": rt}).status_code == 401
