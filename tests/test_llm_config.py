"""F6 模型配置管理：add/activate/remove 往返、连通性测试双分支、GET 脱敏。

配置文件 monkeypatch 到 tmp_path；审计 monkeypatch 为内存假实现；test_provider 用
monkeypatch httpx.post 假 200/超时，绝不触网。
"""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import llm_config as lc
from backend.core.auth import seed_default_users
from backend.main import app

_URL = "https://api.example.com/v1"
_KEY = "sk-abc1234567890xy"  # 18 位：可验证「前6后4中***」脱敏


class _FakeAudit:
    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append({"ts": "2026-01-01T00:00:00", "event_type": str(a[0] if a else "t"),
                             "actor": str(a[-1] if a else "x"), "payload": dict(k)})

    def recent(self, n=8):
        return self.entries[-n:]


def _isolate(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", _FakeAudit)


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- 核心：add / activate / remove 往返 ----

def test_add_activate_remove_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    assert lc.load_config() == {"chat": {"active": None, "providers": []},
                                "vision": {"active": None, "providers": []}}
    pid = lc.add_provider("chat", "openai", _URL + "/", "gpt-test", "测试模型", _KEY)
    assert pid.startswith("p-")
    # base_url 尾部 / 已规整、无激活项
    stored = lc.load_config()["chat"]["providers"][0]
    assert stored["base_url"] == _URL and stored["api_key"] == _KEY
    assert lc.active_provider("chat") is None

    lc.activate("chat", pid)
    p = lc.active_provider("chat")
    assert p["id"] == pid and p["model_id"] == "gpt-test" and p["api_key"] == _KEY
    assert json.load(open(tmp_path / "llm_providers.json", encoding="utf-8"))["chat"]["active"] == pid

    # vision 与 chat 互不影响
    vid = lc.add_provider("vision", "anthropic", "https://api.anthropic.com/v1", "claude-x", "", "vk-1234567890123456")
    lc.activate("vision", vid)
    assert lc.active_provider("vision")["id"] == vid
    assert lc.active_provider("chat")["id"] == pid

    # remove：删除激活项 → active 置空；未找到返回 False
    assert lc.remove_provider("chat", pid) is True
    assert lc.active_provider("chat") is None
    assert lc.load_config()["chat"]["providers"] == []
    assert lc.remove_provider("chat", pid) is False


def test_add_provider_validation(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    for kwargs in ({"kind": "bad", "api_format": "openai", "base_url": _URL, "model_id": "m"},
                   {"kind": "chat", "api_format": "grpc", "base_url": _URL, "model_id": "m"},
                   {"kind": "chat", "api_format": "openai", "base_url": "ftp://x/v1", "model_id": "m"},
                   {"kind": "chat", "api_format": "openai", "base_url": "not-a-url", "model_id": "m"},
                   {"kind": "chat", "api_format": "openai", "base_url": _URL, "model_id": "  "}):
        with pytest.raises(ValueError):
            lc.add_provider(api_key="k", display_name="", **kwargs)
    with pytest.raises(ValueError):
        lc.activate("chat", "p-nonexistent")


# ---- test_provider：假 200 / 超时 两分支（monkeypatch httpx.post，不触网）----

class _Resp:
    def __init__(self, status_code=200, text=""):
        self.status_code = status_code
        self.text = text

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("err", request=None, response=None)


def test_test_provider_openai_ok(monkeypatch):
    seen = {}

    def fake_post(url, **kw):
        seen.update(url=url, kw=kw)
        return _Resp(200)

    monkeypatch.setattr(lc.httpx, "post", fake_post)
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is True and isinstance(r["latency_ms"], int) and r["latency_ms"] >= 0
    assert r["detail"] and _KEY not in json.dumps(r), "结果不得回显 key"
    assert seen["url"] == _URL + "/chat/completions"
    assert seen["kw"]["headers"]["Authorization"] == "Bearer " + _KEY
    assert seen["kw"]["json"]["max_tokens"] == 8
    assert seen["kw"]["json"]["messages"][0]["content"] == "ping"


def test_test_provider_anthropic_headers(monkeypatch):
    seen = {}

    def fake_post(url, **kw):
        seen.update(url=url, kw=kw)
        return _Resp(200)

    monkeypatch.setattr(lc.httpx, "post", fake_post)
    r = lc.test_provider("anthropic", "https://api.anthropic.com/v1", "claude-x", "ak-1")
    assert r["ok"] is True
    assert seen["url"].endswith("/messages")
    assert seen["kw"]["headers"]["x-api-key"] == "ak-1"
    assert seen["kw"]["headers"]["anthropic-version"] == "2023-06-01"


def test_test_provider_timeout_branch(monkeypatch):
    def fake_post(url, **kw):
        raise httpx.TimeoutException("timed out")

    monkeypatch.setattr(lc.httpx, "post", fake_post)
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is False and "超时" in r["detail"]
    assert r["latency_ms"] >= 0 and _KEY not in json.dumps(r)


def test_test_provider_http_error(monkeypatch):
    def fake_post(url, **kw):
        return _Resp(401, '{"error":"invalid api key"}')

    monkeypatch.setattr(lc.httpx, "post", fake_post)
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is False and "401" in r["detail"]


def test_mask_key():
    assert lc.mask_key("") == ""
    assert lc.mask_key(_KEY) == _KEY[:6] + "***" + _KEY[-4:]
    assert lc.mask_key("short") == "***"


# ---- 路由：admin 门禁 + GET 脱敏 ----

def test_get_providers_masks_api_key_and_requires_admin(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    pid = lc.add_provider("chat", "openai", _URL, "gpt-test", "测试模型", _KEY)
    c = TestClient(app)
    doc = _login(c, "doctor01", "Med@2026")
    assert c.get("/api/v1/medical/admin/llm/providers", headers=_h(doc)).status_code == 403
    adm = _login(c, "admin01", "Med@2026")
    r = c.get("/api/v1/medical/admin/llm/providers", headers=_h(adm))
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["chat"]["active"] is None and d["vision"]["active"] is None
    entry = d["chat"]["providers"][0]
    assert entry["id"] == pid and entry["api_key"] != _KEY and "***" in entry["api_key"]
    assert _KEY not in json.dumps(d), "全响应任何位置不得出现完整 api_key"


def test_provider_routes_add_activate_delete(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    H = _h(adm)
    body = {"kind": "chat", "api_format": "openai", "base_url": _URL,
            "model_id": "gpt-test", "display_name": "测试", "api_key": _KEY}
    # 校验失败 → 400
    assert c.post("/api/v1/medical/admin/llm/providers", headers=H,
                  json={**body, "api_format": "bad"}).status_code == 400
    r = c.post("/api/v1/medical/admin/llm/providers", headers=H, json=body)
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    # activate（应同时清空 LLMFactory 缓存）
    from backend.core.llm_factory import LLMFactory
    LLMFactory._instances["sentinel"] = object()
    r = c.post("/api/v1/medical/admin/llm/activate", headers=H, json={"kind": "chat", "pid": pid})
    assert r.status_code == 200 and r.json()["ok"] is True
    assert lc.active_provider("chat")["id"] == pid
    assert "sentinel" not in LLMFactory._instances, "激活后必须重建模型缓存"
    # delete：未知 pid → 404；删除激活项 → active 置空
    assert c.delete("/api/v1/medical/admin/llm/providers/chat/p-none", headers=H).status_code == 404
    assert c.delete(f"/api/v1/medical/admin/llm/providers/chat/{pid}", headers=H).status_code == 200
    assert lc.active_provider("chat") is None


def test_test_route_uses_faked_post(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    monkeypatch.setattr(lc.httpx, "post", lambda url, **kw: _Resp(200))
    r = c.post("/api/v1/medical/admin/llm/test", headers=_h(adm),
               json={"kind": "chat", "api_format": "openai", "base_url": _URL,
                     "model_id": "m", "display_name": "", "api_key": "k"})
    assert r.status_code == 200 and r.json()["ok"] is True
    # 不落盘：配置文件不应被创建
    assert not (tmp_path / "llm_providers.json").exists()
