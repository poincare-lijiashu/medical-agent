"""任务3 轻量会话记忆：
- chat_memory 单元：remember/recall 往返、deque 轮数截断、turns=0 关闭、answer 500 字符截断；
- generate_node 注入：state.memory_context 拼入 prompt 头部（【前文对话】块 + 指代边界说明）；
- 端点层：/literature/ask 与 /literature/stream 携带 session_id 时两轮历史后第三问注入
  前文块 + 审计 memory_used=N；无 session_id（评估路径）不注入不报错；响应完成后写入记忆。

不触网：图/LLM 全部替身（复用 test_lit_route 的假图模式）。
"""
import asyncio

import pytest
from fastapi.testclient import TestClient

from backend.agents.medical.literature import nodes as lit_nodes
from backend.config import settings
from backend.core import chat_memory
from backend.core.auth import seed_default_users
from backend.main import app


@pytest.fixture(autouse=True)
def _clean_memory():
    """测试隔离：会话记忆是进程内存态，每个测试前后清空。"""
    chat_memory.reset()
    yield
    chat_memory.reset()


class _FakeAudit:
    """真实 AuditLog.write(event_type=..., action=..., actor=..., payload={...}) 的
    内存替身：entries 结构与真实一致（payload 为业务负载 dict）。"""

    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        # 与真实 AuditLog 归一化结构一致：action 落入 payload（payload.setdefault("action", action)）
        payload = dict(k.get("payload") or {})
        if k.get("action"):
            payload.setdefault("action", k["action"])
        self.entries.append({"ts": "2026-01-01T00:00:00",
                             "event_type": k.get("event_type", ""),
                             "actor": k.get("actor", ""),
                             "payload": payload})

    def recent(self, n=8, offset=0):
        return list(self.entries)


def _med_client(monkeypatch, audit: _FakeAudit) -> TestClient:
    from backend.api.v1.medical import medical_router as mr
    seed_default_users()
    monkeypatch.setattr(mr, "get_audit_logger", lambda: audit)
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _doctor_headers(c):
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


_ANS = {"text": "答案文本", "confidence": 0.9, "sources": ["KB:y"],
        "needs_human_review": False, "verified": True, "evidence": []}


class _CapAskGraph:
    """替身文献图：捕获 ainvoke 的初始 state（记忆注入断言用）。"""

    def __init__(self):
        self.states = []

    async def ainvoke(self, state, config=None):
        self.states.append(dict(state))
        return {"answer": dict(_ANS), "refine_trace": []}


class _CapStreamGraph:
    """替身文献图：捕获 astream 的初始 state 并按 updates 形态 yield。"""

    def __init__(self):
        self.states = []

    def astream(self, state, config=None, stream_mode=None):
        self.states.append(dict(state))

        async def _gen():
            yield {"verify": {"answer": dict(_ANS)}}
        return _gen()


# ---- chat_memory 单元 ----

def test_remember_recall_roundtrip():
    chat_memory.remember("s1", "胃疼怎么办", "建议就医评估")
    chat_memory.remember("s1", "阿司匹林作用", "解热镇痛抗血小板")
    hist = chat_memory.recall("s1")
    assert hist == [("胃疼怎么办", "建议就医评估"), ("阿司匹林作用", "解热镇痛抗血小板")]


def test_deque_truncates_to_configured_turns(monkeypatch):
    """轮数上限：turns=2 → 写入 3 轮后只保留最近 2 轮（deque 截断）。"""
    monkeypatch.setattr(settings, "chat_memory_turns", 2)
    for i in range(3):
        chat_memory.remember("s1", f"q{i}", f"a{i}")
    hist = chat_memory.recall("s1")
    assert hist == [("q1", "a1"), ("q2", "a2")]


def test_turns_zero_disables_memory(monkeypatch):
    """turns=0（关闭）：不写入、build_context 返回空。"""
    monkeypatch.setattr(settings, "chat_memory_turns", 0)
    chat_memory.remember("s1", "q", "a")
    assert chat_memory.recall("s1") == []
    ctx, used = chat_memory.build_context("s1")
    assert ctx == "" and used == 0


def test_answer_truncated_to_500_chars():
    chat_memory.remember("s1", "q", "x" * 900)
    assert chat_memory.recall("s1")[0][1] == "x" * 500
    assert chat_memory.recall("s1")[0][0] == "q"  # 问题原文不截断


def test_max_sessions_default_is_200():
    """外部审查 M2：会话数上限默认 200（无上限的进程内存 dict 会被长会话/刷会话撑爆）。"""
    assert chat_memory.MAX_SESSIONS == 200


def test_max_sessions_evicts_oldest(monkeypatch):
    """超限淘汰：超过 MAX_SESSIONS 后按最久未写入淘汰最旧会话，新会话保留。"""
    monkeypatch.setattr(chat_memory, "MAX_SESSIONS", 3)
    for i in range(4):
        chat_memory.remember(f"s{i}", f"q{i}", f"a{i}")
    assert chat_memory.recall("s0") == []  # 最旧会话被淘汰
    assert chat_memory.recall("s1") == [("q1", "a1")]
    assert chat_memory.recall("s3") == [("q3", "a3")]


def test_max_sessions_201_default_evicts_oldest():
    """外部审查 M2（默认上限实量验证）：不 patch 上限，创建 201 个会话 →
    最旧（s000）被淘汰、进程内总会话数 ≤200、最新会话完整保留。"""
    for i in range(201):
        chat_memory.remember(f"s{i:03d}", "q", "a")
    assert len(chat_memory._store) <= 200, "总会话数不得超过 MAX_SESSIONS"
    assert chat_memory.recall("s000") == [], "最旧会话应被淘汰"
    assert chat_memory.recall("s200") == [("q", "a")], "最新会话应完整保留"


def test_max_sessions_keeps_active_session(monkeypatch):
    """活跃会话不受影响：最旧会话持续写入（每次 move_to_end）时不会被淘汰。"""
    monkeypatch.setattr(chat_memory, "MAX_SESSIONS", 3)
    chat_memory.remember("old", "q0", "a0")
    for i in range(1, 4):
        chat_memory.remember(f"s{i}", f"q{i}", f"a{i}")
        chat_memory.remember("old", f"qa{i}", f"ab{i}")  # old 一直活跃 → 永不淘汰
    assert chat_memory.recall("old")[-1] == ("qa3", "ab3")
    assert chat_memory.recall("s1") == []  # 被淘汰的是不再活跃的 s1


def test_build_context_format_marks_scope():
    """前文块格式：标注仅用于理解指代、回答仍需独立检索与引用。"""
    chat_memory.remember("s1", "胃疼怎么办", "建议就医评估")
    ctx, used = chat_memory.build_context("s1")
    assert used == 1
    assert "【前文对话】" in ctx
    assert "仅用于理解指代" in ctx and "独立检索与引用" in ctx
    assert "用户：胃疼怎么办" in ctx and "助手：建议就医评估" in ctx


def test_build_context_limits_to_recent_turns(monkeypatch):
    monkeypatch.setattr(settings, "chat_memory_turns", 2)
    for i in range(4):
        chat_memory.remember("s1", f"q{i}", f"a{i}")
    ctx, used = chat_memory.build_context("s1")
    assert used == 2
    assert "q0" not in ctx and "q1" not in ctx
    assert "q2" in ctx and "q3" in ctx


# ---- generate_node 注入 ----

class _FakeLLM:
    def __init__(self, capture: list):
        self._capture = capture

    async def ainvoke(self, messages):
        self._capture.append(messages[0].content)

        class _R:
            content = '{"answer": "答案", "citations": []}'
        return _R()


def test_generate_node_prepends_memory_context(monkeypatch):
    """state.memory_context 非空 → 拼入 prompt 头部（前文块在 system 之前）。"""
    capture: list[str] = []
    monkeypatch.setattr(lit_nodes, "get_llm", lambda route, temperature=0: _FakeLLM(capture))
    out = asyncio.run(lit_nodes.generate_node({
        "question": "那它对孕妇安全吗", "evidence": [], "pubmed_ids": [],
        "memory_context": "【前文对话】\n用户：阿司匹林作用\n助手：解热镇痛\n"}))
    assert out["draft"]["answer"] == "答案"
    assert capture[0].startswith("【前文对话】"), "记忆块必须位于 prompt 头部"
    assert "用户：阿司匹林作用" in capture[0]


def test_generate_node_without_memory_keeps_original_prompt(monkeypatch):
    """无记忆（评估路径/首问）→ prompt 与旧版完全一致（不追加任何前文块）。"""
    capture: list[str] = []
    monkeypatch.setattr(lit_nodes, "get_llm", lambda route, temperature=0: _FakeLLM(capture))
    asyncio.run(lit_nodes.generate_node({"question": "胃疼怎么办", "evidence": [], "pubmed_ids": []}))
    assert not capture[0].startswith("【前文对话】")


# ---- 端点层：ask / stream 注入 + 审计 + 写入时机 ----

def test_ask_injects_history_on_third_question(monkeypatch):
    """2 轮历史后第三问：图收到含前文块的 memory_context；审计 memory_used=2；
    响应完成后本轮问答写入记忆。"""
    from backend.api.v1.medical import medical_router as mr
    graph = _CapAskGraph()
    monkeypatch.setattr(mr, "_lit_graph", graph)
    audit = _FakeAudit()
    c = _med_client(monkeypatch, audit)
    H = _doctor_headers(c)
    sid = "sess-abc"
    r1 = c.post("/api/v1/medical/literature/ask", json={"question": "胃疼怎么办", "session_id": sid},
                headers=H)
    assert r1.status_code == 200, r1.text
    assert graph.states[0]["memory_context"] == ""  # 首问无历史
    r2 = c.post("/api/v1/medical/literature/ask", json={"question": "阿司匹林作用", "session_id": sid},
                headers=H)
    assert r2.status_code == 200, r2.text
    r3 = c.post("/api/v1/medical/literature/ask",
                json={"question": "那它对孕妇安全吗", "session_id": sid}, headers=H)
    assert r3.status_code == 200, r3.text
    ctx = graph.states[2]["memory_context"]
    assert "【前文对话】" in ctx
    assert "用户：胃疼怎么办" in ctx and "用户：阿司匹林作用" in ctx
    assert "助手：答案文本" in ctx
    ev = [e for e in audit.entries if e["payload"].get("action") == "query"]
    assert ev[-1]["payload"]["memory_used"] == 2, "第三问应审计 memory_used=2"


def test_ask_without_session_id_no_memory_no_error(monkeypatch):
    """无 session_id（评估脚本/旧客户端）：不注入、审计 memory_used=0、响应正常。"""
    from backend.api.v1.medical import medical_router as mr
    graph = _CapAskGraph()
    monkeypatch.setattr(mr, "_lit_graph", graph)
    audit = _FakeAudit()
    c = _med_client(monkeypatch, audit)
    r = c.post("/api/v1/medical/literature/ask", json={"question": "胃疼怎么办"},
               headers=_doctor_headers(c))
    assert r.status_code == 200, r.text
    assert graph.states[0]["memory_context"] == ""
    ev = [e for e in audit.entries if e["payload"].get("action") == "query"]
    assert ev[-1]["payload"]["memory_used"] == 0
    assert chat_memory.recall("") == []  # 未写入任何记忆


def test_ask_writes_memory_after_response_for_fallback_too(monkeypatch):
    """fallback 兜底答案同样写入记忆（真实问答语义，追问需指代上下文）。"""
    from backend.api.v1.medical import medical_router as mr

    class _FbGraph:
        async def ainvoke(self, state, config=None):
            return {"answer": {"text": "常识兜底内容", "confidence": 0.4,
                               "sources": ["fallback:model-prior"],
                               "needs_human_review": True, "verified": False, "evidence": []},
                    "refine_trace": []}

    monkeypatch.setattr(mr, "_lit_graph", _FbGraph())
    c = _med_client(monkeypatch, _FakeAudit())
    r = c.post("/api/v1/medical/literature/ask",
               json={"question": "胃疼怎么办", "session_id": "s-fb"},
               headers=_doctor_headers(c))
    assert r.status_code == 200, r.text
    assert chat_memory.recall("s-fb") == [("胃疼怎么办", "常识兜底内容")]


def test_stream_injects_same_memory_context(monkeypatch):
    """流式路径与非流式注入同一 memory_context（SSE 语义一致）+ 审计 memory_used。"""
    from backend.api.v1.medical import medical_router as mr
    graph = _CapStreamGraph()
    monkeypatch.setattr(mr, "_lit_graph", graph)
    audit = _FakeAudit()
    c = _med_client(monkeypatch, audit)
    H = _doctor_headers(c)
    sid = "sess-sse"
    r1 = c.post("/api/v1/medical/literature/stream",
                json={"question": "胃疼怎么办", "session_id": sid}, headers=H)
    assert r1.status_code == 200, r1.text
    assert "event: result" in r1.text
    assert graph.states[0]["memory_context"] == ""
    r2 = c.post("/api/v1/medical/literature/stream",
                json={"question": "那它对孕妇安全吗", "session_id": sid}, headers=H)
    assert r2.status_code == 200, r2.text
    ctx = graph.states[1]["memory_context"]
    assert "【前文对话】" in ctx and "用户：胃疼怎么办" in ctx and "助手：答案文本" in ctx
    ev = [e for e in audit.entries if e["payload"].get("action") == "stream"]
    assert ev[-1]["payload"]["memory_used"] == 1  # 第二问使用 1 轮历史
    # 两问响应完成后都写入（每问一轮）：第一问无历史注入但仍入存，供后续追问指代
    assert chat_memory.recall("sess-sse") == [("胃疼怎么办", "答案文本"),
                                              ("那它对孕妇安全吗", "答案文本")]
