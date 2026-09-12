"""L1 agentic 检索循环（串行回边）测试：全程 mock，不触真实 LLM/Milvus/审计文件。

六路径覆盖：
1. grade 零证据 + LLM 决策 rewrite（新查询）→ 回 retrieve → 二次 grade 有证据 → generate，attempts==1
2. 检索分数分布（语料缺失信号）注入 LLM 输入 → 决策 giveup → fallback，attempts==1
3. 轮数上限 MAX_REFINE=2：连续两次 rewrite 后第三轮 grade 仍零证据 → 直接 fallback（不再 refine），attempts==2
4. LLM 决策 new_query 与 queries_tried 重复 → 视为 giveup（不再浪费检索轮次）
5. new_query 超长（>200）截断到 200；空/纯空白 → 视为 giveup
6. LLM 结构化输出异常 → fail-safe 视为 giveup（异常绝不冒泡）
外加：LITERATURE_AGENTIC=false 走旧路径（无 refine 节点，等价旧 test_lit_route 语义）。

分数分流新语义（route_after_grade 不再看 sufficient）：
- sufficient=True 但低分 → refine（grade 的 sufficient 是「写作充分性」宽松判定，
  LLM 几乎总判 True，不能作为 refine 闸门——否则分数分流在真实流量中是死代码）；
- sufficient=True 且高分 → generate 零 refine（防回退「胃疼查不到」，断言绝不删除）；
- 零文档轮数尽 → fallback（保持）。
"""
import asyncio

import pytest
from langchain_core.messages import AIMessage

from backend.config import settings
from backend.agents.medical.literature import nodes as lit_nodes
from backend.agents.medical.literature.graph import build_literature_graph
from backend.agents.medical.literature.nodes import (
    MAX_REFINE,
    AgenticDecision,
    refine_node,
    route_after_grade,
    route_after_refine,
)
from backend.agents.medical.literature.state import LiteratureState

QUESTION = "二甲双胍是一线治疗吗"
REWRITE_Q = "二甲双胍 2型糖尿病 一线治疗"
EVIDENCE = [{"content": "二甲双胍是2型糖尿病一线降糖药", "source": "KB:demo",
             "score": 0.8, "rerank": 2.0}]
LOW_SCORE_EVIDENCE = [{"content": "勉强相关条目", "source": "KB:x",
                       "score": 0.2, "rerank": -2.0}]  # sigmoid(-2)≈0.119，低于中位阈值


# ---------- 替身 ----------

class FakeChatLLM:
    """替身 _ask 底层模型：按序返回预设文本（用尽后重复最后一个），记录全部输入。"""

    def __init__(self, replies):
        self.replies = list(replies)
        self.prompts = []

    async def ainvoke(self, messages, *a, **k):
        self.prompts.append(messages[-1].content)
        text = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        return AIMessage(content=text)


class FakeStructuredLLM:
    """替身 refine 结构化输出模型：按序返回 AgenticDecision 或抛异常，记录全部输入。"""

    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.prompts = []

    async def ainvoke(self, messages, *a, **k):
        self.prompts.append(messages[-1].content)
        o = self.outcomes.pop(0) if len(self.outcomes) > 1 else self.outcomes[0]
        if isinstance(o, Exception):
            raise o
        return o


# ---------- fixtures / helpers ----------

@pytest.fixture
def agentic_on(monkeypatch):
    monkeypatch.setattr(settings, "literature_agentic", True)


@pytest.fixture
def agentic_off(monkeypatch):
    monkeypatch.setattr(settings, "literature_agentic", False)


@pytest.fixture
def audit_calls(monkeypatch):
    """审计替身：拦截 nodes 内审计写入，避免污染真实 audit.jsonl。"""
    calls = []

    class _FakeAudit:
        def write(self, *a, **kw):
            calls.append({"args": a, **kw})

    monkeypatch.setattr(lit_nodes, "get_audit_logger", lambda: _FakeAudit())
    return calls


def _patch_env(monkeypatch, search_results, chat, struct):
    """patch 检索/PubMed/两路 LLM 工厂，返回检索调用记录。"""
    searches = []

    def fake_search(q, k=4):
        searches.append(q)
        if not search_results:
            return []
        return search_results.pop(0) if len(search_results) > 1 else search_results[0]

    async def fake_pubmed(q, k=5):
        return []

    monkeypatch.setattr(lit_nodes, "search_hybrid", fake_search)
    monkeypatch.setattr(lit_nodes, "_pubmed_ids", fake_pubmed)
    monkeypatch.setattr(lit_nodes, "get_llm", lambda *a, **k: chat)
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)
    return searches


def _run_graph(state=None):
    g = build_literature_graph()
    state = state or {"session_id": "t1", "question": QUESTION, "query": QUESTION,
                      "attempts": 0}
    return asyncio.run(g.ainvoke(state, config={"configurable": {"thread_id": "t1"}}))


def _is_fallback(out) -> bool:
    a = out.get("answer") or {}
    return any(str(s).startswith("fallback") for s in (a.get("sources") or []))


# ---------- 路径 1：rewrite → 二次检索有证据 → generate ----------

def test_rewrite_loop_hits_generate(agentic_on, monkeypatch, audit_calls):
    chat = FakeChatLLM([
        '{"sufficient": true}',                                                    # 二次 grade：证据充分
        '{"answer":"二甲双胍是2型糖尿病一线治疗药物。","citations":["KB:demo"]}',  # generate
        '{"supported_ratio": 0.9, "unsupported_claims": [], "high_risk": false}',  # verify
    ])
    struct = FakeStructuredLLM([
        AgenticDecision(action="rewrite", new_query=REWRITE_Q, reason="口语词改术语"),
    ])
    searches = _patch_env(monkeypatch, [[], EVIDENCE], chat, struct)

    out = _run_graph()

    assert out["attempts"] == 1                            # 只消耗一轮 refine 决策
    assert out["query"] == REWRITE_Q                       # 回边用了改写后的查询
    assert out["queries_tried"] == [QUESTION, REWRITE_Q]   # 已试查询登记
    assert out["sufficient"] is True
    assert "一线治疗" in out["answer"]["text"]
    assert out["answer"]["confidence"] > 0.6
    assert searches == [QUESTION, REWRITE_Q]               # 第二次检索用新查询
    assert len(struct.prompts) == 1                        # 仅一次 refine 决策
    # 审计留痕：agentic_refine 事件含 attempt/action/new_query
    ev = [c for c in audit_calls if c.get("action") == "agentic_refine"]
    assert ev and ev[0]["event_type"] == "literature"
    assert ev[0]["payload"]["attempt"] == 1
    assert ev[0]["payload"]["action"] == "rewrite"
    assert ev[0]["payload"]["new_query"] == REWRITE_Q
    assert "elapsed_ms" in ev[0]["payload"]


# ---------- 路径 2：分数分布注入 LLM 输入 + giveup → fallback ----------

def test_low_score_signal_reaches_llm_prompt(agentic_on, monkeypatch):
    """有证据但 rerank 全域低分：分数统计与语料缺失提示必须进入 LLM 输入。"""
    struct = FakeStructuredLLM([AgenticDecision(action="giveup", reason="低分全域")])
    monkeypatch.setattr(lit_nodes, "get_structured_llm",
                        lambda *a, **k: struct)

    out = asyncio.run(refine_node(LiteratureState(
        question="胃疼怎么办", original_query="胃疼怎么办",
        queries_tried=["胃疼怎么办"], evidence=LOW_SCORE_EVIDENCE)))

    prompt = struct.prompts[0]
    assert "0.119" in prompt                               # 分数统计真实注入
    assert "低于" in prompt                                # 语料缺失信号提示
    assert "胃疼怎么办" in prompt                          # 原问题在输入中
    assert "胃疼怎么办" in prompt.split("已尝试")[1]       # 且登记在已试查询列表里
    assert out["agentic_action"] == "giveup"
    assert out["attempts"] == 1


def test_giveup_goes_fallback(agentic_on, monkeypatch):
    chat = FakeChatLLM(["常识性谨慎回答"])                  # 仅 fallback_node 调用
    struct = FakeStructuredLLM([AgenticDecision(action="giveup", reason="语料缺失")])
    searches = _patch_env(monkeypatch, [[]], chat, struct)

    out = _run_graph()

    assert out["attempts"] == 1
    assert _is_fallback(out)
    a = out["answer"]
    assert a.get("needs_human_review") is True
    assert searches == [QUESTION]                          # giveup 不再浪费检索轮次


# ---------- 路径 3：MAX_REFINE=2 轮数上限 ----------

def test_max_refine_cap_after_two_rewrites(agentic_on, monkeypatch):
    assert MAX_REFINE == 2
    chat = FakeChatLLM(["常识性谨慎回答"])
    struct = FakeStructuredLLM([
        AgenticDecision(action="rewrite", new_query="改写一", reason="r1"),
        AgenticDecision(action="rewrite", new_query="改写二", reason="r2"),
    ])
    searches = _patch_env(monkeypatch, [[]], chat, struct)

    out = _run_graph()

    assert out["attempts"] == 2                            # 恰好消耗两轮 refine
    assert len(struct.prompts) == 2                        # 第三轮 grade 后不再 refine
    assert searches == [QUESTION, "改写一", "改写二"]
    assert _is_fallback(out)


# ---------- 路径 4：new_query 重复 → 视为 giveup ----------

def test_duplicate_query_counts_as_giveup(agentic_on, monkeypatch):
    chat = FakeChatLLM(["常识性谨慎回答"])
    # LLM 给出的"改写"与已试查询重复 → 护栏判 giveup，不产生第二次检索
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query=QUESTION)])
    searches = _patch_env(monkeypatch, [[]], chat, struct)

    out = _run_graph()

    assert searches == [QUESTION]                          # 未浪费轮次重检
    assert _is_fallback(out)
    assert out["attempts"] == 1


# ---------- 路径 5：new_query 超长截断 / 空白判 giveup ----------

def test_new_query_truncated_to_200(agentic_on, monkeypatch):
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query="长" * 300)])
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)

    out = asyncio.run(refine_node(LiteratureState(
        question="胃疼怎么办", original_query="胃疼怎么办",
        queries_tried=["胃疼怎么办"], evidence=[])))

    assert out["agentic_action"] == "rewrite"
    assert out["query"] == "长" * 200                      # 代码强制截断，不信任 LLM
    assert out["attempts"] == 1


def test_new_query_blank_counts_as_giveup(agentic_on, monkeypatch):
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query="   ")])
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)

    out = asyncio.run(refine_node(LiteratureState(
        question="胃疼怎么办", original_query="胃疼怎么办",
        queries_tried=["胃疼怎么办"], evidence=[])))

    assert out["agentic_action"] == "giveup"               # 空/纯空白 → giveup
    assert "query" not in out                              # 不污染当前查询
    assert out["attempts"] == 1


# ---------- 轨迹 old_query 语义：必须是上一轮「实际检索」的查询 ----------

def test_refine_old_query_is_last_actually_searched_query(agentic_on, monkeypatch):
    """refine_trace.old_query 必须取 queries_tried 末位（上一轮真实检索过的查询），
    而非可能被 grade_node 的 rewrite_query 覆盖后的 state.query——grade 在 agentic 路径
    返回的 query 只是写作侧草稿改写、并未真正检索过；前端文案「改写查询X→Y再检索」
    的 X 必须真实，否则轨迹误导排障。"""
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query="gastralgia 治疗", reason="改术语")])
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)

    out = asyncio.run(refine_node(LiteratureState(
        question="胃疼怎么办", original_query="胃疼怎么办",
        query="grade草稿改写并未检索",          # 模拟 grade_node 返回 rewrite_query 覆盖后的 state
        queries_tried=["胃疼怎么办"], evidence=[])))

    tr = out["refine_trace"][0]
    assert tr["old_query"] == "胃疼怎么办"                  # 实际检索过的查询，而非 grade 草稿
    assert tr["new_query"] == "gastralgia 治疗"


def test_refine_old_query_second_round_uses_last_rewrite(agentic_on, monkeypatch):
    """第二轮 refine：old_query = 第一轮改写后真实检索过的查询（queries_tried 末位）。"""
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query="改写二", reason="r2")])
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)

    out = asyncio.run(refine_node(LiteratureState(
        question="原问题", original_query="原问题", query="grade又覆盖", attempts=1,
        queries_tried=["原问题", "改写一"], evidence=[])))

    tr = out["refine_trace"][0]
    assert tr["attempt"] == 2
    assert tr["old_query"] == "改写一"                      # 上一轮真实检索查询
    assert tr["new_query"] == "改写二"


# ---------- 路径 6：LLM 结构化输出异常 → fail-safe giveup ----------

def test_llm_failure_failsafe_giveup(agentic_on, monkeypatch):
    chat = FakeChatLLM(["常识性谨慎回答"])
    struct = FakeStructuredLLM([RuntimeError("structured output boom")])
    searches = _patch_env(monkeypatch, [[]], chat, struct)

    out = _run_graph()                                     # 绝不让决策异常冒泡

    assert _is_fallback(out)
    assert out["attempts"] == 1
    assert searches == [QUESTION]
    ev = [c for c in [] ]  # 占位防误读，审计断言见下
    assert len(struct.prompts) == 1


def test_llm_failure_writes_failsafe_audit(agentic_on, monkeypatch, audit_calls):
    struct = FakeStructuredLLM([RuntimeError("boom")])
    monkeypatch.setattr(lit_nodes, "search_hybrid", lambda q, k=4: [])
    monkeypatch.setattr(lit_nodes, "_pubmed_ids", _noop_async)
    monkeypatch.setattr(lit_nodes, "get_structured_llm", lambda *a, **k: struct)

    asyncio.run(refine_node(LiteratureState(
        question="q", original_query="q", queries_tried=["q"], evidence=[])))

    ev = [c for c in audit_calls if c.get("action") == "agentic_refine"]
    assert ev and ev[0]["payload"]["action"] == "giveup"
    assert ev[0]["payload"]["reason"]                      # fail-safe 原因留痕（非空）


async def _noop_async(*a, **k):
    return []


# ---------- 路由函数与图结构（开关行为） ----------

def test_route_on_enabled(agentic_on):
    # v2 语义：sufficient 驱动（融合分经 sigmoid 落 0.5 死区无判别力，分数分流是死代码）
    assert route_after_grade({"sufficient": True, "attempts": 0,
                              "evidence": [{"source": "KB:x", "rerank": -0.85}]}) == "generate"
    assert route_after_grade({"sufficient": True, "attempts": 0,
                              "evidence": [{"source": "KB:x", "rerank": 1.386}]}) == "generate"
    assert route_after_grade({"sufficient": False, "attempts": 0,
                              "evidence": [{"source": "KB:x", "rerank": 1.386}]}) == "refine"
    assert route_after_grade({"sufficient": False, "attempts": 1, "evidence": []}) == "refine"
    assert route_after_grade({"sufficient": False, "attempts": 2,
                              "evidence": [{"source": "KB:x", "rerank": 1.386}]}) == "generate"  # 轮尽兜底
    assert route_after_grade({"sufficient": False, "attempts": 2, "evidence": []}) == "fallback"
    assert route_after_refine({"agentic_action": "rewrite"}) == "retrieve"
    assert route_after_refine({"agentic_action": "giveup"}) == "fallback"


def test_graph_has_refine_node_when_enabled(agentic_on):
    g = build_literature_graph()
    nodes = set(g.get_graph().nodes)
    assert "refine" in nodes
    for n in ("retrieve", "grade", "generate", "verify", "fallback"):
        assert n in nodes


# ---------- 开关关闭：走旧路径 ----------

def test_switch_off_keeps_old_route(agentic_off):
    assert route_after_grade({"sufficient": False, "attempts": 0, "evidence": []}) == "retrieve"
    assert route_after_grade({"sufficient": False, "attempts": 2, "evidence": []}) == "fallback"


def test_switch_off_keeps_old_graph_and_loop(agentic_off, monkeypatch):
    chat = FakeChatLLM(["常识性谨慎回答"])                  # 仅 fallback_node 调用
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query="不应被使用")])
    searches = _patch_env(monkeypatch, [[]], chat, struct)

    g = build_literature_graph()
    assert "refine" not in set(g.get_graph().nodes)        # 旧图结构：不挂 refine 节点

    out = _run_graph()

    assert searches == [QUESTION, QUESTION]                # 旧路径：原查询重检一次
    assert out["attempts"] == 2                            # 旧语义：attempts=检索次数
    assert len(struct.prompts) == 0                        # 从未触发 refine 决策
    assert _is_fallback(out)


# ---------- 分数分流路由（按 top sigmoid 分数决定 refine/generate，非仅零命中） ----------
# sigmoid(-0.85)≈0.299 < HIGH_CONF_THRESHOLD(0.55)：低分证据；sigmoid(1.386)≈0.80 ≥ 阈值：高分证据
MID_EVIDENCE = [{"content": "泛泛而谈条目", "source": "KB:y", "score": 0.3, "rerank": -0.85}]
HIGH_EVIDENCE = [{"content": "高质量条目", "source": "KB:high", "score": 0.9, "rerank": 1.386}]


def test_low_score_evidence_triggers_refine(agentic_on, monkeypatch, audit_calls):
    """sufficient=False 触发 refine（v2）：grade 判证据答非所问（调严后跨域题预期）→
    refine 改写 → 二次检索命中 → 二次 grade 判充分 → generate。"""
    chat = FakeChatLLM([
        '{"sufficient": false, "rewrite_query": "x"}',                             # 首轮 grade：答非所问
        '{"sufficient": true}',                                                    # 二次 grade：证据充分
        '{"answer":"改写后检索到了高质量证据。","citations":["KB:high"]}',          # generate
        '{"supported_ratio": 0.9, "unsupported_claims": [], "high_risk": false}',  # verify
    ])
    struct = FakeStructuredLLM([AgenticDecision(action="rewrite", new_query=REWRITE_Q, reason="低分改写")])
    searches = _patch_env(monkeypatch, [MID_EVIDENCE, HIGH_EVIDENCE], chat, struct)

    out = _run_graph()

    assert len(struct.prompts) == 1                        # 低分证据触发了一次 refine 决策
    assert searches == [QUESTION, REWRITE_Q]               # 二次检索使用改写查询
    assert out["attempts"] == 1
    assert not _is_fallback(out)
    assert "高质量证据" in out["answer"]["text"]
    # refine 轨迹进入最终状态（供 ask/stream 端点构造 refine_trace 返回前端）
    tr = out.get("refine_trace") or []
    assert tr and tr[0]["attempt"] == 1 and tr[0]["action"] == "rewrite"
    assert tr[0]["new_query"] == REWRITE_Q and tr[0]["reason"]
    # 审计留痕照旧：agentic_refine 事件
    assert any(c.get("action") == "agentic_refine" for c in audit_calls)


def test_high_score_evidence_skips_refine(agentic_on, monkeypatch):
    """高分证据（sigmoid≈0.8 ≥ 阈值）直接 generate，refine LLM 零调用——
    防回退断言（绝不删除）：即使 grade 判 sufficient=True（真实流量常态），
    有高分证据也必须直接作答，不得退回历史「胃疼查不到」问题。"""
    chat = FakeChatLLM([
        '{"sufficient": true}',                                                    # grade 宽松判充分（高分直通不折腾）
        '{"answer":"有高分证据即可带出处作答。","citations":["KB:high"]}',          # generate
        '{"supported_ratio": 0.9, "unsupported_claims": [], "high_risk": false}',  # verify
    ])
    struct = FakeStructuredLLM([AgenticDecision(action="giveup", reason="不应被调用")])
    _patch_env(monkeypatch, [HIGH_EVIDENCE], chat, struct)

    out = _run_graph()

    assert len(struct.prompts) == 0                        # 防回退：高分证据绝不触发 refine
    assert out["attempts"] == 0                            # 未消耗 refine 轮数
    assert not _is_fallback(out)
    assert "高分证据" in out["answer"]["text"]


def test_low_score_after_refine_exhausted_falls_back_to_generate(agentic_on, monkeypatch):
    """sufficient=False + 两轮 refine 改写后仍不充分 → 轮数尽后 generate 兜底（有证据
    带出处，总比纯模型常识 fallback 好；这与「零文档 → fallback」语义刻意不同）。"""
    grade_no = '{"sufficient": false, "rewrite_query": "x"}'  # 三轮 grade 均判不充分
    chat = FakeChatLLM([
        grade_no,                                                                  # 三轮 grade 均判不充分
        grade_no,
        grade_no,
        '{"answer":"低分证据兜底作答。","citations":["KB:y"]}',                     # generate 兜底
        '{"supported_ratio": 0.5, "unsupported_claims": [], "high_risk": false}',  # verify
    ])
    struct = FakeStructuredLLM([
        AgenticDecision(action="rewrite", new_query="改写一", reason="r1"),
        AgenticDecision(action="rewrite", new_query="改写二", reason="r2"),
    ])
    searches = _patch_env(monkeypatch, [MID_EVIDENCE], chat, struct)

    out = _run_graph()

    assert out["attempts"] == 2                            # 恰好消耗两轮 refine 决策
    assert len(struct.prompts) == 2
    assert searches == [QUESTION, "改写一", "改写二"]
    assert not _is_fallback(out)                           # 关键断言：generate 兜底而非 fallback
    assert "兜底作答" in out["answer"]["text"]
    tr = out.get("refine_trace") or []
    assert [t["attempt"] for t in tr] == [1, 2]            # 轨迹逐轮累积
    assert all(t["action"] == "rewrite" for t in tr)


# ---------- 终评 F7：grade_node rewrite_query 限长 + llm_factory 死代码清理锁 ----------

def test_grade_node_rewrite_query_truncated_to_200(monkeypatch):
    """grade_node 的 rewrite_query 与 refine 节点 new_query 同口径限长
    （REFINE_QUERY_MAX_LEN=200）：LLM 吐超长改写时代码强制截断，防撑爆后续 prompt。"""
    long_rewrite = "长" * 500

    async def fake_ask(route, text):
        assert route == "medical_literature"
        return '{"sufficient": false, "rewrite_query": "' + long_rewrite + '"}'

    monkeypatch.setattr(lit_nodes, "_ask", fake_ask)
    out = asyncio.run(lit_nodes.grade_node(LiteratureState(
        question="q", evidence=[{"source": "KB:x", "content": "内容"}])))
    assert out["query"] == "长" * lit_nodes.REFINE_QUERY_MAX_LEN
    assert len(out["query"]) == 200


def test_llm_factory_dead_code_removed():
    """终评 F7：llm_factory 的 _MODEL_ID_MAP 死代码（无任何引用的旧映射表）已删除，
    防后续误用复活。"""
    from backend.core import llm_factory
    assert not hasattr(llm_factory, "_MODEL_ID_MAP")
