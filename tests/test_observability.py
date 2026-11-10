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
    assert "medassist_http_requests_total" in m.text
    assert "medassist_http_request_duration_seconds_bucket" in m.text


def test_metrics_exposition_shape():
    """评测 06 P1-5：一个指标一个名字 + 状态码标签 + 标准 histogram（可算 P95/P99）。"""
    observability.reset()
    observability.inc_http("/x", "GET", 500)
    observability.observe_seconds("/x", 0.012)
    obs = observability.exposition()

    assert 'medassist_http_requests_total{route="/x",method="GET",status="500"} 1' in obs
    assert "# TYPE medassist_http_request_duration_seconds histogram" in obs
    # le=0.012 落在 0.025 桶内，累计到该桶为 1
    assert 'medassist_http_request_duration_seconds_bucket{route="/x",le="0.025"} 1' in obs
    assert 'medassist_http_request_duration_seconds_bucket{route="/x",le="+Inf"} 1' in obs
    assert 'medassist_http_request_duration_seconds_sum{route="/x"}' in obs
    assert 'medassist_http_request_duration_seconds_count{route="/x"} 1' in obs
    # 旧的"标签当名字"写法必须彻底消失
    assert "medassist_counter_total" not in obs
    assert "medassist_latency_ms" not in obs


def test_latency_histogram_is_cumulative_and_monotonic():
    """桶必须累计（histogram_quantile 的前提）；超出最大桶的样本只计入 +Inf。"""
    observability.reset()
    for seconds in (0.004, 0.03, 42.0):
        observability.observe_seconds("/slow", seconds)
    counts = observability.snapshot()["latency"]["/slow"]["buckets"]
    assert sum(counts) == 2, "42s 超出最大桶（30s），只应计入 +Inf"

    text = observability.exposition()
    # 累计值：le=0.005 → 1；le=0.05 → 2；+Inf → 3
    assert 'medassist_http_request_duration_seconds_bucket{route="/slow",le="0.005"} 1' in text
    assert 'medassist_http_request_duration_seconds_bucket{route="/slow",le="0.05"} 2' in text
    assert 'medassist_http_request_duration_seconds_bucket{route="/slow",le="+Inf"} 3' in text
