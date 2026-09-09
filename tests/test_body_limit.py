"""A1 请求体上限：Content-Length 超过 settings.max_body_mb 时返回 413。

说明：max_body_mb=1 → 上限 1MB；故用 2MB 触发（任务描述中“2KB body”与 1MB 上限语义矛盾，
按实现语义以 2MB body 验证中间件拦截）。chunked 无 Content-Length 不拦截为已知边界，不做测试。
"""
from fastapi.testclient import TestClient

from backend.config import settings
from backend.main import app


def _client():
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def test_body_over_limit_returns_413(monkeypatch):
    c = _client()
    monkeypatch.setattr(settings, "max_body_mb", 1)  # settings 为 pydantic 单例，可直接改属性
    r = c.post("/api/v1/auth/login", json={"username": "u" * (2 * 1024 * 1024), "password": "p"})
    assert r.status_code == 413, r.text


def test_413_response_carries_security_headers(monkeypatch):
    """FIND-13：413 短路响应不经过 _security_headers 中间件，必须内联携带全套安全头。"""
    from backend.main import _SECURITY_HEADERS

    c = _client()
    monkeypatch.setattr(settings, "max_body_mb", 1)
    r = c.post("/api/v1/auth/login", json={"username": "u" * (2 * 1024 * 1024), "password": "p"})
    assert r.status_code == 413
    for k, v in _SECURITY_HEADERS.items():
        assert r.headers.get(k) == v, f"413 响应缺少安全头 {k}"
    assert r.headers.get("X-Request-Id"), "413 响应应带简版 X-Request-Id"


def test_normal_body_reaches_route_with_default_limit():
    c = _client()  # 默认 max_body_mb=40，小请求不应被拦截
    r = c.post("/api/v1/auth/login", json={"username": "nobody", "password": "wrong"})
    assert r.status_code != 413  # 到达路由层（401 等），未被中间件拦截


def test_413_response_is_json_detail(monkeypatch):
    """诊断3：413 响应改为 JSON 体 {"detail": "请求体过大（>NMB），请拆分文件"}
    （与 HTTPException 同构，前端可读 detail；N 随 settings.max_body_mb 动态生成）。"""
    c = _client()
    monkeypatch.setattr(settings, "max_body_mb", 64)
    r = c.post("/api/v1/auth/login", content=b"u" * (65 * 1024 * 1024))
    assert r.status_code == 413, r.text
    assert r.json() == {"detail": "请求体过大（>64MB），请拆分文件"}


def test_413_json_detail_tracks_configured_limit(monkeypatch):
    """诊断3：detail 里的上限随配置动态变化（不硬编码 64）。"""
    c = _client()
    monkeypatch.setattr(settings, "max_body_mb", 1)
    r = c.post("/api/v1/auth/login", json={"username": "u" * (2 * 1024 * 1024), "password": "p"})
    assert r.status_code == 413, r.text
    assert r.json() == {"detail": "请求体过大（>1MB），请拆分文件"}
