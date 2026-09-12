"""密码功能：登录后修改密码 + admin 重置密码（隔离用户文件与审计）。

revoke 语义测试用独立新用户（doctor01 会被本文件/其它测试的改密与重置动作全局
revoke 污染——deps._revoked_before 为模块级状态，跨测试残留会误伤同秒签发令牌）。
"""
import time

from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core.auth import create_user, get_user, seed_default_users
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


def test_change_password_revokes_old_token(monkeypatch, tmp_path):
    """改密 revoke 语义（T2 前端强制重登依据）：改密成功后，旧 access token 一律 401
    （revoke_user 撤销早于撤销时间戳的全部令牌），新登录签发的令牌立即可用。
    用独立新账号规避模块级 _revoked_before 的跨测试残留（见模块 docstring）。
    终评 F9：受控时钟构造跨秒签发（不再 time.sleep(1.1) 真等）——auth.datetime 与
    deps 撤销戳共用同一推进时间线：旧令牌 iat=K → revoke 记录 K+0.9 → 新令牌 iat=K+1。"""
    import backend.api.deps as deps_mod

    class _Clock:
        """受控时钟替身：auth 签发（datetime.now）与 deps 撤销（time.time）同轴。"""
        t = 0.0

        @classmethod
        def now(cls, tz=None):
            import datetime as _dt
            return _dt.datetime.fromtimestamp(cls.t, tz=tz or _dt.timezone.utc)

        @staticmethod
        def time():
            return _Clock.t

    c = _client(monkeypatch, tmp_path)
    create_user("pwdrev01", "Med@2026", "doctor")
    # 起点拨到真实时间 -10s（iat 必须落在 PyJWT 按真实时钟的可信窗口内，超前会被判不成熟令牌）
    _Clock.t = int(time.time()) - 10 + 0.2           # 第 K 秒内（K=int(真实)-10）
    monkeypatch.setattr(auth_mod, "datetime", _Clock)
    monkeypatch.setattr(deps_mod, "time", _Clock)
    old_tok = c.post("/api/v1/auth/login",
                     json={"username": "pwdrev01", "password": "Med@2026"}).json()["access_token"]
    H = {"Authorization": "Bearer " + old_tok}
    assert c.get("/api/v1/auth/me", headers=H).status_code == 200  # 改密前旧 token 有效
    _Clock.t += 0.7                                 # K+0.9：revoke 记录落在本秒内
    r = c.post("/api/v1/auth/change-password", headers=H,
               json={"old_password": "Med@2026", "new_password": "NewStr0ng!"})
    assert r.status_code == 200
    # 旧 token 立即失效（iat=K < 撤销戳=K+0.9 → 前端改密后必须强制重新登录）
    assert c.get("/api/v1/auth/me", headers=H).status_code == 401
    # 推进到下一整秒中段（跨秒）：新登录 iat=K+1 ≥ 撤销戳 → 立即可用，无需真实等待
    _Clock.t = int(_Clock.t) + 1.5
    new_tok = c.post("/api/v1/auth/login",
                     json={"username": "pwdrev01", "password": "NewStr0ng!"}).json()["access_token"]
    assert c.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + new_tok}).status_code == 200


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
