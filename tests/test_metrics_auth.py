"""G105 /metrics 抓取鉴权：Prometheus 静态令牌或 admin 登录态。"""
from __future__ import annotations

from fastapi.testclient import TestClient

from backend.config import settings
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_metrics_requires_admin_when_no_token(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "metrics_token", "")
    c = _client(monkeypatch, tmp_path)
    assert c.get("/metrics").status_code == 401

    adm = {"Authorization": "Bearer " + _login(c, "admin01")}
    r = c.get("/metrics", headers=adm)
    assert r.status_code == 200
    assert "medassist_agent_consult_requests_total" in r.text


def test_metrics_accepts_static_token(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "metrics_token", "s3cr3t-metrics")
    c = _client(monkeypatch, tmp_path)

    r = c.get("/metrics", headers={"Authorization": "Bearer s3cr3t-metrics"})
    assert r.status_code == 200
    assert "medassist_agent_consult_tokens_total" in r.text

    assert c.get("/metrics",
                 headers={"Authorization": "Bearer wrong"}).status_code == 401

    adm = {"Authorization": "Bearer " + _login(c, "admin01")}
    assert c.get("/metrics", headers=adm).status_code == 200

    doc = {"Authorization": "Bearer " + _login(c, "doctor01")}
    assert c.get("/metrics", headers=doc).status_code == 403

    # 非 ASCII 令牌必须 fail-closed，不能把鉴权依赖打成 500（HTTP 头仅允许 Latin-1，
    # 这里直接验证比较函数对非 ASCII 输入不抛异常）
    from backend.api import deps
    assert deps._metrics_token_matches("tökén-抓取", "s3cr3t-metrics") is False
    assert deps._metrics_token_matches("s3cr3t-metrics", "s3cr3t-metrics") is True


def test_metrics_fail_closed_when_auth_disabled_and_no_token(monkeypatch, tmp_path):
    """评审 S5：无鉴权且未配置 METRICS_TOKEN 时 /metrics 必须拒绝（不能公开）。"""
    monkeypatch.setattr(settings, "metrics_token", "")
    monkeypatch.setattr(settings, "auth_enabled", False)
    c = _client(monkeypatch, tmp_path)
    assert c.get("/metrics").status_code == 503


def test_metrics_token_channel_ignores_cookie(monkeypatch, tmp_path):
    """评审 S5：抓取令牌只认 Bearer 头，cookie 不能当作令牌通道。"""
    monkeypatch.setattr(settings, "metrics_token", "s3cr3t-metrics")
    monkeypatch.setattr(settings, "auth_enabled", True)
    c = _client(monkeypatch, tmp_path)
    c.cookies.set("medassist_at", "s3cr3t-metrics")
    assert c.get("/metrics").status_code == 401
    assert c.get("/metrics",
                 headers={"Authorization": "Bearer s3cr3t-metrics"}).status_code == 200
