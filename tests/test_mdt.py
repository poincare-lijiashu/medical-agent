"""MDT 会诊：引用落地防幻觉 + 三专科并行 fan-out 拓扑 + 路由层图单例。"""
from __future__ import annotations

import asyncio
import json


from backend.agents.medical import mdt as mdt_mod


def _fake_ask_factory(citations):
    async def fake_ask(route: str, text: str) -> str:
        if "citations" in text:
            return json.dumps({"opinion": "循证意见", "citations": citations}, ensure_ascii=False)
        if "待鉴别" in text:
            return json.dumps({"opinion": "临床意见"}, ensure_ascii=False)
        return json.dumps({
            "headline": "信息不足，建议完善评估排除急腹症",
            "urgency": "中",
            "key_actions": ["监测生命体征", "完善腹部查体"],
            "summary": "小结", "consensus": "共识", "disagreements": "无", "plan": "随访",
        }, ensure_ascii=False)
    return fake_ask


def _fake_search(q, k):
    return [{"content": "权威证据内容", "source": "KB:foo", "rerank": 5.0}]


def test_mdt_literature_op_drops_hallucinated_citation(monkeypatch):
    """MDT 循证意见的引用必须过 _ground_citations：无据引用剔除并降置信（与主文献链路同护栏）。"""
    monkeypatch.setattr(mdt_mod, "search_hybrid", _fake_search)
    monkeypatch.setattr(mdt_mod, "_ask", _fake_ask_factory(["KB:foo", "PMID:99999"]))
    out = asyncio.run(mdt_mod.literature_op({"case": "测试病例"}))
    op = out["opinions"][0]
    assert op["sources"] == ["KB:foo"]   # PMID:99999 无据 → 剔除
    assert op["confidence"] <= 0.6       # 有剔除 → 降置信


def test_mdt_graph_parallel_fanout(monkeypatch):
    """三专科（循证/药学/临床）应并行 fan-out 后汇入 synthesize，且各产出一个意见。"""
    monkeypatch.setattr(mdt_mod, "search_hybrid", _fake_search)
    monkeypatch.setattr(mdt_mod, "_ask", _fake_ask_factory(["KB:foo"]))
    monkeypatch.setattr(mdt_mod, "drug_check",
                        lambda q: {"answer": "未发现已知相互作用。", "sources": ["drug_rules:curated_v1"]})
    g = mdt_mod.build_mdt_graph()
    edges = set()
    for e in g.get_graph().edges:
        if isinstance(e, tuple):
            s, t = e[0], e[1]          # namedtuple(source, target, data)
        else:
            s, t = e.source, e.target
        edges.add((s, t))
    starts = {t for (s, t) in edges if s in ("__start__", "START")}
    joins = {s for (s, t) in edges if t == "synthesize"}
    assert {"literature", "drug", "case"} <= starts
    assert {"literature", "drug", "case"} <= joins

    out = asyncio.run(g.ainvoke({"session_id": "t", "case": "测试病例", "opinions": []},
                                config={"configurable": {"thread_id": "t"}}))
    assert len(out["opinions"]) == 3
    assert {o["specialty"] for o in out["opinions"]} == {"循证医学", "药学", "临床诊断"}
    assert out["report"]["confidence"] > 0
    assert out["report"]["needs_human_review"] is not None
    # 重点前置结构：一句话结论 + 紧急度 + 建议行动
    rep = out["report"]
    assert rep["headline"] == "信息不足，建议完善评估排除急腹症"
    assert rep["urgency"] in ("高", "中", "低")
    assert rep["key_actions"] == ["监测生命体征", "完善腹部查体"]


def test_get_mdt_graph_is_singleton():
    """MDT 图应像文献图一样进程内单例复用，而非每次请求重建。"""
    from backend.api.v1.medical.medical_router import _get_mdt_graph
    assert _get_mdt_graph() is _get_mdt_graph()
