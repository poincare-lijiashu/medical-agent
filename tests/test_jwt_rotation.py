"""W3.5 JWT 密钥轮换验收（转型计划书）：

轮换全程零踢出——签发恒用新 secret，验签先新后旧（previous 过渡窗口）：
1) 轮换前旧 secret 签发的令牌：轮换后（新 secret + previous=旧值）仍验签通过；
2) 未配置 previous（轮换完成态）：旧 secret 令牌立即 401；
3) 刷新令牌同样走双 secret；
4) 生产校验：previous 为默认弱密钥时拒绝启动。
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.config import settings
from backend.main import app


@pytest.fixture(autouse=True)
def _seed_users():
    # M-29 整改：conftest 已把 users 表隔离到 tmp_path（空表），需显式注入演示账号
    from backend.core.auth import seed_default_users
    seed_default_users()


def test_secret_rotation_zero_kickout(monkeypatch):
    """换 secret + previous=旧值 → 旧令牌仍可用（在线用户零踢出）。"""
    old_secret = "old-secret-for-rotation-0123456789abcdef"
    monkeypatch.setattr(settings, "auth_jwt_secret", old_secret)
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", "")
    tok = auth_mod.create_access_token("doctor01", "doctor")
    assert auth_mod.decode_token(tok) is not None  # 轮换前有效

    # 轮换：新 secret 上位，旧值进过渡窗口
    monkeypatch.setattr(settings, "auth_jwt_secret", "new-secret-for-rotation-fedcba9876543210")
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", old_secret)
    assert auth_mod.decode_token(tok) is not None, "过渡期旧令牌应仍验签通过"
    # 新签发的令牌用新 secret（previous 无法伪造新签发面）
    tok2 = auth_mod.create_access_token("doctor01", "doctor")
    assert auth_mod.decode_token(tok2) is not None


def test_rotation_complete_old_secret_rejected(monkeypatch):
    """过渡结束清空 previous → 旧 secret 令牌立即失效（轮换真正完成）。"""
    old_secret = "old-secret-for-rotation-0123456789abcdef"
    monkeypatch.setattr(settings, "auth_jwt_secret", old_secret)
    tok = auth_mod.create_access_token("doctor01", "doctor")
    monkeypatch.setattr(settings, "auth_jwt_secret", "new-secret-for-rotation-fedcba9876543210")
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", "")
    assert auth_mod.decode_token(tok) is None


def test_refresh_token_dual_secret(monkeypatch):
    """刷新令牌同样走双 secret：轮换前签发的 refresh 在过渡期仍可解码。"""
    old_secret = "old-secret-for-rotation-0123456789abcdef"
    monkeypatch.setattr(settings, "auth_jwt_secret", old_secret)
    rtok = auth_mod.create_refresh_token("doctor01")
    monkeypatch.setattr(settings, "auth_jwt_secret", "new-secret-for-rotation-fedcba9876543210")
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", old_secret)
    assert auth_mod.decode_refresh_payload(rtok) is not None


def test_startup_security_blocks_weak_previous_secret(monkeypatch):
    """生产校验：previous 为默认弱密钥时拒绝启动（previous 仍参与验签，弱旧密钥
    等同弱主密钥——攻击者可伪造令牌走 previous 分支）。"""
    from backend import main as backend_main

    monkeypatch.setattr(settings, "app_env", "production")
    monkeypatch.setattr(settings, "auth_jwt_secret", "x" * 40)
    monkeypatch.setattr(settings, "auth_enabled", True)
    monkeypatch.setattr(settings, "auth_token_in_body", False)
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", "dev-secret-change-in-production")
    with pytest.raises(RuntimeError, match="PREVIOUS"):
        backend_main.validate_startup_security()


def test_rotation_end_to_end_http(monkeypatch):
    """HTTP 全链路：轮换前登录拿 cookie → 轮换后（新 secret + previous）→
    携旧 cookie 请求 /auth/me 仍 200；新登录签发走新 secret。"""
    old_secret = "old-secret-for-rotation-0123456789abcdef"
    monkeypatch.setattr(settings, "auth_jwt_secret", old_secret)
    c = TestClient(app)
    assert c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).status_code == 200
    monkeypatch.setattr(settings, "auth_jwt_secret", "new-secret-for-rotation-fedcba9876543210")
    monkeypatch.setattr(settings, "auth_jwt_secret_previous", old_secret)
    r = c.get("/api/v1/auth/me")
    assert r.status_code == 200 and r.json()["username"] == "doctor01"
