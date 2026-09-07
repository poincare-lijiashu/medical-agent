"""文献 Agent 图：检索 ⇄ 充分性打分（不足则改写重试）→ 生成 → claim 校验 → 校准置信。"""
from __future__ import annotations

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from backend.agents.medical.literature.nodes import (
    fallback_node,
    generate_node,
    grade_node,
    retrieve_node,
    route_after_grade,
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

    g.set_entry_point("retrieve")
    g.add_edge("retrieve", "grade")
    g.add_conditional_edges("grade", route_after_grade,
                            {"retrieve": "retrieve", "generate": "generate", "fallback": "fallback"})
    g.add_edge("generate", "verify")
    g.add_edge("verify", END)
    g.add_edge("fallback", END)

    return g.compile(checkpointer=checkpointer)
