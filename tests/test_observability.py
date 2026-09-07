"""可观测/健康探针离线测试（TestClient，不走 lifespan 以免加载模型）。"""
from fastapi.testclient import TestClient

from backend.core import observability
from backend.core.auth import seed_default_users
from backend.main import app


def test_probes_and_request_id():
    c = TestClient(app)
    assert c.get("/healthz").status_code == 200
    r = c.get("/api/v1/health")
    assert r.status_code == 200 and r.headers.get("X-Request-Id")
    seed_default_users()
    tok = c.post("/api/v1/auth/login",
                 json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    m = c.get("/metrics", headers={"Authorization": "Bearer " + tok})  # /metrics 仅限 admin（FASTAPI-METRICS-001）
    assert m.status_code == 200
    assert "medassist_counter_total" in m.text or "medassist_latency_ms" in m.text


def test_metrics_exposition_shape():
    observability.reset()
    observability.inc("http_requests_total", 3)
    observability.observe_ms("/x", 12.0)
    obs = observability.exposition()
    assert 'medassist_counter_total{name="http_requests_total"} 3' in obs
    assert 'medassist_latency_ms_count{route="/x"} 1' in obs
