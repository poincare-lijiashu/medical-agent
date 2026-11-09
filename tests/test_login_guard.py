"""评测 04 P1-6：登录爆破防护（按 账号+IP 连续失败锁定）。

验收口径：
1. 连续 `LOGIN_FAIL_LIMIT`（默认 5）次失败后返回 429 + `Retry-After`，锁定期内
   **即使口令正确也拒绝**（不再消耗 PBKDF2）；
2. 成功登录清零计数（正常用户偶尔输错不会被锁）；
3. 锁定按 (账号, IP) 隔离：同一账号换 IP、同一 IP 换账号都不受影响；
4. 不存在的账号同样走锁定，且提示文案与真实账号一致（防账号枚举）；
5. `LOGIN_FAIL_LIMIT=0` 可关闭（默认开启）。
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.core import login_guard
from backend.main import app

DEMO_USER = "doctor01"
DEMO_PASS = "Med@2026"
LOCK_S = 900


@pytest.fixture()
def client() -> TestClient:
    from backend.core.auth import seed_default_users
    seed_default_users()
    return TestClient(app)  # 不进 with：跳过 lifespan 的模型预加载


def _login(client: TestClient, username: str, password: str):
    return client.post("/api/v1/auth/login", json={"username": username, "password": password})


def test_consecutive_failures_lock_and_retry_after(client):
    for _ in range(4):
        assert _login(client, DEMO_USER, "wrong-pass").status_code == 401

    r = _login(client, DEMO_USER, "wrong-pass")
    assert r.status_code == 429, r.text
    assert r.headers.get("Retry-After") == str(LOCK_S)
    assert "分钟" in r.json()["detail"]

    # 锁定期内正确口令同样拒绝——锁定发生在 PBKDF2 之前
    assert _login(client, DEMO_USER, DEMO_PASS).status_code == 429


def test_successful_login_resets_failure_counter(client):
    for _ in range(4):
        assert _login(client, DEMO_USER, "wrong-pass").status_code == 401
    assert _login(client, DEMO_USER, DEMO_PASS).status_code == 200
    # 计数已清零：再一次失败仍是 401，而不是第 5 次触发锁定
    assert _login(client, DEMO_USER, "wrong-pass").status_code == 401


def test_lock_is_isolated_by_account_and_ip():
    for _ in range(5):
        login_guard.record_failure(DEMO_USER, "10.0.0.1")
    assert login_guard.locked_seconds(DEMO_USER, "10.0.0.1") > 0
    assert login_guard.locked_seconds(DEMO_USER, "10.0.0.2") == 0
    assert login_guard.locked_seconds("admin01", "10.0.0.1") == 0

    login_guard.clear(DEMO_USER, "10.0.0.1")
    assert login_guard.locked_seconds(DEMO_USER, "10.0.0.1") == 0
    assert login_guard.status(DEMO_USER, "10.0.0.1")["failures"] == 0


def test_unknown_account_locks_without_user_enumeration(client):
    for _ in range(4):
        assert _login(client, "ghost_user", "whatever").status_code == 401
    r = _login(client, "ghost_user", "whatever")
    assert r.status_code == 429
    # 与"真实账号被锁"同一文案，不泄露账号是否存在
    assert "用户名或密码错误" not in r.json()["detail"]
    assert "分钟" in r.json()["detail"]


def test_guard_can_be_disabled(client, monkeypatch):
    from backend.config import settings
    monkeypatch.setattr(settings, "login_fail_limit", 0)
    for _ in range(8):
        assert _login(client, DEMO_USER, "wrong-pass").status_code == 401
    assert login_guard.locked_seconds(DEMO_USER, "testclient") == 0
