"""评测 04 P0-3：医疗主链路的模型出境策略（fail-closed）回归锁。

背景：`model_policy` 此前只接在 agent 域，`core/llm_factory` 的 chat/vision 构建完全
没有校验——管理员配置一个境外 provider 后，处方建议/质控/文献/影像/MDT 会把病例
原文与影像发往境外。本文件锁定：
1. chat/vision provider 构建前必须过策略，境外/未知直接抛 `ModelPolicyViolation`；
2. 境内/本地 provider 照常构建；
3. 策略符号已下沉 `backend.core.model_policy`，agents 侧导入面保持兼容；
4. 应用层把该异常映射为 503（不 500、不改道）。
"""
import pytest

from backend.core import llm_factory as lf
from backend.core.llm_factory import LLMFactory
from backend.core.model_policy import ModelPolicyViolation, classify


def _p(pid: str, url: str, fmt: str = "openai") -> dict:
    return {"kind": "chat", "api_format": fmt, "id": pid, "display_name": pid,
            "base_url": url, "model_id": "m", "api_key": "k"}


FOREIGN = _p("p-f", "https://api.openai.com/v1")
UNKNOWN = _p("p-u", "https://llm.example.com/v1")
DOMESTIC = _p("p-d", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LOCAL = _p("p-l", "http://127.0.0.1:8000/v1")


def test_classify_matrix():
    assert classify(LOCAL) == "local"
    assert classify(DOMESTIC) == "domestic"
    assert classify(FOREIGN) == "foreign"
    assert classify(UNKNOWN) == "unknown"


def test_chat_factory_blocks_foreign_and_unknown(monkeypatch):
    """境外/未知 provider：必须在建模前抛错（模型未创建 → 一个字节都不会发出）。"""
    for provider in (FOREIGN, UNKNOWN):
        monkeypatch.setattr(lf, "active_provider", lambda kind, p=provider: p)
        LLMFactory.clear_cache()
        with pytest.raises(ModelPolicyViolation):
            LLMFactory._build_model_kwargs()


def test_vision_factory_blocks_foreign(monkeypatch):
    monkeypatch.setattr(lf, "active_provider", lambda kind: FOREIGN)
    LLMFactory.clear_cache()
    try:
        with pytest.raises(ModelPolicyViolation):
            LLMFactory.get_vl_llm()
    finally:
        LLMFactory.clear_cache()


def test_chat_factory_allows_domestic(monkeypatch):
    monkeypatch.setattr(lf, "active_provider", lambda kind: DOMESTIC)
    LLMFactory.clear_cache()
    kw = LLMFactory._build_model_kwargs()
    assert kw["base_url"] == DOMESTIC["base_url"]


def test_agents_module_reexports_policy():
    """agents 侧旧导入面必须继续可用（既有 6 个调用点零改动）。"""
    from backend.agents import model_policy as ap

    assert ap.is_allowed(DOMESTIC) and not ap.is_allowed(FOREIGN)
    assert ap.ModelPolicyViolation is ModelPolicyViolation
    with pytest.raises(ModelPolicyViolation):
        ap.assert_allowed(FOREIGN)


def test_app_maps_violation_to_503():
    from backend.main import app

    handlers = getattr(app, "exception_handlers", {}) or {}
    assert ModelPolicyViolation in handlers
