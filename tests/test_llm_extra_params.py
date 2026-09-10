"""任务2 provider 额外参数逃生舱 + 厂商自动预设：
- suggest_extra_params：按 base_url+model_id 匹配已知厂商（GLM 5.x 强制思考模型→thinking
  预设；deepseek/moonshot/minimax/anthropic→空建议）；
- extra_params 数据链路：add_provider JSON 校验（非法抛 ValueError→路由层 422）、存储往返；
- llm_factory 合并逻辑：openai/anthropic 双分支构造参数断言；显式配置优先
  （extra_params 的 temperature/max_tokens 覆盖工厂默认；思考参数显式给出时跳过默认
  enable_thinking=False 注入——GLM 5.x「始终思考」模型吃到关闭参数会 400）；
- 连通性测试端点透传 extra_params（真实验证用户填的参数）。

不触网：init_chat_model / httpx.Client 全部 monkeypatch。
"""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import llm_config as lc
from backend.core.auth import seed_default_users
from backend.core.llm_factory import LLMFactory
from backend.main import app

_URL = "https://api.example.com/v1"
_KEY = "sk-abc1234567890xy"


class _FakeAudit:
    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append({"ts": "2026-01-01T00:00:00", "event_type": str(a[0] if a else "t"),
                             "actor": str(a[-1] if a else "x"), "payload": dict(k)})

    def recent(self, n=8, offset=0):
        return list(self.entries)


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


# ---- suggest_extra_params：厂商预设纯函数 ----

def test_suggest_glm_base_url_returns_thinking_preset():
    """智谱 base_url → GLM-5.3 预设：thinking.type=enabled（强制思考，仅
    enabled/disabled 合法）+ reasoning_effort=high（报错文案的 low/high/max
    指 reasoning_effort，官方迁移文档实锤）。"""
    s = lc.suggest_extra_params("https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash")
    assert json.loads(s) == {"thinking": {"type": "enabled"}, "reasoning_effort": "high"}


def test_suggest_glm_model_prefix_matches_without_bigmodel_url():
    """非智谱网关但 model 前缀 glm-（大小写不敏感）→ 同样给 GLM 预设。"""
    assert json.loads(lc.suggest_extra_params("https://gw.example.com/v1", "glm-4.7")) == {
        "thinking": {"type": "enabled"}, "reasoning_effort": "high"}
    assert json.loads(lc.suggest_extra_params("https://gw.example.com/v1", "GLM-5")) == {
        "thinking": {"type": "enabled"}, "reasoning_effort": "high"}


def test_suggest_known_vendors_without_preset():
    """deepseek / moonshot / minimax / anthropic → 无预设（空串）。"""
    for url, model in (("https://api.deepseek.com/v1", "deepseek-chat"),
                       ("https://api.moonshot.cn/v1", "kimi-k2"),
                       ("https://api.minimax.chat/v1", "abab-6.5"),
                       ("https://api.anthropic.com/v1", "claude-sonnet-4")):
        assert lc.suggest_extra_params(url, model) == "", f"{url} 不应有预设"


def test_suggest_qwen_and_misc_empty():
    """Qwen 兼容端点与未知厂商 → 空预设。"""
    assert lc.suggest_extra_params("https://dashscope.aliyuncs.com/compatible-mode/v1",
                                   "qwen3-vl-plus") == ""
    assert lc.suggest_extra_params("", "") == ""


# ---- add_provider：extra_params JSON 校验与存储往返 ----

def test_add_provider_extra_params_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    pid = lc.add_provider("chat", "openai", _URL, "glm-5", "GLM", _KEY,
                          extra_params='{"thinking":{"type":"high"}}')
    stored = lc.load_config()["chat"]["providers"][0]
    assert stored["extra_params"] == '{"thinking":{"type":"high"}}'


def test_add_provider_extra_params_default_empty(monkeypatch, tmp_path):
    """不传 extra_params → 存空串（与既有 provider 结构向后兼容）。"""
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    lc.add_provider("chat", "openai", _URL, "m", "", _KEY)
    assert lc.load_config()["chat"]["providers"][0]["extra_params"] == ""


def test_add_provider_rejects_invalid_json(monkeypatch, tmp_path):
    """非法 JSON（含解析结果非 object 的）→ ValueError（路由层转 422）。"""
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))
    for bad in ("{not-json", "[1,2]", '"just-a-string"', "123"):
        with pytest.raises(ValueError):
            lc.add_provider("chat", "openai", _URL, "m", "", _KEY, extra_params=bad)


# ---- llm_factory 合并逻辑：构造参数断言（openai/anthropic 双分支） ----

def _capture_init_chat_model(monkeypatch) -> list[dict]:
    captured: list[dict] = []

    def _fake_init(**kwargs):
        captured.append(kwargs)

        class _M:
            pass
        return _M()

    monkeypatch.setattr("backend.core.llm_factory.init_chat_model", _fake_init)
    return captured


def test_provider_kwargs_openai_merges_thinking_into_model_kwargs():
    """openai 分支：extra_params 的 thinking → model_kwargs 透传（body 顶层）；
    用户显式管理思考模式 → 默认 enable_thinking=False 注入让位（GLM 5.x 始终思考
    模型吃到关闭参数 400：'该模型始终思考，不支持关闭思考'）。"""
    kw = LLMFactory._provider_kwargs({
        "kind": "chat", "api_format": "openai", "id": "p-g", "display_name": "GLM",
        "base_url": "https://open.bigmodel.cn/api/paas/v4", "model_id": "glm-5",
        "api_key": _KEY, "extra_params": '{"thinking":{"type":"high"}}'})
    assert kw["extra_body"]["thinking"] == {"type": "high"}
    assert kw.get("model_kwargs", {}).get("thinking") is None, "openai 自定义参数必须走 extra_body"
    assert "extra_body" in kw and kw.get("model_kwargs") in (None, {}), \
        "用户已显式给出 thinking 时不得再注入默认关闭参数"


def test_provider_kwargs_openai_without_extra_keeps_defaults():
    """无 extra_params → 现状行为不变（非 deepseek 注入 enable_thinking=False）。"""
    kw = LLMFactory._provider_kwargs({
        "kind": "chat", "api_format": "openai", "id": "p-q", "display_name": "Q",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "model_id": "qwen-plus", "api_key": _KEY})
    assert kw["extra_body"]["enable_thinking"] is False
    assert "model_kwargs" not in kw


def test_provider_kwargs_explicit_temperature_max_tokens_override_defaults():
    """显式配置优先：extra_params 的 temperature/max_tokens 覆盖工厂默认
    （anthropic 分支默认 temperature=0 / max_tokens=1024）。"""
    kw = LLMFactory._provider_kwargs({
        "kind": "chat", "api_format": "anthropic", "id": "p-a", "display_name": "A",
        "base_url": "https://api.anthropic.com/v1", "model_id": "claude-x",
        "api_key": "ak-1", "extra_params": '{"temperature":0.5,"max_tokens":512,"top_k":40}'})
    assert kw["temperature"] == 0.5
    assert kw["max_tokens"] == 512
    assert kw["model_kwargs"]["top_k"] == 40, "未知构造键 → model_kwargs 透传"


def test_provider_kwargs_invalid_extra_params_defensively_ignored():
    """读取侧防御：存储层已被保存校验拦截，但历史/手改数据可能非法——忽略不炸。"""
    kw = LLMFactory._provider_kwargs({
        "kind": "chat", "api_format": "openai", "id": "p-b", "display_name": "B",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "model_id": "qwen-plus", "api_key": _KEY, "extra_params": "{broken"})
    assert kw["extra_body"]["enable_thinking"] is False
    assert "model_kwargs" not in kw


def test_get_llm_glm_extra_params_no_default_thinking_injection(monkeypatch):
    """get_llm 全链路：GLM provider + thinking 预设 → extra_body.thinking 透传
    且无 enable_thinking=False（默认注入与用户显式配置互斥）。"""
    LLMFactory.clear_cache()
    monkeypatch.setattr("backend.core.llm_factory.active_provider", lambda kind: {
        "kind": "chat", "api_format": "openai", "id": "p-g", "display_name": "GLM",
        "base_url": "https://open.bigmodel.cn/api/paas/v4", "model_id": "glm-5",
        "api_key": _KEY, "extra_params": '{"thinking":{"type":"high"}}'})
    captured = _capture_init_chat_model(monkeypatch)
    try:
        LLMFactory.get_llm("medical_literature")
    finally:
        LLMFactory.clear_cache()
    kw = captured[0]
    assert kw["extra_body"]["thinking"] == {"type": "high"}
    assert "enable_thinking" not in (kw.get("extra_body") or {})


def test_get_llm_deepseek_without_extra_keeps_thinking_disabled(monkeypatch):
    """回归锁定：无 extra_params 的 deepseek provider 仍保留 thinking disabled
    （commit 7008c07 语义不被本任务破坏）。"""
    LLMFactory.clear_cache()
    monkeypatch.setattr("backend.core.llm_factory.active_provider", lambda kind: {
        "kind": "chat", "api_format": "openai", "id": "p-ds", "display_name": "DS",
        "base_url": "https://api.deepseek.com/v1", "model_id": "deepseek-chat",
        "api_key": _KEY})
    try:
        llm = LLMFactory.get_llm("medical_literature")
        eb = getattr(llm, "extra_body", None)
        assert eb is not None
        assert eb.get("thinking") == {"type": "disabled"}
        assert eb.get("enable_thinking") is False
    finally:
        LLMFactory.clear_cache()


# ---- 路由层：422 / suggest 端点 / 测试端点透传 ----

def _fake_client(monkeypatch, handler):
    holder = {"calls": []}

    class _FakeClient:
        def __init__(self, **kw):
            pass

        def post(self, url, **kw):
            holder["calls"].append((url, kw))
            return handler(url, **kw)

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    monkeypatch.setattr(lc.httpx, "Client", _FakeClient)
    return holder


class _Resp:
    status_code = 200
    text = ""

    def json(self):
        return {"choices": [{"message": {"content": "pong"}}]}


def test_route_add_provider_invalid_extra_params_422(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    body = {"kind": "chat", "api_format": "openai", "base_url": _URL,
            "model_id": "glm-5", "display_name": "GLM", "api_key": _KEY,
            "extra_params": "{not-json"}
    assert c.post("/api/v1/medical/admin/llm/providers", headers=_h(adm),
                  json=body).status_code == 422


def test_route_add_provider_extra_params_persisted(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    body = {"kind": "chat", "api_format": "openai", "base_url": _URL,
            "model_id": "glm-5", "display_name": "GLM", "api_key": _KEY,
            "extra_params": '{"thinking":{"type":"high"}}'}
    r = c.post("/api/v1/medical/admin/llm/providers", headers=_h(adm), json=body)
    assert r.status_code == 200, r.text
    stored = lc.load_config()["chat"]["providers"][0]
    assert stored["extra_params"] == '{"thinking":{"type":"high"}}'


def test_route_add_provider_non_object_json_422(monkeypatch, tmp_path):
    """extra_params 解析结果必须为 JSON object（数组/标量拒绝）。"""
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    body = {"kind": "chat", "api_format": "openai", "base_url": _URL,
            "model_id": "m", "display_name": "", "api_key": _KEY,
            "extra_params": "[1,2]"}
    assert c.post("/api/v1/medical/admin/llm/providers", headers=_h(adm),
                  json=body).status_code == 422


def test_route_suggest_extra_params(monkeypatch, tmp_path):
    """suggest 端点：按厂商返回预设（admin 权限）。"""
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    doc = _login(c, "doctor01", "Med@2026")
    assert c.get("/api/v1/medical/admin/llm/suggest-extra",
                 params={"base_url": "https://open.bigmodel.cn/v4", "model_id": "glm-5"},
                 headers=_h(doc)).status_code == 403
    adm = _login(c, "admin01", "Med@2026")
    r = c.get("/api/v1/medical/admin/llm/suggest-extra",
              params={"base_url": "https://open.bigmodel.cn/api/paas/v4", "model_id": "glm-5"},
              headers=_h(adm))
    assert r.status_code == 200
    assert json.loads(r.json()["suggested"]) == {
        "thinking": {"type": "enabled"}, "reasoning_effort": "high"}
    r2 = c.get("/api/v1/medical/admin/llm/suggest-extra",
               params={"base_url": "https://api.deepseek.com/v1", "model_id": "deepseek-chat"},
               headers=_h(adm))
    assert r2.json()["suggested"] == ""


def test_route_llm_test_carries_extra_params(monkeypatch, tmp_path):
    """连通性测试透传 extra_params：payload 合并用户参数（真实验证），且思考参数
    显式给出时默认 enable_thinking=False 让位。"""
    _isolate(monkeypatch, tmp_path)
    c = TestClient(app)
    adm = _login(c, "admin01", "Med@2026")
    holder = _fake_client(monkeypatch, lambda url, **kw: _Resp())
    r = c.post("/api/v1/medical/admin/llm/test", headers=_h(adm),
               json={"kind": "chat", "api_format": "openai", "base_url": _URL,
                     "model_id": "glm-5", "display_name": "", "api_key": _KEY,
                     "extra_params": '{"thinking":{"type":"high"}}'})
    assert r.status_code == 200 and r.json()["ok"] is True
    payload = holder["calls"][0][1]["json"]
    assert payload["thinking"] == {"type": "high"}
    assert "enable_thinking" not in payload
    assert holder["calls"][0][0] == _URL + "/chat/completions"


def test_llm_config_test_provider_merges_extra_payload(monkeypatch):
    """核心层 test_provider：extra_params 覆盖默认 max_tokens（用户显式优先）。"""
    holder = _fake_client(monkeypatch, lambda url, **kw: _Resp())
    r = lc.test_provider("openai", _URL, "m1", _KEY,
                         extra_params='{"max_tokens":64}')
    assert r["ok"] is True
    payload = holder["calls"][0][1]["json"]
    assert payload["max_tokens"] == 64
