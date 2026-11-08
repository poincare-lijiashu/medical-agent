"""第 74 批：清理 `_AGENT_MODEL_ROUTING` 伪路由表，锁住"白名单 + 缓存粒度"两件事。

历史：该表把 agent_type 映射到模型名，但所有值相同、且 `_build_model_kwargs` 从未使用；
改任何一行都不会改变实际模型（真实模型来自管理端激活 provider 或 QWEN_MODEL_CHAT）。
清理后只保留 `_AGENT_TYPES` 白名单，缓存键改为显式命名空间 `chat`——**缓存粒度必须
仍是 (温度, 流式)**，不能按 agent_type 拆（否则同一模型被创建多份）。
"""
import inspect

import pytest

from backend.core import llm_factory as lf


@pytest.fixture(autouse=True)
def _clean_cache():
    lf.LLMFactory.clear_cache()
    yield
    lf.LLMFactory.clear_cache()


def test_dead_routing_table_removed():
    """伪路由表不得复活；白名单必须包含医疗域实际在用类型。"""
    assert not hasattr(lf, "_AGENT_MODEL_ROUTING"), "伪路由表已删除，不应再出现"
    for t in ("medical_literature", "medical_case", "medical_drug", "medical_imaging"):
        assert t in lf._AGENT_TYPES


def test_unknown_agent_type_lists_allowed(monkeypatch):
    """未知类型报错信息必须给出可用类型（排障用），且不创建任何模型实例。"""
    called = []
    monkeypatch.setattr(lf, "init_chat_model", lambda **k: called.append(k) or object())
    with pytest.raises(ValueError) as e:
        lf.LLMFactory.get_llm("no-such-agent")
    assert "medical_literature" in str(e.value)
    assert not called


def _fake_factory(monkeypatch):
    """不触真实网络：返回固定 kwargs，init_chat_model 每次调用产出新对象。"""
    monkeypatch.setattr(lf.LLMFactory, "_build_model_kwargs",
                        classmethod(lambda cls: {"model": "fake", "temperature": 0}))
    made = []

    def fake_init(**kwargs):
        obj = object()
        made.append(obj)
        return obj

    monkeypatch.setattr(lf, "init_chat_model", fake_init)
    return made


def test_cache_granularity_is_temperature_and_streaming(monkeypatch):
    """不同 agent_type 共用同一实例（同一模型）；温度/流式不同才新建——与清理前一致。"""
    made = _fake_factory(monkeypatch)
    a = lf.LLMFactory.get_llm("qa")
    b = lf.LLMFactory.get_llm("medical_literature")
    assert a is b, "同一底层模型跨 agent_type 必须共享实例（缓存粒度不变）"
    assert len(made) == 1
    lf.LLMFactory.get_llm("medical_literature", temperature=0.3)
    assert len(made) == 2
    lf.LLMFactory.get_llm("qa", temperature=0.3, streaming=True)
    assert len(made) == 3
    assert lf.LLMFactory.get_llm("medical_case", temperature=0.3) is \
        lf.LLMFactory.get_llm("qa", temperature=0.3)


def test_build_model_kwargs_takes_no_model_key():
    """`_build_model_kwargs` 不再接受 model_key（原先这个参数完全未被使用）。"""
    # classmethod 经类访问时 cls 已绑定，签名里只剩业务参数（应为空）
    params = inspect.signature(lf.LLMFactory._build_model_kwargs).parameters
    assert list(params) == [], f"不应再有 model_key 参数：{list(params)}"
