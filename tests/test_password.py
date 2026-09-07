"""密码功能：登录后修改密码 + admin 重置密码（隔离用户文件与审计）。"""
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core.auth import get_user, seed_default_users
from backend.main import app


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.auth.auth_router.get_audit_logger",
                        __import__("tests.test_admin_ops", fromlist=["_FakeAudit"])._FakeAudit)
    return TestClient(app)


def test_change_password_flow(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    tok = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    H = {"Authorization": "Bearer " + tok}
    # 旧密码错误 → 400
    r1 = c.post("/api/v1/auth/change-password", headers=H,
                json={"old_password": "wrong-pass", "new_password": "NewStr0ng!"})
    assert r1.status_code == 400
    # 新密码过弱 → 400
    r2 = c.post("/api/v1/auth/change-password", headers=H,
                json={"old_password": "Med@2026", "new_password": "123"})
    assert r2.status_code == 400
    # 正确修改 → 旧密码失效、新密码可登录
    r3 = c.post("/api/v1/auth/change-password", headers=H,
                json={"old_password": "Med@2026", "new_password": "NewStr0ng!"})
    assert r3.status_code == 200
    assert c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).status_code == 401
    assert c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "NewStr0ng!"}).status_code == 200


def test_admin_reset_password_and_role_guard(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    # 医生不能重置他人密码
    assert c.post("/api/v1/auth/reset-password", headers={"Authorization": "Bearer " + doc},
                  json={"username": "pharm01", "new_password": "Hacked09!"}).status_code == 403
    adm = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    r = c.post("/api/v1/auth/reset-password", headers={"Authorization": "Bearer " + adm},
               json={"username": "doctor01", "new_password": "Reset099!"})
    assert r.status_code == 200
    assert c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).status_code == 401
    assert c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Reset099!"}).status_code == 200
    # 未知用户 → 404
    assert c.post("/api/v1/auth/reset-password", headers={"Authorization": "Bearer " + adm},
                  json={"username": "nobody", "new_password": "Reset099!"}).status_code == 404
