"""F3（GLM thinking 兼容降级）：structured_with_fallback 三态验证。

背景：GLM 5.x 强制思考模型对 with_structured_output(function_calling) 恒 400
'Thinking mode does not support this tool_choice'，原样重试也 400（日志实锤
mdt.pick_departments_llm_failed ×38）。降级语义：
1. 首选 function_calling 成功 → 不触碰降级路径；
2. 400 + Thinking mode/tool_choice 互斥错误 → json_mode 重试一次成功；
3. json_mode 也失败 / 错误非互斥类 → 抛**原错**（调用方异常语义零变化）。

与既有 mock 形态兼容（get_structured_llm 返回「直接 ainvoke 的对象」）：
base_llm_fn 仅在真互斥 400 时才被调用，主路径 mock 零额外行为。
"""
from __future__ import annotations

import asyncio

import pytest
from pydantic import BaseModel

from backend.core import llm_factory as lf


class Team(BaseModel):
    departments: list[str]
    reasoning: str = ""


_THINKING_400 = RuntimeError(
    "Error code: 400 - {'error': {'message': 'Thinking mode does not support this tool_choice'}}")
_NET_500 = RuntimeError("Error code: 500 - upstream unavailable")


class _Chain:
    """假结构化链：ainvoke 按 script 顺序抛错/返回。"""

    def __init__(self, script):
        self._script = list(script)
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(list(messages))
        step = self._script.pop(0) if self._script else Exception("no script")
        if isinstance(step, Exception):
            raise step
        return step


class _BaseLLM:
    """假原始模型：记录 with_structured_output 收到的 method，返回绑定链。"""

    def __init__(self, json_chain):
        self._json_chain = json_chain
        self.methods = []

    def with_structured_output(self, schema, method=None):
        self.methods.append((schema, method))
        assert schema is Team
        return self._json_chain


def test_fallback_primary_success_never_downgrades():
    """首选 function_calling 成功 → 不调 base_llm_fn（降级路径零触碰）。"""
    ok = Team(departments=["口腔科"], reasoning="r")
    chain = _Chain([ok])
    touched = {"n": 0}

    def _base():
        touched["n"] += 1
        raise AssertionError("主路径成功不得触发降级")

    out = asyncio.run(lf.structured_with_fallback(chain, Team, "p", base_llm_fn=_base))
    assert out is ok
    assert touched["n"] == 0
    assert len(chain.calls) == 1 and chain.calls[0][0].content == "p"  # 文本 prompt → HumanMessage


def test_fallback_thinking_400_downgrades_to_json_mode_and_succeeds():
    """400 'Thinking mode does not support this tool_choice' → json_mode 重试一次成功，
    且降级消息尾部追加 JSON Schema 说明（json_mode 不自动注入 schema）。"""
    ok = Team(departments=["内科"], reasoning="json")
    primary = _Chain([_THINKING_400])
    json_chain = _Chain([ok])
    base = _BaseLLM(json_chain)
    out = asyncio.run(lf.structured_with_fallback(primary, Team, "组队", base_llm_fn=lambda: base))
    assert out is ok
    assert base.methods == [(Team, "json_mode")]  # 降级绑定 method=json_mode
    # 降级消息 = 原消息 + schema 提示（含字段名，供模型按 schema 输出）
    assert len(json_chain.calls[0]) == 2
    assert "JSON Schema" in json_chain.calls[0][-1].content
    assert "departments" in json_chain.calls[0][-1].content


def test_fallback_json_mode_also_fails_raises_original_error():
    """json_mode 重试仍失败 → 抛**原 400 错**（不是降级错误）。"""
    primary = _Chain([_THINKING_400])
    json_chain = _Chain([_NET_500])
    base = _BaseLLM(json_chain)
    with pytest.raises(RuntimeError, match="Thinking mode"):
        asyncio.run(lf.structured_with_fallback(primary, Team, "p", base_llm_fn=lambda: base))


def test_fallback_non_thinking_error_raises_through():
    """非互斥类错误（如 500）→ 不降级直接原样抛（调用方既有语义零变化）。"""
    chain = _Chain([_NET_500])
    touched = {"n": 0}

    def _base():
        touched["n"] += 1

    with pytest.raises(RuntimeError, match="500"):
        asyncio.run(lf.structured_with_fallback(chain, Team, "p", base_llm_fn=_base))
    assert touched["n"] == 0


def test_fallback_multimodal_messages_pass_through_and_hint_appended():
    """VL 多模态路径：messages 列表原样透传，降级时尾部追加 schema 提示（不改动图文段）。"""
    ok = Team(departments=["口腔科"], reasoning="vl")
    mm = [{"type": "image_url", "image_url": {"url": "data:image/png;base64,AAA"}},
          {"type": "text", "text": "结合影像组队"}]
    primary = _Chain([_THINKING_400])
    json_chain = _Chain([ok])
    base = _BaseLLM(json_chain)
    out = asyncio.run(lf.structured_with_fallback(
        primary, Team, None, base_llm_fn=lambda: base, messages=[{"content": mm}]))
    assert out is ok
    # 主路径消息原样（不包 HumanMessage）
    assert primary.calls[0][0]["content"] == mm
    # 降级消息 = 原消息 + 文本提示
    assert len(json_chain.calls[0]) == 2


def test_thinking_400_detector_scope():
    """识别器精确性：400+标记 → True；缺标记或非 400 → False。"""
    assert lf._is_thinking_tool_choice_400(_THINKING_400)
    assert lf._is_thinking_tool_choice_400(RuntimeError("400 Bad Request: tool_choice invalid"))
    assert not lf._is_thinking_tool_choice_400(_NET_500)
    assert not lf._is_thinking_tool_choice_400(RuntimeError("400 - quota exceeded"))
