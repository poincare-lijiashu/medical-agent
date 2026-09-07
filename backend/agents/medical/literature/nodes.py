"""文献 Agent：多步自检 LangGraph（检索→充分性打分/改写重试→生成→claim 溯源校验→校准置信）。

置信度不再自报，而由「精排相关性 × 论断被证据支持比例」算出，命中好证据→高置信，
证据不足/有未支持论断/高危→降置信并（仅必要时）触发人工复核。
"""
from __future__ import annotations

import asyncio
import json
import math
import re
import time

import httpx
from langchain_core.messages import HumanMessage

from backend.agents.medical.literature.prompts import (
    LITERATURE_ANSWER,
    LITERATURE_GRADE,
    LITERATURE_SYSTEM,
    LITERATURE_VERIFY,
)
from backend.agents.medical.literature.state import LiteratureState
from backend.core.llm_factory import get_llm
from backend.core.logger import get_logger
from backend.core.medical_kb import search_hybrid

logger = get_logger(__name__)
MAX_RETRIEVE = 2  # 检索-打分最多重试轮数


# PubMed 熔断：连接失败/超时后短时间内不再尝试，避免每个问题挂 10s
_PUBMED_BREAKER_UNTIL = 0.0  # monotonic 时间戳；0 表示未触发
_PUBMED_BREAKER_COOLDOWN = 300  # 触发后冷却 5 分钟
_PUBMED_TIMEOUT = httpx.Timeout(connect=2.0, read=5.0, write=2.0, pool=2.0)  # 默认 2s 连 5s 读（网络受限环境）


async def _pubmed_ids(q: str, k: int = 5):
    global _PUBMED_BREAKER_UNTIL
    if time.monotonic() < _PUBMED_BREAKER_UNTIL:
        return []  # 熔断中：跳过，不挂时间
    try:
        async with httpx.AsyncClient(timeout=_PUBMED_TIMEOUT) as c:
            r = await c.get(
                "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi",
                params={"db": "pubmed", "term": q, "retmax": k, "retmode": "json"},
            )
            return r.json().get("esearchresult", {}).get("idlist", [])
    except Exception:  # noqa: BLE001
        _PUBMED_BREAKER_UNTIL = time.monotonic() + _PUBMED_BREAKER_COOLDOWN
        return []


def _strip_md(s: str) -> str:
    s = (s or "").strip()
    if s.startswith("```"):
        return "\n".join(ln for ln in s.split("\n")[1:] if not ln.startswith("```"))
    return s


def _parse_json(raw: str, default: dict) -> dict:
    raw = _strip_md(raw)
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        m = re.search(r"\{.*\}", raw, re.S)
        if m:
            try:
                return json.loads(m.group())
            except Exception:  # noqa: BLE001
                return default
        return default


async def _ask(route: str, text: str) -> str:
    llm = get_llm(route, temperature=0)
    r = await llm.ainvoke([HumanMessage(content=text)])
    return r.content if hasattr(r, "content") else str(r)


def _evidence_brief(evidence: list) -> list:
    return [{"source": e.get("source", ""), "content": (e.get("content") or "")[:600]}
            for e in evidence]


def _ground_citations(citations: list, evidence: list, pubmed_ids: list):
    """引用落地：只保留能在证据集/PubMed IDs 中核验的 citation（防幻觉）。返回 (kept, dropped)。"""
    def key(x):
        return str(x).split(":")[-1].strip()
    allowed_keys = {key(e.get("source", "")) for e in evidence if e.get("source")}
    allowed_keys |= {str(pid).strip() for pid in (pubmed_ids or [])}
    kept, dropped = [], []
    for c in (citations or []):
        (kept if key(c) in allowed_keys else dropped).append(c)
    return kept, dropped


# ---------- 节点 ----------
async def retrieve_node(state: LiteratureState) -> dict:
    query = state.get("query") or state["question"]
    # 同步推理（BGE编码+Milvus+CrossEncoder）移出事件循环；失败降级为空证据→走“无据低置信”分支
    try:
        evidence = await asyncio.to_thread(search_hybrid, query, 4) or []
    except Exception as exc:  # noqa: BLE001
        logger.warning("literature.retrieve_degraded", error=str(exc)[:120])
        evidence = []
    pmid = await _pubmed_ids(query)
    return {
        "evidence": evidence,
        "pubmed_ids": pmid,
        "attempts": state.get("attempts", 0) + 1,
    }


async def grade_node(state: LiteratureState) -> dict:
    evidence = state.get("evidence", [])
    if not evidence:
        return {"sufficient": False, "query": state["question"]}
    prompt = LITERATURE_GRADE.format(
        question=state["question"],
        evidence=json.dumps(_evidence_brief(evidence), ensure_ascii=False)[:1500],
    )
    raw = await _ask("medical_literature", LITERATURE_SYSTEM + "\n" + prompt)
    d = _parse_json(raw, {"sufficient": True, "rewrite_query": ""})
    rewrite = (d.get("rewrite_query") or "").strip()
    return {
        "sufficient": bool(d.get("sufficient", True)),
        "query": (rewrite or state.get("query") or state["question"]),
    }


async def generate_node(state: LiteratureState) -> dict:
    evidence = state.get("evidence", [])
    prompt = LITERATURE_ANSWER.format(
        local=json.dumps(_evidence_brief(evidence), ensure_ascii=False),
        pubmed=state.get("pubmed_ids", [])[:3],
        question=state["question"],
    )
    raw = await _ask("medical_literature", LITERATURE_SYSTEM + "\n" + prompt)
    d = _parse_json(raw, {"answer": _strip_md(raw), "citations": []})
    return {"draft": {"answer": d.get("answer", ""), "citations": d.get("citations", []) or []}}


async def fallback_node(state: LiteratureState) -> dict:
    """模型常识兜底层：检索未命中相关证据时，凭 LLM 医学常识给谨慎参考回答，
    但强制降置信≤0.4、标注“未检索·仅供参考”、需人工复核（不冒充循证结论）。"""
    q = state["question"]
    prompt = ("你是临床辅助助手。知识库未检索到相关权威证据。请仅凭通用医学常识给出谨慎、"
              "非处方性的参考回答，不得编造具体剂量/指南结论，提醒需结合面诊与复核。用中文、简洁分点。\n问题：" + q)
    try:
        raw = await _ask("medical_literature", LITERATURE_SYSTEM + "\n" + prompt)
        body = _strip_md(raw)
    except Exception:  # noqa: BLE001
        body = "（模型暂不可用）建议直接转人工医师复核。"
    text = "⚠ 未检索到权威循证证据，以下为模型常识，仅供参考，请务必人工复核。\n\n" + body
    return {"answer": {"text": text, "confidence": 0.4, "sources": ["fallback:model-prior"],
                       "needs_human_review": True, "verified": False, "evidence": []}}


async def verify_node(state: LiteratureState) -> dict:
    evidence = state.get("evidence", [])
    draft = state.get("draft", {"answer": "", "citations": []})
    answer_text = draft.get("answer", "")

    # 无证据：直接低置信 + 需复核（诚实兜底）
    if not evidence:
        return {"answer": {
            "text": "知识库与文献中未检索到足够证据，无法给出可靠回答。请补充专科指南或换一种问法；此结果已标记为需人工复核。",
            "confidence": 0.2, "sources": [], "needs_human_review": True, "verified": False,
        }}

    v_prompt = LITERATURE_VERIFY.format(
        evidence=json.dumps(_evidence_brief(evidence), ensure_ascii=False)[:2000],
        answer=answer_text,
    )
    v_raw = await _ask("medical_literature", LITERATURE_SYSTEM + "\n" + v_prompt)
    v = _parse_json(v_raw, {"supported_ratio": 0.5, "unsupported_claims": [], "high_risk": False})

    supported = max(0.0, min(1.0, float(v.get("supported_ratio", 0.5))))
    top_norm = max((1 / (1 + math.exp(-float(e.get("rerank", 0.0)))) for e in evidence), default=0.5)
    conf = round(min(0.97, 0.35 + 0.35 * top_norm + 0.30 * supported), 2)
    high_risk = bool(v.get("high_risk", False))

    # claim 溯源护栏：剔除无法在证据中核验的引用（防幻觉）
    kept, dropped = _ground_citations(draft.get("citations", []), evidence, state.get("pubmed_ids", []))
    if dropped:
        conf = min(conf, 0.6)
    if draft.get("citations") and not kept:
        conf = min(conf, 0.4)  # 声称有引用但全部无据 → 强降信
    need_review = conf < 0.6 or high_risk or supported < 0.6 or bool(dropped)

    text = answer_text
    if v.get("unsupported_claims"):
        text += "\n\n（校验提示：以下论断未被检索证据充分支持：" + "；".join(v["unsupported_claims"][:3]) + "）"
    if dropped:
        text += "\n（已剔除无法溯源的引用：" + "、".join(map(str, dropped[:3])) + "）"
    return {"answer": {
        "text": text, "confidence": conf, "sources": kept,
        "needs_human_review": need_review, "verified": True,
        "evidence": _evidence_brief(evidence),
    }}


# ---------- 路由 ----------
def route_after_grade(state: LiteratureState) -> str:
    if state.get("sufficient", True):
        return "generate"
    # 关键修正：只要检索到了证据（rerank 分数>0），即便 LLM 判"证据不充分"（如症状类宽泛问题），
    # 也应把证据交给 generate 生成"带出处的参考回答"（verify_node 会做 claim 溯源并校准置信），
    # 而不是直接走 fallback（丢弃证据、纯模型常识）。fallback 仅在"检索一无所获"时启用。
    if state.get("evidence"):
        return "generate"
    if state.get("attempts", 0) < MAX_RETRIEVE:
        return "retrieve"
    return "fallback"  # 检索零命中→模型常识兜底（降信+标注）
