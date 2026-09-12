"""F6 模型配置管理：add/activate/remove 往返、连通性测试双分支、GET 脱敏。

配置文件 monkeypatch 到 tmp_path；审计 monkeypatch 为内存假实现；test_provider 用
monkeypatch httpx.Client（FIND-06：实现走 Client(trust_env=False)，不再用模块级 httpx.post）
假 200/超时/非 JSON，绝不触网。
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

    def recent(self, n=8, offset=0):
        end = max(0, len(self.entries) - offset)  # 与真实 AuditLog.recent 翻页语义一致
        return list(self.entries[max(0, end - n):end])[::-1]


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


# ---- 终评 F4：base_url 内网/回环黑名单（SSRF 收口）+ LLM_ALLOW_PRIVATE_HOSTS 放行开关 ----

def test_base_url_private_hosts_rejected(monkeypatch, tmp_path):
    """各私网/回环形态一律 ValueError（文案「不允许指向内网/回环地址」）：
    localhost、127.x、10.x、172.16-31.x、192.168.x、169.254.x（云元数据）、0.0.0.0、::1。"""
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    for url in ("http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://127.9.9.9/v1",
                "http://10.1.2.3/v1", "http://172.16.0.9/v1", "http://172.31.255.255/v1",
                "http://192.168.1.5:8000/v1", "http://169.254.169.254/latest",
                "http://0.0.0.0:8000", "http://[::1]:8000/v1"):
        with pytest.raises(ValueError, match="内网/回环"):
            lc.add_provider("chat", "openai", url, "m", "", "k")


def test_base_url_public_host_accepted_and_switch_override(monkeypatch, tmp_path):
    """公网域名照常放行；LLM_ALLOW_PRIVATE_HOSTS=true（development 本地网关）显式放行内网。"""
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    pid = lc.add_provider("chat", "openai", _URL, "m", "", "k")  # 公网域名不受影响
    assert pid.startswith("p-")
    # 开关放行：本地网关场景（先证默认拒、再证开关开则过）
    with pytest.raises(ValueError, match="内网/回环"):
        lc.add_provider("vision", "openai", "http://127.0.0.1:11434/v1", "ollama", "", "k")
    from backend.config import settings
    monkeypatch.setattr(settings, "llm_allow_private_hosts", True)
    pid2 = lc.add_provider("vision", "openai", "http://127.0.0.1:11434/v1", "ollama", "", "k")
    assert pid2.startswith("p-")


def test_route_add_provider_private_host_400(monkeypatch, tmp_path):
    """路由层透传：admin 新增 provider 指向内网 → 400 带中文文案（不落盘、不计审计成功）。"""
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    tok = _login(c, "admin01", "Med@2026")
    r = c.post("/api/v1/medical/admin/llm/providers", headers=_h(tok),
               json={"kind": "chat", "api_format": "openai", "base_url": "http://192.168.0.9/v1",
                     "model_id": "m", "display_name": "内网", "api_key": "k"})
    assert r.status_code == 400, r.text
    assert "内网/回环" in r.text
    assert lc.load_config()["chat"]["providers"] == []


# ---- test_provider：假 200 / 超时 等分支（monkeypatch httpx.Client，不触网）----

class _Resp:
    def __init__(self, status_code=200, text="", json_data=None):
        self.status_code = status_code
        self.text = text
        # FIND-06：200 后会 r.json() 校验结构；默认给 openai/anthropic 双兼容的最小合法结构
        self._json = json_data if json_data is not None else {
            "choices": [{"message": {"content": "pong"}}],
            "content": [{"type": "text", "text": "pong"}],
        }

    def json(self):
        return self._json

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("err", request=None, response=None)


class _NoJsonResp:
    """FIND-06：网关/代理返回 200 但 body 非 JSON。"""

    status_code = 200
    text = "<html>502 gateway</html>"

    def json(self):
        raise ValueError("no json")


def _fake_client(monkeypatch, handler):
    """把 lc.httpx.Client 替换为记录构造 kwargs 与 post 调用的假客户端。"""
    holder = {"ctor": None, "calls": []}

    class _FakeClient:
        def __init__(self, **kw):
            holder["ctor"] = kw

        def post(self, url, **kw):
            holder["calls"].append((url, kw))
            return handler(url, **kw)

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    monkeypatch.setattr(lc.httpx, "Client", _FakeClient)
    return holder


def test_test_provider_openai_ok(monkeypatch):
    holder = _fake_client(monkeypatch, lambda url, **kw: _Resp(200))
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is True and isinstance(r["latency_ms"], int) and r["latency_ms"] >= 0
    assert r["detail"] and _KEY not in json.dumps(r), "结果不得回显 key"
    url, kw = holder["calls"][0]
    assert url == _URL + "/chat/completions"
    assert kw["headers"]["Authorization"] == "Bearer " + _KEY
    assert kw["json"]["max_tokens"] == 8
    assert kw["json"]["messages"][0]["content"] == "ping"
    assert kw["json"]["enable_thinking"] is False  # FIND-05：与 llm_factory 真实请求对齐
    assert holder["ctor"]["trust_env"] is False  # FIND-06：与工厂纪律一致（绕过系统代理）


def test_test_provider_anthropic_headers(monkeypatch):
    holder = _fake_client(monkeypatch, lambda url, **kw: _Resp(200))
    r = lc.test_provider("anthropic", "https://api.anthropic.com/v1", "claude-x", "ak-1")
    assert r["ok"] is True
    url, kw = holder["calls"][0]
    assert url.endswith("/messages")
    assert kw["headers"]["x-api-key"] == "ak-1"
    assert kw["headers"]["anthropic-version"] == "2023-06-01"


def test_test_provider_timeout_branch(monkeypatch):
    def fake_post(url, **kw):
        raise httpx.TimeoutException("timed out")

    _fake_client(monkeypatch, fake_post)
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is False and "超时" in r["detail"]
    assert r["latency_ms"] >= 0 and _KEY not in json.dumps(r)


def test_test_provider_http_error(monkeypatch):
    _fake_client(monkeypatch, lambda url, **kw: _Resp(401, '{"error":"invalid api key"}'))
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is False and "401" in r["detail"]


def test_test_provider_non_json_200(monkeypatch):
    """FIND-06：200 但非 JSON（网关/代理页）→ 不算连通。"""
    _fake_client(monkeypatch, lambda url, **kw: _NoJsonResp())
    r = lc.test_provider("openai", _URL, "m1", _KEY)
    assert r["ok"] is False and "非 JSON" in r["detail"]


def test_test_provider_200_without_expected_shape(monkeypatch):
    """FIND-06：200 JSON 但缺 choices/content 结构 → 不算连通。"""
    _fake_client(monkeypatch, lambda url, **kw: _Resp(200, json_data={"error": "gateway-ish"}))
    r = lc.test_provider("anthropic", "https://api.anthropic.com/v1", "claude-x", "ak-1")
    assert r["ok"] is False and "结构" in r["detail"]


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


# ---- 批次三：deactivate（回落 .env 内置配置）----

def test_deactivate_core(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    pid = lc.add_provider("chat", "openai", _URL, "gpt-test", "测试模型", _KEY)
    lc.activate("chat", pid)
    assert lc.active_provider("chat")["id"] == pid
    lc.deactivate("chat")
    assert lc.active_provider("chat") is None
    assert json.load(open(tmp_path / "llm_providers.json", encoding="utf-8"))["chat"]["active"] is None
    with pytest.raises(ValueError):
        lc.deactivate("bad")  # kind 存在校验


def test_deactivate_route_resets_cache_and_audits(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    pid = lc.add_provider("chat", "openai", _URL, "gpt-test", "测试模型", _KEY)
    lc.activate("chat", pid)
    from backend.core.llm_factory import LLMFactory
    LLMFactory._instances["sentinel"] = object()
    c = TestClient(app)
    doc = _login(c, "doctor01", "Med@2026")
    assert c.post("/api/v1/medical/admin/llm/deactivate", headers=_h(doc),
                  json={"kind": "chat"}).status_code == 403
    adm = _login(c, "admin01", "Med@2026")
    r = c.post("/api/v1/medical/admin/llm/deactivate", headers=_h(adm), json={"kind": "chat"})
    assert r.status_code == 200 and r.json()["ok"] is True
    assert lc.active_provider("chat") is None
    assert "sentinel" not in LLMFactory._instances, "停用后必须重建模型缓存"
    assert c.post("/api/v1/medical/admin/llm/deactivate", headers=_h(adm),
                  json={"kind": "bad"}).status_code == 400


def test_deactivate_falls_back_to_builtin_qwen_kwargs(monkeypatch, tmp_path):
    """前端「使用内置配置」按钮语义：清空激活后 active_provider=None，
    _build_model_kwargs 回落 .env 内置 Qwen 参数（用户配置保留在 providers 中可切回）。"""
    from backend.config import settings
    from backend.core.llm_factory import LLMFactory
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    pid = lc.add_provider("chat", "openai", _URL, "gpt-test", "测试模型", _KEY)
    lc.activate("chat", pid)
    # 激活时走 provider 参数
    kw_active = LLMFactory._build_model_kwargs("medical_literature")
    assert kw_active["model"] == "gpt-test" and kw_active["base_url"] == _URL
    # 清空激活（=回退内置配置）→ active None 且回落 Qwen
    lc.deactivate("chat")
    assert lc.active_provider("chat") is None
    assert lc.active_provider("vision") is None
    kw = LLMFactory._build_model_kwargs("medical_literature")
    assert kw["model"] == settings.qwen_model_chat
    assert kw["base_url"] == settings.qwen_base_url
    assert kw["api_key"] == settings.qwen_api_key
    assert kw["model_provider"] == "openai"
    # 用户配置未被清空，可随时切回
    assert lc.load_config()["chat"]["providers"][0]["id"] == pid
    lc.activate("chat", pid)
    assert lc.active_provider("chat")["id"] == pid


def test_test_route_uses_faked_post(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    _fake_client(monkeypatch, lambda url, **kw: _Resp(200))
    r = c.post("/api/v1/medical/admin/llm/test", headers=_h(adm),
               json={"kind": "chat", "api_format": "openai", "base_url": _URL,
                     "model_id": "m", "display_name": "", "api_key": "k"})
    assert r.status_code == 200 and r.json()["ok"] is True
    # 不落盘：配置文件不应被创建
    assert not (tmp_path / "llm_providers.json").exists()


# ---- F6/7008c07 回归锁定：extra_body 合并语义（openai/anthropic 双格式副作用排查） ----

def _deepseek_provider():
    return {"kind": "chat", "api_format": "openai", "id": "p-ds", "display_name": "DeepSeek",
            "base_url": "https://api.deepseek.com/v1", "model_id": "deepseek-chat", "api_key": _KEY}


def test_get_llm_merges_extra_body_without_dropping_thinking(monkeypatch):
    """commit 7008c07 回归锁定：get_llm 的 enable_thinking 注入必须「合并而非覆盖」——
    DeepSeek provider 的 thinking disabled 若被覆盖丢失，思考模式与 function-calling 的
    tool_choice 强制互斥 → refine 结构化输出 400。"""
    from backend.core import llm_factory as lf
    from backend.core.llm_factory import LLMFactory

    LLMFactory.clear_cache()
    monkeypatch.setattr(lf, "active_provider", lambda kind: _deepseek_provider())
    try:
        llm = LLMFactory.get_llm("medical_literature")
        eb = getattr(llm, "extra_body", None)
        assert eb is not None, "openai 格式 provider 必须带 extra_body"
        assert eb.get("thinking") == {"type": "disabled"}, "DeepSeek 思考关闭参数不得丢失"
        assert eb.get("enable_thinking") is False, "tool_choice 兼容注入必须存在"
    finally:
        LLMFactory.clear_cache()


def test_get_llm_non_deepseek_openai_gets_enable_thinking(monkeypatch):
    """非 DeepSeek 的 openai 兼容端点（Qwen3 等）：extra_body 至少含 enable_thinking=False。"""
    from backend.core import llm_factory as lf
    from backend.core.llm_factory import LLMFactory

    LLMFactory.clear_cache()
    monkeypatch.setattr(lf, "active_provider", lambda kind: {
        "kind": "chat", "api_format": "openai", "id": "p-q", "display_name": "Qwen",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "model_id": "qwen-plus", "api_key": _KEY})
    try:
        llm = LLMFactory.get_llm("medical_literature")
        eb = getattr(llm, "extra_body", None)
        assert eb is not None and eb.get("enable_thinking") is False
    finally:
        LLMFactory.clear_cache()


def test_provider_kwargs_anthropic_has_no_openai_extra_body():
    """anthropic 格式不得携带 openai 私有的 extra_body/enable_thinking（对 anthropic SDK
    是未知参数，存在被端点拒绝的副作用风险）；base_url 需裁掉管理端约定的尾部 /v1。"""
    from backend.core.llm_factory import LLMFactory

    kw = LLMFactory._provider_kwargs({"kind": "chat", "api_format": "anthropic", "id": "p-a",
                                      "display_name": "Claude", "base_url": "https://x.example.com/v1",
                                      "model_id": "claude-x", "api_key": "ak-1"})
    assert "extra_body" not in kw
    assert "enable_thinking" not in kw
    assert kw["base_url"] == "https://x.example.com"   # SDK 自行追加 /v1/messages
    assert kw["max_tokens"] == 1024
