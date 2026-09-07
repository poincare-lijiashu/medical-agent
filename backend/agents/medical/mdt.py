"""MDT 多学科会诊 Agent 图：循证 + 药学 + 临床 三专科并行给出意见 → 主持人综合并标注分歧。

体现多智能体编排：三个专科节点 fan-out 并行执行（循证检索与临床推理互不阻塞，缩短会诊耗时），
各专科节点产出结构化意见（opinions 用 operator.add 归并），synthesis 节点汇总共识/分歧；
循证意见的引用经 _ground_citations 落地校验（与主文献链路同护栏），
整体置信度由专科置信与分歧情况校准；分歧或高危进入双人核对。
"""
from __future__ import annotations

import asyncio
import json
import operator
from typing import Annotated, Optional, TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph

from backend.agents.medical.literature.nodes import (
    _ask,
    _evidence_brief,
    _ground_citations,
    _parse_json,
)
from backend.core.llm_factory import get_llm
from backend.core.medical_drug import check as drug_check
from backend.core.medical_kb import search_hybrid


class MdtState(TypedDict, total=False):
    session_id: str
    case: str
    opinions: Annotated[list, operator.add]  # 并行专科节点各自追加，reducer 归并
    report: Optional[dict]


_SYS = "你是严谨的临床多学科会诊(MDT)助手，只依据给定信息与循证证据，坦诚标注不确定与分歧，禁止编造。"


async def literature_op(state: MdtState) -> dict:
    case = state["case"]
    try:
        ev = await asyncio.to_thread(search_hybrid, case, 3) or []
    except Exception:  # noqa: BLE001
        ev = []
    prompt = ("针对该临床问题给出循证意见(2-3 句)并引用来源编号。\n"
              f"证据：{json.dumps(_evidence_brief(ev), ensure_ascii=False)}\n问题：{case}\n"
              '返回 JSON：{"opinion":"...","citations":["..."]}')
    d = _parse_json(await _ask("medical_literature", _SYS + "\n" + prompt), {"opinion": "", "citations": []})
    # claim 溯源护栏：只保留能在证据集核验的引用（防幻觉），有剔除则降置信
    kept, dropped = _ground_citations(d.get("citations", []), ev, [])
    conf = 0.85 if ev else 0.4
    if dropped:
        conf = min(conf, 0.6)
    op = {"specialty": "循证医学", "opinion": d.get("opinion", ""),
          "sources": kept, "confidence": conf}
    return {"opinions": [op]}


async def drug_op(state: MdtState) -> dict:
    r = drug_check(state["case"])
    has = "发现以下相互作用" in r["answer"]
    sev = "禁忌" if "禁忌" in r["answer"] else ("高危" if "高危" in r["answer"] else ("中危" if has else "无"))
    conf = 0.9 if has else 0.5
    op = {"specialty": "药学", "opinion": r["answer"], "sources": r["sources"],
          "confidence": conf, "severity": sev}
    return {"opinions": [op]}


async def case_op(state: MdtState) -> dict:
    prompt = ("作为临床医师，基于病例信息给出：主要问题概括 + 待鉴别方向(要点式)。"
              "不下确诊结论。\n病例：" + state["case"] +
              '\n返回 JSON：{"opinion":"..."}')
    d = _parse_json(await _ask("medical_case", _SYS + "\n" + prompt), {"opinion": ""})
    op = {"specialty": "临床诊断", "opinion": d.get("opinion", ""), "sources": [], "confidence": 0.6}
    return {"opinions": [op]}


async def synthesize_node(state: MdtState) -> dict:
    ops = state.get("opinions", [])
    prompt = ("下面是 MDT 各专科意见，请综合并【重点前置】：1) headline 一句话会诊结论(不超过40字，点明最关键判断) "
              "2) urgency 紧急度(高/中/低，按急腹症/危重症风险判断) 3) key_actions 医生最该先做的3-5条行动 "
              "4) 会诊小结 5) 共识 6) 分歧点(若无写\'无\') 7) 下一步建议。\n"
              f"意见：{json.dumps(ops, ensure_ascii=False)}\n问题：{state['case']}\n"
              '返回 JSON：{"headline":"...","urgency":"高|中|低","key_actions":["..."],'
              '"summary":"...","consensus":"...","disagreements":"...","plan":"..."}')
    d = _parse_json(await _ask("medical_literature", _SYS + "\n" + prompt),
                    {"headline": "", "urgency": "中", "key_actions": [],
                     "summary": "", "consensus": "", "disagreements": "无", "plan": ""})
    dis = d.get("disagreements", "无")
    has_dis = dis and dis not in ("无", "无。", "None")
    confs = [o.get("confidence", 0.5) for o in ops] or [0.5]
    overall = round(min(0.95, sum(confs) / len(confs) * (0.85 if has_dis else 1.0)), 2)
    risky = any(o.get("severity") in ("禁忌", "高危") for o in ops)
    need_review = overall < 0.6 or has_dis or risky
    urgency = d.get("urgency") if d.get("urgency") in ("高", "中", "低") else "中"
    headline = (d.get("headline") or "").strip() or (d.get("summary", "")[:40] or "会诊完成")
    key_actions = [str(a) for a in (d.get("key_actions") or []) if str(a).strip()][:5]
    lines = [f"【{o['specialty']}】(置信 {o.get('confidence'):.2f}) {o.get('opinion','')}" for o in ops]
    report_text = ("会诊小结：" + d.get("summary", "") +
                   "\n共识：" + d.get("consensus", "") +
                   "\n分歧：" + dis + "\n建议：" + d.get("plan", "") +
                   "\n\n—— 各专科意见 ——\n" + "\n".join(lines))
    return {"report": {
        "headline": headline, "urgency": urgency, "key_actions": key_actions,
        "text": report_text, "confidence": overall, "opinions": ops,
        "disagreements": dis, "needs_human_review": need_review,
        "sources": sorted({s for o in ops for s in o.get("sources", [])}),
    }}


def build_mdt_graph(checkpointer=None):
    """默认不挂 checkpointer（与 literature 同纪律）：thread_id 每请求唯一、无断点续跑需求，
    MemorySaver 会按唯一 thread_id 无界累积会话快照（长期运行 OOM）。"""
    g = StateGraph(MdtState)
    g.add_node("literature", literature_op)
    g.add_node("drug", drug_op)
    g.add_node("case", case_op)
    g.add_node("synthesize", synthesize_node)
    # 三专科并行 fan-out：START → {literature, drug, case} → synthesize（join）
    g.add_edge(START, "literature")
    g.add_edge(START, "drug")
    g.add_edge(START, "case")
    g.add_edge("literature", "synthesize")
    g.add_edge("drug", "synthesize")
    g.add_edge("case", "synthesize")
    g.add_edge("synthesize", END)
    return g.compile(checkpointer=checkpointer)
