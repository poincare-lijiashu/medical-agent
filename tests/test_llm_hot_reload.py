"""评测行动项 3：LLM key 热更新失效测试。

此前缓存失效钩子只在 admin 路由层（medical_router._reset_llm_cache）——绕过路由
直接调用 llm_config 更新函数（脚本/内部调用）不会清 LLMFactory._instances，
旧 api_key 的客户端被复用 → key 热更新对已构造客户端不生效。本测试锁定：
llm_config 库层更新函数（add/activate/remove/deactivate）尾部必须失效缓存，
下一次 get_llm 必须按新 key 重新构造客户端。

全程不触网：init_chat_model 打桩为记录构造参数的假函数（只构造、绝不调用）；
配置文件 monkeypatch 到 tmp_path。
"""
from types import SimpleNamespace

import pytest

from backend.core import llm_config as lc
from backend.core.llm_factory import LLMFactory

_URL = "https://api.example.com/v1"
_KEY1 = "sk-key-000000000001"
_KEY2 = "sk-key-000000000002"


@pytest.fixture()
def fake_init(monkeypatch):
    """init_chat_model 打桩：记录每次构造 kwargs，返回占位对象（绝不触网）。"""
    calls: list[dict] = []

    def _fake(**kwargs):
        calls.append(dict(kwargs))
        return SimpleNamespace(_fake="llm")

    monkeypatch.setattr("backend.core.llm_factory.init_chat_model", _fake)
    LLMFactory.clear_cache()
    yield calls
    LLMFactory.clear_cache()


@pytest.fixture()
def isolated_config(monkeypatch, tmp_path):
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(tmp_path / "llm_providers.json"))


def test_key_hot_reload_two_updates_in_a_row(fake_init, isolated_config):
    """连续两次更新（激活 key1 → 切换到 key2），每次 get_llm 都必须用最新 key 构造。"""
    # 第一次配置：add + activate → get_llm 携带 key1
    pid1 = lc.add_provider("chat", "openai", _URL, "gpt-test", "模型1", _KEY1)
    lc.activate("chat", pid1)
    llm1 = LLMFactory.get_llm("qa")
    assert fake_init[-1]["openai_api_key"] == _KEY1  # ChatOpenAI 构造参数（openai 格式字段名）
    assert getattr(llm1, "_fake", None) == "llm"      # 打桩占位对象，证明未触网

    # 第二次更新：新 provider 换 key + 激活 → 缓存已失效 → 同缓存键重新构造且带 key2
    pid2 = lc.add_provider("chat", "openai", _URL, "gpt-test2", "模型2", _KEY2)
    lc.activate("chat", pid2)
    llm2 = LLMFactory.get_llm("qa")
    assert llm2 is not llm1, "key 更新后必须重建客户端（不得复用旧实例）"
    assert fake_init[-1]["openai_api_key"] == _KEY2
    assert len(fake_init) == 2  # 恰好两次构造：每次更新各一次


def test_remove_and_deactivate_invalidate_cache(fake_init, isolated_config):
    """remove（含激活项被删）与 deactivate 也必须失效缓存。"""
    pid = lc.add_provider("chat", "openai", _URL, "gpt-test", "模型", _KEY1)
    lc.activate("chat", pid)
    LLMFactory.get_llm("qa")
    assert len(fake_init) == 1

    # remove 激活项 → 缓存失效 → get_llm 重新构造（active 已空，回落 .env 内置配置；
    # 回落参数本身由 test_llm_config.py 覆盖，此处只断言「重新构造」发生）
    assert lc.remove_provider("chat", pid) is True
    LLMFactory.get_llm("qa")
    assert len(fake_init) == 2

    # deactivate：active 本就为空，失效钩子仍幂等触发，再次构造重建
    lc.deactivate("chat")
    LLMFactory.get_llm("qa")
    assert len(fake_init) == 3


def test_add_provider_without_activation_also_invalidates(fake_init, isolated_config):
    """add（未激活）也失效缓存：新增配置后旧实例一律不可信，统一重建语义。"""
    LLMFactory.get_llm("qa")  # 先构造一次（回落 .env 内置配置）
    assert len(fake_init) == 1
    lc.add_provider("chat", "openai", _URL, "gpt-test", "模型", _KEY1)
    LLMFactory.get_llm("qa")
    assert len(fake_init) == 2, "add 后缓存必须已失效（重新构造）"
