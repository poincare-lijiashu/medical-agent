"""第 177 批（评测 04 P1-5）：PG 连接 TLS 参数测试。

`async_database_url` 只在配置了 `pg_sslmode` / `pg_sslrootcert` 时追加参数，
未配置保持旧 URL（本地无 TLS 的 PG 不受影响）；显式 DSN 优先级最高。
"""
import urllib.parse

from backend.config import settings


def _query(url: str) -> dict:
    return urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)


def test_sslmode_and_rootcert_appended(monkeypatch):
    monkeypatch.setattr(settings, "pg_async_dsn", "")
    monkeypatch.setattr(settings, "pg_sslmode", "verify-full")
    monkeypatch.setattr(settings, "pg_sslrootcert", "/etc/ssl/pg-ca.pem")

    q = _query(settings.async_database_url)
    assert q["sslmode"] == ["verify-full"]
    assert q["sslrootcert"] == ["/etc/ssl/pg-ca.pem"]


def test_no_tls_params_when_unset(monkeypatch):
    monkeypatch.setattr(settings, "pg_async_dsn", "")
    monkeypatch.setattr(settings, "pg_sslmode", "")
    monkeypatch.setattr(settings, "pg_sslrootcert", "")

    assert "?" not in settings.async_database_url


def test_explicit_dsn_wins(monkeypatch):
    monkeypatch.setattr(settings, "pg_async_dsn", "postgresql+asyncpg://custom/db")
    monkeypatch.setattr(settings, "pg_sslmode", "verify-full")

    assert settings.async_database_url == "postgresql+asyncpg://custom/db"
