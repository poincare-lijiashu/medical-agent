"""可观测/健康探针离线测试（TestClient，不走 lifespan 以免加载模型）。"""
from fastapi.testclient import TestClient

from backend.core import observability
from backend.main import app


def test_probes_and_request_id():
    c = TestClient(app)
    assert c.get("/healthz").status_code == 200
    r = c.get("/api/v1/health")
    assert r.status_code == 200 and r.headers.get("X-Request-Id")
    m = c.get("/metrics")
    assert m.status_code == 200
    assert "medassist_counter_total" in m.text or "medassist_latency_ms" in m.text


def test_metrics_exposition_shape():
    observability.reset()
    observability.inc("http_requests_total", 3)
    observability.observe_ms("/x", 12.0)
    obs = observability.exposition()
    assert 'medassist_counter_total{name="http_requests_total"} 3' in obs
    assert 'medassist_latency_ms_count{route="/x"} 1' in obs
