"""A5 seed 演示口令：显式配置（AUTH_DEMO_PASSWORD）优先；未配置则随机生成（不再硬编码）。"""
from backend.config import settings
from backend.core import auth


def test_demo_password_prefers_config(monkeypatch):
    monkeypatch.setattr(settings, "auth_demo_password", "Med@2026")
    assert auth._demo_password() == "Med@2026"


def test_demo_password_random_when_unset(monkeypatch):
    monkeypatch.setattr(settings, "auth_demo_password", "")
    pw = auth._demo_password()
    assert len(pw) >= 12
    assert pw != "Med@2026"
    assert auth._demo_password() != pw  # 随机分支每次生成不同口令


def test_seed_uses_configured_password(tmp_path, monkeypatch):
    monkeypatch.setattr(auth, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(settings, "auth_demo_password", "Test@Seed2026")
    auth.seed_default_users()
    assert auth.authenticate("doctor01", "Test@Seed2026") is not None
    assert auth.authenticate("doctor01", "Med@2026") is None
