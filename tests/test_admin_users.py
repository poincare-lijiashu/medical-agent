"""admin 用户管理：新增/删除用户端点（角色门禁 + 重名拒绝 + 删除后登录失效）。

用户文件与审计均 monkeypatch 到临时路径，不污染真实用户表。
"""
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core.auth import get_user, seed_default_users
from backend.main import app


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger",
                        __import__("tests.test_admin_ops", fromlist=["_FakeAudit"])._FakeAudit)
    return TestClient(app)


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_doctor_cannot_manage_users(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    H = {"Authorization": "Bearer " + doc}
    assert c.post("/api/v1/medical/admin/users", headers=H,
                  json={"username": "x", "password": "Passw0rd!", "role": "doctor"}).status_code == 403
    assert c.delete("/api/v1/medical/admin/users/x", headers=H).status_code == 403


def test_admin_create_user_and_login(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    H = {"Authorization": "Bearer " + adm}
    r = c.post("/api/v1/medical/admin/users", headers=H,
               json={"username": "doctor02", "password": "Str0ngPass!", "role": "doctor"})
    assert r.status_code == 200, r.text
    assert r.json()["ok"] is True
    assert get_user("doctor02")["role"] == "doctor"
    # 新用户可登录
    assert c.post("/api/v1/auth/login", json={"username": "doctor02", "password": "Str0ngPass!"}).status_code == 200
    # 重名拒绝
    r2 = c.post("/api/v1/medical/admin/users", headers=H,
                json={"username": "doctor02", "password": "Another1!", "role": "doctor"})
    assert r2.status_code == 400
    # 非法角色拒绝
    r3 = c.post("/api/v1/medical/admin/users", headers=H,
                json={"username": "bad", "password": "Str0ngPass!", "role": "hacker"})
    assert r3.status_code == 400
    # 弱密码拒绝
    r4 = c.post("/api/v1/medical/admin/users", headers=H,
                json={"username": "weak", "password": "123", "role": "doctor"})
    assert r4.status_code == 400


def test_admin_delete_user_blocks_login_and_self(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    H = {"Authorization": "Bearer " + adm}
    assert c.post("/api/v1/medical/admin/users", headers=H,
                  json={"username": "tmp01", "password": "Str0ngPass!", "role": "pharmacist"}).status_code == 200
    # 不能删自己
    assert c.delete("/api/v1/medical/admin/users/admin01", headers=H).status_code == 400
    # 删除后登录失效
    assert c.delete("/api/v1/medical/admin/users/tmp01", headers=H).status_code == 200
    assert c.post("/api/v1/auth/login", json={"username": "tmp01", "password": "Str0ngPass!"}).status_code == 401
    assert get_user("tmp01") is None


def test_admin_users_carry_dept(monkeypatch, tmp_path):
    """seed 演示账号带科室；创建用户透传 dept；/admin/data 的 users 列表带 dept。"""
    c = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    H = {"Authorization": "Bearer " + adm}
    assert get_user("doctor01")["dept"] == "口腔科"
    assert get_user("pharm01")["dept"] == "药剂科"
    assert get_user("admin01")["dept"] == "医务处"
    assert c.post("/api/v1/medical/admin/users", headers=H,
                  json={"username": "doc9", "password": "Str0ngPass!", "role": "doctor",
                        "dept": "外科"}).status_code == 200, get_user("doc9")
    assert get_user("doc9")["dept"] == "外科"
    data = c.get("/api/v1/medical/admin/data", headers=H).json()
    by_name = {u["username"]: u for u in data["users"]}
    assert by_name["doc9"]["dept"] == "外科"
    assert by_name["admin01"]["dept"] == "医务处"


def test_deleted_user_token_revoked_immediately(monkeypatch, tmp_path):
    """删除用户后，其已签发的访问令牌必须立即失效（此前最长 12h 内仍可调用 API）。"""
    c = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    H = {"Authorization": "Bearer " + adm}
    assert c.post("/api/v1/medical/admin/users", headers=H,
                  json={"username": "tmp02", "password": "Str0ngPass!", "role": "doctor"}).status_code == 200
    t2 = c.post("/api/v1/auth/login", json={"username": "tmp02", "password": "Str0ngPass!"}).json()["access_token"]
    assert c.get("/api/v1/medical/overview", headers={"Authorization": "Bearer " + t2}).status_code == 200
    assert c.delete("/api/v1/medical/admin/users/tmp02", headers=H).status_code == 200
    assert c.get("/api/v1/medical/overview", headers={"Authorization": "Bearer " + t2}).status_code == 401
