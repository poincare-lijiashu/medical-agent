"""G91 有界工具循环：边界、白名单、失败回传、审计与 API。"""
from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from backend.agents import bot_runtime, bots, tool_loop
from backend.core import agent_tools
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _bot(tools=("kb_search",), agent="literature") -> dict:
    return bots.create_bot({
        "code": "loop-bot",
        "name": "工具循环 Bot",
        "agentId": agent,
        "persona": "你是循证医学助手，回答必须标注证据来源与不确定性，不做诊断结论。",
        "modelProvider": "default",
        "modelId": None,
        "skillCodes": [],
        "toolAllowlist": list(tools),
        "memoryScope": "bot",
    }, "admin01")


class FakeLLM:
    def __init__(self, replies):
        self.replies = list(replies)
        self.bound = None
        self.calls = 0

    def bind_tools(self, tools):
        self.bound = tools
        return self

    async def ainvoke(self, messages):
        self.calls += 1
        return self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]


def _call(name="kb_search", args=None, cid="c1") -> AIMessage:
    return AIMessage(content="", tool_calls=[{
        "name": name, "args": args or {"query": "高血压"}, "id": cid,
    }])


@pytest.fixture(autouse=True)
def _local_provider(monkeypatch):
    monkeypatch.setattr(bot_runtime.llm_config, "active_provider",
                        lambda kind: {"id": "local", "api_format": "openai",
                                      "base_url": "http://127.0.0.1:8000/v1",
                                      "model_id": "m", "api_key": "k"})


@pytest.fixture
def executed(monkeypatch):
    calls = []

    async def fake_execute(name, args, role):
        calls.append({"name": name, "args": args, "role": role})
        return {"tool": name, "result": {"chunks": []}}

    monkeypatch.setattr(tool_loop.agent_tools, "execute_tool", fake_execute)
    return calls


def test_allowed_tool_then_final(executed):
    bot = _bot()
    llm = FakeLLM([_call(), AIMessage(content="最终答案")])
    result = asyncio.run(tool_loop.run("高血压如何分级", bot, "doctor",
                                       llm_factory=lambda p: llm))
    assert result["answer"] == "最终答案"
    assert result["toolCalls"] == 1 and result["truncated"] is False
    assert [s["type"] for s in result["steps"]] == ["tool", "final"]
    assert result["steps"][0]["status"] == "OK"
    assert executed[0]["name"] == "kb_search"
    assert [t["function"]["name"] for t in llm.bound] == ["kb_search"]


def test_disallowed_tool_is_blocked_not_executed(executed):
    bot = _bot(tools=("kb_search",))
    llm = FakeLLM([_call(name="drug_check"), AIMessage(content="纠正后答案")])
    result = asyncio.run(tool_loop.run("问题", bot, "doctor", llm_factory=lambda p: llm))
    assert result["steps"][0]["status"] == "BLOCKED"
    assert executed == []
    assert result["answer"] == "纠正后答案"


def test_disabled_tool_not_bound_and_blocked(executed):
    bot = _bot(tools=("web_search",))
    llm = FakeLLM([_call(name="web_search"), AIMessage(content="无联网答案")])
    result = asyncio.run(tool_loop.run("问题", bot, "doctor", llm_factory=lambda p: llm))
    assert llm.bound is None                      # 开关关闭 → 不暴露给模型
    assert result["steps"][0]["status"] == "BLOCKED"
    assert executed == []


def test_max_tool_calls_truncates(executed):
    bot = _bot()
    llm = FakeLLM([
        AIMessage(content="", tool_calls=[
            {"name": "kb_search", "args": {"query": "a"}, "id": "c1"},
            {"name": "kb_search", "args": {"query": "b"}, "id": "c2"},
        ]),
        AIMessage(content="", tool_calls=[
            {"name": "kb_search", "args": {"query": "c"}, "id": "c3"},
            {"name": "kb_search", "args": {"query": "d"}, "id": "c4"},
        ]),
    ])
    result = asyncio.run(tool_loop.run("问题", bot, "doctor", llm_factory=lambda p: llm))
    assert result["truncated"] is True
    assert result["answer"] == ""
    assert result["toolCalls"] == tool_loop.MAX_TOOL_CALLS
    assert len(executed) == tool_loop.MAX_TOOL_CALLS


def test_tool_error_is_returned_to_model_then_final(monkeypatch):
    async def failing(name, args, role):
        raise agent_tools.ToolUnavailable("知识库暂不可用")

    monkeypatch.setattr(tool_loop.agent_tools, "execute_tool", failing)
    bot = _bot()
    llm = FakeLLM([_call(), AIMessage(content="降级后的答案")])
    result = asyncio.run(tool_loop.run("问题", bot, "doctor", llm_factory=lambda p: llm))
    assert result["steps"][0]["status"] == "ERROR"
    assert result["answer"] == "降级后的答案"


def test_foreign_provider_rejected(monkeypatch):
    monkeypatch.setattr(bot_runtime.llm_config, "active_provider",
                        lambda kind: {"id": "openai", "api_format": "openai",
                                      "base_url": "https://api.openai.com/v1",
                                      "model_id": "m", "api_key": "k"})
    bot = _bot()
    with pytest.raises(bot_runtime.BotUnavailable):
        asyncio.run(tool_loop.run("问题", bot, "doctor",
                                  llm_factory=lambda p: FakeLLM([AIMessage(content="x")])))


def test_role_scope_enforced():
    bot = _bot(agent="imaging", tools=())
    with pytest.raises(tool_loop.ToolLoopUnavailable):
        asyncio.run(tool_loop.run("问题", bot, "pharmacist",
                                  llm_factory=lambda p: FakeLLM([AIMessage(content="x")])))


def test_permission_error_is_forbidden_typed():
    """评审 01-S15：权限不足用 ToolLoopForbidden 表达，便于 API 层映射 403。"""
    bot = _bot(agent="imaging", tools=())
    with pytest.raises(tool_loop.ToolLoopForbidden):
        asyncio.run(tool_loop.run("问题", bot, "pharmacist",
                                  llm_factory=lambda p: FakeLLM([AIMessage(content="x")])))


def test_short_question_is_invalid_typed():
    """评审 01-S15 同批：入参不合法用 ToolLoopInvalid 表达，便于映射 422。"""
    bot = _bot()
    with pytest.raises(tool_loop.ToolLoopInvalid):
        asyncio.run(tool_loop.run("短", bot, "doctor",
                                  llm_factory=lambda p: FakeLLM([AIMessage(content="x")])))


def test_per_step_audit(executed):
    bot = _bot()
    llm = FakeLLM([_call(), AIMessage(content="答案")])
    events = []

    async def audit(ev):
        events.append(ev)

    asyncio.run(tool_loop.run("问题", bot, "doctor", llm_factory=lambda p: llm,
                              audit=audit))
    assert [e["type"] for e in events] == ["tool", "final"]
    assert events[0]["tool"] == "kb_search" and events[0]["status"] == "OK"


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_api_tool_loop_error_mapping(monkeypatch, tmp_path):
    """评审 01-S15：服务层权限异常映射 403、入参异常映射 422（不再一律 503）。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    bot = _bot()
    b = bots.submit_bot("loop-bot", bot["rowVersion"], "admin01")
    bots.review_bot("loop-bot", True, None, b["rowVersion"], "qc01")

    async def deny(*a, **k):
        raise tool_loop.ToolLoopForbidden("当前角色无权使用该 Bot")

    monkeypatch.setattr(tool_loop, "run", deny)
    r = c.post("/api/v1/agent/bots/loop-bot/tool-loop", headers=doc,
               json={"question": "高血压如何分级"})
    assert r.status_code == 403, r.text

    async def invalid(*a, **k):
        raise tool_loop.ToolLoopInvalid("问题需 2-2000 字")

    monkeypatch.setattr(tool_loop, "run", invalid)
    r = c.post("/api/v1/agent/bots/loop-bot/tool-loop", headers=doc,
               json={"question": "高血压如何分级"})
    assert r.status_code == 422, r.text


def test_api_tool_loop_permissions_and_success(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    adm = _h(_login(c, "admin01"))
    bot = _bot()

    # 草稿 Bot：普通角色不可见
    assert c.post("/api/v1/agent/bots/loop-bot/tool-loop", headers=doc,
                  json={"question": "问题"}).status_code == 404

    # 发布
    b = bots.submit_bot("loop-bot", bot["rowVersion"], "admin01")
    bots.review_bot("loop-bot", True, None, b["rowVersion"], "qc01")

    async def fake_run(question, bot_, role, audit=None, llm_factory=None):
        return {"botCode": bot_["code"], "question": question, "answer": "答案",
                "steps": [{"step": 1, "type": "final", "status": "OK"}],
                "toolCalls": 0, "truncated": False, "elapsedMs": 1,
                "needsHumanReview": True, "provider": {"id": "local"}}

    monkeypatch.setattr(tool_loop, "run", fake_run)
    r = c.post("/api/v1/agent/bots/loop-bot/tool-loop", headers=doc,
               json={"question": "高血压如何分级"})
    assert r.status_code == 200, r.text
    assert r.json()["answer"] == "答案"
    actions = {(e.get("payload") or {}).get("action") for e in audit.entries}
    assert "tool_loop" in actions
