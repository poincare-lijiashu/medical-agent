"""§303 控制台同源入口：`/api/v2/**` 反向代理到 Java 网关。

为什么必须锁住：控制台由 AI 中台在根路径托管（同镜像、同域、同登录），浏览器只会发
相对路径 `/api/v2/...`。缺少这段代理时，POST 会落进只注册了 GET 的 SPA fallback，
症状是"页面能打开、登录却 405/500"——功能看起来在，实际一步都走不通。
"""
from fastapi.testclient import TestClient

from backend.config import settings
from backend.main import app


def _client():
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def test_v2_route_registered_for_writes():
    route = next((r for r in app.routes if getattr(r, "path", "") == "/api/v2/{path:path}"), None)
    assert route is not None, "缺少 /api/v2 反向代理路由"
    for method in ("POST", "PUT", "PATCH", "DELETE"):
        assert method in route.methods, f"/api/v2 代理缺少 {method}"


def test_missing_gateway_config_is_503(monkeypatch):
    monkeypatch.setattr(settings, "platform_gateway_base_url", "")
    r = _client().post("/api/v2/auth/login", json={"username": "u", "password": "p"})
    assert r.status_code == 503, r.text
    assert r.json()["code"] == 50301


def test_unreachable_gateway_is_502(monkeypatch):
    monkeypatch.setattr(settings, "platform_gateway_base_url", "http://127.0.0.1:1")
    r = _client().post("/api/v2/auth/login", json={"username": "u", "password": "p"})
    assert r.status_code == 502, r.text
    assert r.json()["code"] == 50302


class _FakeResponse:
    status_code = 200
    headers = {"content-type": "application/json",
               "connection": "keep-alive",
               "transfer-encoding": "chunked"}

    async def aiter_raw(self):
        yield b'{"code":0,"message":"ok"}'

    async def aclose(self):
        return None


class _FakeClient:
    """只记录转发参数，不真的访问网络。"""

    is_closed = False

    def __init__(self):
        self.sent = None

    def build_request(self, method, url, headers=None, params=None, content=None):
        self.sent = {"method": method, "url": url, "headers": dict(headers or {})}
        return self.sent

    async def send(self, req, stream=True):
        return _FakeResponse()


def test_forwarding_strips_hop_by_hop_and_adds_forwarded_headers(monkeypatch):
    import backend.main as main

    fake = _FakeClient()
    monkeypatch.setattr(main, "_gateway_http", fake)
    r = _client().post("/api/v2/auth/login", json={"username": "u", "password": "p"},
                       headers={"Connection": "keep-alive", "X-Trace": "t-1"})
    assert r.status_code == 200, r.text
    lowered = {k.lower() for k in fake.sent["headers"]}
    assert "connection" not in lowered
    assert "content-length" not in lowered
    assert "transfer-encoding" not in lowered
    # 端到端追踪头要保留，否则网关侧无法把一次点击串起来
    assert fake.sent["headers"].get("x-trace") == "t-1"
    # 真实来源要透传，便于审计与租户路由
    assert fake.sent["headers"].get("x-forwarded-for")
    assert fake.sent["url"].endswith("/api/v2/auth/login")
