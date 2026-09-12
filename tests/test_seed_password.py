"""A5 seed 演示口令：显式配置（AUTH_DEMO_PASSWORD）优先；未配置则随机生成（不再硬编码）。

外部审查 M1 追加：随机口令明文绝不写入文件日志（常规 logger 只记「已生成」事件、
不带口令值）；开发环境口令明文仅打印到控制台（独立 propagate=False logger）。
"""
import logging

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


def _capture_auth_file_log(monkeypatch, tmp_path):
    """给 auth 模块 logger 挂一个真实 FileHandler，等价捕获「文件日志通道」内容。"""
    fh = logging.FileHandler(tmp_path / "capture.log", encoding="utf-8")
    lg = logging.getLogger("backend.core.auth")
    lg.addHandler(fh)
    return fh, tmp_path / "capture.log"


def test_random_password_not_in_file_log_but_in_dev_console(monkeypatch, tmp_path, capsys):
    """M1（dev 环境）：seed 随机口令 → 文件 handler 捕获不含明文口令（仅「已生成」事件）；
    开发环境控制台仍打印明文（dev 流程靠看控制台拿口令，行为保留）。"""
    monkeypatch.setattr(settings, "auth_demo_password", "")
    monkeypatch.setattr(settings, "app_env", "development")
    fh, capture = _capture_auth_file_log(monkeypatch, tmp_path)
    try:
        pw = auth._demo_password()
    finally:
        fh.flush()
        fh.close()
        logging.getLogger("backend.core.auth").removeHandler(fh)
    content = capture.read_text(encoding="utf-8")
    assert "auth.seed.demo_password" in content, "文件日志应保留「已生成随机口令」事件"
    assert pw and pw not in content, "明文口令不得进入文件日志"
    assert pw in capsys.readouterr().out, "开发环境控制台必须打印口令"


def test_random_password_no_console_outside_dev(monkeypatch, tmp_path, capsys):
    """非开发环境（production）：文件日志无明文、控制台同样不打印口令。"""
    monkeypatch.setattr(settings, "auth_demo_password", "")
    monkeypatch.setattr(settings, "app_env", "production")
    fh, capture = _capture_auth_file_log(monkeypatch, tmp_path)
    try:
        pw = auth._demo_password()
    finally:
        fh.flush()
        fh.close()
        logging.getLogger("backend.core.auth").removeHandler(fh)
    content = capture.read_text(encoding="utf-8")
    assert pw and pw not in content
    assert pw not in capsys.readouterr().out, "非开发环境不得打印明文口令"
