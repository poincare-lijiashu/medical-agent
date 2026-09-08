import asyncio

import pytest

from backend.agents.medical.literature.graph import build_literature_graph
from backend.agents.medical.literature.nodes import route_after_grade, verify_node
from backend.agents.medical.literature.state import LiteratureState
from backend.config import settings


def test_graph_compiles():
    g = build_literature_graph()
    nodes = set(g.get_graph().nodes)
    for n in ("retrieve", "grade", "generate", "verify", "fallback"):
        assert n in nodes


@pytest.fixture
def agentic_off(monkeypatch):
    """旧路由断言建立在旧图语义上（agentic 开关默认开启），关闭后验证旧重试路径。"""
    monkeypatch.setattr(settings, "literature_agentic", False)


def test_route_retries_when_insufficient(agentic_off):
    assert route_after_grade(LiteratureState(sufficient=False, attempts=1)) == "retrieve"
    assert route_after_grade(LiteratureState(sufficient=True, attempts=1)) == "generate"
    assert route_after_grade(LiteratureState(sufficient=False, attempts=2)) == "fallback"


def test_verify_no_evidence_low_conf_review():
    out = asyncio.run(verify_node(LiteratureState(question="q", evidence=[], draft={"answer": "", "citations": []})))
    a = out["answer"]
    assert a["needs_human_review"] is True
    assert a["confidence"] < 0.5  # 诚实兜底：无证据→低置信
    assert 0.0 <= a["confidence"] <= 1.0
