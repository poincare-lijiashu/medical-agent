"""文献 Agent 图：检索 ⇄ 充分性打分（不足则改写重试）→ 生成 → claim 校验 → 校准置信。

L1 agentic 检索循环（LITERATURE_AGENTIC=true，默认开启）：grade 零命中或证据低分
（top sigmoid < HIGH_CONF_THRESHOLD）时进入 refine 决策节点（LLM 决策改写重检或放弃），
串行回边 refine → retrieve → grade，轮数上限 MAX_REFINE；giveup/异常 fail-safe 直接
fallback；轮数尽仍有证据（低分）→ generate 兜底（有出处优于纯常识 fallback），零命中
→ fallback。开关关闭时保持旧图结构（不挂 refine 节点，grade 零命中按旧规则原查询重
检索，有证据一律 generate），保证旧路由断言语义不变。
注意：开关在编译时决定图结构、运行时决定路由走向——生产环境应在进程启动前固定
LITERATURE_AGENTIC，禁止运行中热切（避免路由返回值与已编译图结构不一致）。
"""
from __future__ import annotations

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from backend.config import settings
from backend.agents.medical.literature.nodes import (
    fallback_node,
    generate_node,
    grade_node,
    refine_node,
    retrieve_node,
    route_after_grade,
    route_after_refine,
    verify_node,
)
from backend.agents.medical.literature.state import LiteratureState


def build_literature_graph(checkpointer=None):
    """默认不挂 checkpointer：thread_id 每请求唯一、无断点续跑需求，
    MemorySaver 会按唯一 thread_id 无界累积会话快照（长期运行 OOM）。"""
    g = StateGraph(LiteratureState)
    g.add_node("retrieve", retrieve_node)
    g.add_node("grade", grade_node)
    g.add_node("generate", generate_node)
    g.add_node("verify", verify_node)
    g.add_node("fallback", fallback_node)

    grade_map = {"retrieve": "retrieve", "generate": "generate", "fallback": "fallback"}
    if settings.literature_agentic:
        # agentic 循环：grade 零命中 → refine →（rewrite 回 retrieve / giveup 直接 fallback）
        g.add_node("refine", refine_node)
        grade_map["refine"] = "refine"
        g.add_conditional_edges("refine", route_after_refine,
                                {"retrieve": "retrieve", "fallback": "fallback"})

    g.set_entry_point("retrieve")
    g.add_edge("retrieve", "grade")
    g.add_conditional_edges("grade", route_after_grade, grade_map)
    g.add_edge("generate", "verify")
    g.add_edge("verify", END)
    g.add_edge("fallback", END)

    return g.compile(checkpointer=checkpointer)
