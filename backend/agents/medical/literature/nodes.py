"""文献 Agent：多步自检 LangGraph（检索→充分性打分/分数分流→生成→claim 溯源校验→校准置信）。

置信度不再自报，而由「精排相关性 × 论断被证据支持比例」算出，命中好证据→高置信，
证据不足/有未支持论断/高危→降置信并（仅必要时）触发人工复核。
agentic 开启时 grade 后按证据分数分流（不看 sufficient——grade 的 sufficient 是写作
充分性而非检索相关性，宽松判定下不能作为 refine 闸门）：top 分数（sigmoid 归一）
≥ HIGH_CONF_THRESHOLD → generate；有文档但低分 → refine 改写重检（轮数尽 → generate
兜底，有出处优于纯常识 fallback）；零命中 → refine（轮数尽 → fallback）。
"""
from __future__ import annotations

import asyncio
import json
import math
import re
import time
from typing import Literal

import httpx
from langchain_core.messages import HumanMessage
from pydantic import BaseModel

from backend.agents.medical.literature.prompts import (
    LITERATURE_ANSWER,
    LITERATURE_GRADE,
    LITERATURE_REFINE,
    LITERATURE_SYSTEM,
    LITERATURE_VERIFY,
)
from backend.agents.medical.literature.state import LiteratureState
from backend.config import settings
from backend.core.llm_factory import get_llm, get_structured_llm, structured_with_fallback
from backend.core.logger import get_logger
from backend.core.medical_audit import get_audit_logger
from backend.core.medical_kb import search_hybrid

logger = get_logger(__name__)
MAX_RETRIEVE = 2  # 检索-打分最多重试轮数（旧路径）
MAX_REFINE = 2  # agentic 循环：refine 决策（改写/放弃）最多轮数，超限直接 fallback
REFINE_QUERY_MAX_LEN = 200  # new_query 安全限长（代码强制截断，不信任 LLM 自律）
_REFINE_SCORE_FLOOR = 0.5  # rerank sigmoid 归一化中位阈值：全域低于视为语料缺失信号
# 分数分流阈值：证据 top 分数（sigmoid 归一后）≥ 该值即视为「有高分证据」直接 generate，
# 低于则进入 refine 改写重检（agentic 开启时）。
# 校准说明：不能取 sigmoid 中位 0.5——BGE reranker 对「完全无关」query-doc 对输出
# logit≈0（sigmoid≈0.50），实测无关带 0.500~0.512、弱相关≈0.58、高相关≥0.72；
# 若取 0.5，跨域噪声分（如「量子纠缠对高血压」top=0.504）会被误判为高分证据，
# 低分分流在真实流量中再次沦为死代码。取 0.55：噪声/跨域低分 → refine 改写重检；
# 弱相关及以上 → generate 直接带出处作答（防回退优先，不折腾有据场景）。
HIGH_CONF_THRESHOLD = 0.55


class AgenticDecision(BaseModel):
    """refine 节点的 LLM 结构化决策（function calling 输出，经 pydantic 校验）。"""
    action: Literal["rewrite", "giveup"] = "giveup"
    new_query: str = ""
    reason: str = ""


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
    # 已尝试查询登记：供 refine 护栏做重复判定（重复改写不浪费轮次）
    tried = list(state.get("queries_tried") or [])
    if query not in tried:
        tried.append(query)
    # attempts 语义分叉：旧路径=已检索次数（供旧路由判重试上限，保持既有语义）；
    # agentic 路径=已消耗的 refine 决策轮数（由 refine_node 自增），检索本身不计数
    attempts = state.get("attempts", 0)
    if not settings.literature_agentic:
        attempts += 1
    return {"evidence": evidence, "pubmed_ids": pmid, "attempts": attempts,
            "queries_tried": tried}


def _refine_scores(state: LiteratureState) -> list:
    """证据 rerank 分数（sigmoid 归一到 0-1），供 refine 判断语料缺失信号。"""
    scores = []
    for e in (state.get("evidence") or []):
        try:
            scores.append(round(1 / (1 + math.exp(-float(e.get("rerank", 0.0)))), 3))
        except Exception:  # noqa: BLE001 —— 单条分数异常跳过，不影响决策
            continue
    return scores


async def refine_node(state: LiteratureState) -> dict:
    """L1 agentic 检索改写决策节点（grade 零命中后进入）。

    输入：原问题、已尝试查询、检索分数分布（top rerank 归一化分；全域低于中位阈值
    → 语料缺失信号写进 prompt，供 LLM 参考及时 giveup）。
    决策：LLM 结构化输出 AgenticDecision（rewrite/giveup）。
    安全护栏（代码强制，不信任 LLM 输出）：new_query strip + 限长 200；
    与 queries_tried 重复 → giveup（不浪费轮次）；空/纯空白 → giveup。
    兜底：refine 全程 try/except —— 任何异常一律视为 giveup 继续 fallback，
    agent 决策绝不阻塞主链路。
    """
    t0 = time.monotonic()
    attempts = state.get("attempts", 0) + 1  # 每次 refine 决策消耗一轮
    question = state.get("original_query") or state.get("question", "")
    tried = list(state.get("queries_tried") or [])
    scores = _refine_scores(state)
    low_corpus = bool(scores) and all(s < _REFINE_SCORE_FLOOR for s in scores)

    prompt = LITERATURE_REFINE.format(
        question=question,
        tried=json.dumps(tried, ensure_ascii=False) or "[]",
        scores=json.dumps(scores, ensure_ascii=False) if scores else "无（检索零命中）",
        corpus_hint=("注意：所有检索分数均低于中位阈值，知识库很可能缺少相关语料，"
                     "若没有高价值改写思路请直接 giveup。\n" if low_corpus else ""),
    )

    decision = None
    try:
        # F3：GLM thinking 模型对 function_calling 结构化输出 400（tool_choice 互斥）时
        # 自动降级 json_mode 重试——literature.refine_llm_failed 与 mdt 同源问题一并覆盖
        decision = await structured_with_fallback(
            get_structured_llm("medical_literature", AgenticDecision), AgenticDecision,
            LITERATURE_SYSTEM + "\n" + prompt, base_llm_fn=lambda: get_llm("medical_literature"))
    except Exception as exc:  # noqa: BLE001 —— 决策异常 fail-safe：视为 giveup，绝不让异常冒泡
        logger.warning("literature.refine_llm_failed", error=str(exc)[:120])

    # ---- 安全护栏：代码强制归一化 LLM 输出（不信任 LLM 自律）----
    action, new_query, reason, guard = "giveup", "", "", []
    if decision is None:
        guard.append("护栏:LLM决策异常fail-safe视为giveup")
    else:
        reason = (getattr(decision, "reason", "") or "")[:120]
        raw = (getattr(decision, "new_query", "") or "").strip()
        if getattr(decision, "action", "") == "rewrite" and raw:
            new_query = raw[:REFINE_QUERY_MAX_LEN]  # 超长截断到 200
            if new_query in tried:
                guard.append("护栏:重复查询判giveup")
            else:
                action = "rewrite"
        elif getattr(decision, "action", "") == "rewrite":
            guard.append("护栏:空查询判giveup")
    reason = (reason + "；" if reason else "") + "；".join(guard)

    # ---- 审计 trace（写失败不影响主链路）----
    elapsed_ms = round((time.monotonic() - t0) * 1000, 1)
    try:
        await asyncio.to_thread(
            get_audit_logger().write,
            event_type="literature", action="agentic_refine",
            payload={"session_id": state.get("session_id", ""), "attempt": attempts,
                     "action": action, "reason": reason[:160],
                     "new_query": new_query[:REFINE_QUERY_MAX_LEN],
                     "question": question[:60], "scores": scores,
                     "elapsed_ms": elapsed_ms})
    except Exception:  # noqa: BLE001
        logger.warning("literature.refine_audit_failed")

    # ---- 改写轨迹（refine_trace）：逐轮累积进 state，供 ask/stream 端点原样返回前端展示 ----
    # old_query=上一轮「实际检索」的查询（queries_tried 末位，retrieve_node 登记的真实检索值；
    # 不取 state.query——它可能被 grade_node 的 rewrite_query 覆盖而从未真正检索过），
    # 前端渲染「改写查询X→Y再检索」的 X 必须真实；条目均限长，防撑大响应
    trace = list(state.get("refine_trace") or [])
    last_searched = tried[-1] if tried else (state.get("query") or state.get("question", ""))
    trace.append({"attempt": attempts, "action": action,
                  "old_query": last_searched[:REFINE_QUERY_MAX_LEN],
                  "new_query": new_query[:REFINE_QUERY_MAX_LEN],
                  "reason": reason[:160]})

    if action != "rewrite":
        return {"attempts": attempts, "agentic_action": "giveup", "refine_trace": trace}
    return {"attempts": attempts, "query": new_query, "agentic_action": "rewrite",
            "refine_trace": trace}


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
    # 终评 F7：rewrite_query 与 refine 节点 new_query 同限长（REFINE_QUERY_MAX_LEN=200，
    # 代码强制截断不信任 LLM 自律——防超长改写撑爆后续 prompt/审计）。
    rewrite = ((d.get("rewrite_query") or "").strip())[:REFINE_QUERY_MAX_LEN]
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
    # 任务3 会话记忆：最近 N 轮【前文对话】块拼入 prompt 头部（仅用于理解指代，
    # 检索 query 不受历史影响——retrieve 仍用本轮 question/query 独立检索）。
    memory = state.get("memory_context") or ""
    raw = await _ask("medical_literature",
                     (memory + "\n" if memory else "") + LITERATURE_SYSTEM + "\n" + prompt)
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
def _top_norm_score(state: LiteratureState) -> float:
    """证据最高分（sigmoid 归一到 0-1）；零证据返回 -1（恒低于任何阈值，便于统一分流）。"""
    scores = _refine_scores(state)
    return max(scores) if scores else -1.0


def route_after_grade(state: LiteratureState) -> str:
    """grade 后分流。开关关闭 → 完全旧路由；开启 → sufficient 驱动的 L1 agentic 语义。

    为什么以 sufficient 为闸门（v2 修正）：检索融合分（0.03 量级）经 sigmoid 后全部
    落在 0.5 死区（sigmoid(0.032)≈0.5075），与真实 rerank 分数无判别力——以分数分流
    时高分分支恒走、refine 成死代码（实测「量子纠缠」跨域题直出）。grade 的 LLM 判断
    是唯一有判别力的相关性信号；其宽松倾向通过 grade prompt 调严（要求证据与问题核心
    意图一致）来纠偏，refine 循环为调严后的误杀提供第二次机会（这正是分级自主性的
    设计闭环：grade 敢严，refine 兜底）。
    """
    if not settings.literature_agentic:
        # ---- 旧路由（开关关闭，语义逐条保持不变）----
        if state.get("sufficient", True):
            return "generate"
        if not state.get("evidence"):
            # 零命中：未超重试 → 原查询重检索；超限 → 模型常识兜底
            if state.get("attempts", 0) < MAX_RETRIEVE:
                return "retrieve"
            return "fallback"
        return "generate"  # 有证据即 generate（高分低分一个待遇，不引入分流）
    # ---- L1 agentic 分流（sufficient 驱动）----
    if state.get("sufficient", True):
        # grade 判证据与问题核心意图一致 → generate 不折腾
        return "generate"
    # sufficient=False：grade 判证据不足/答非所问 → refine 给第二次机会；
    # 轮数尽 → generate 兜底而非 fallback（零文档时 evidence 为空、generate 无从谈起
    # 才走 fallback）：有真实命中语料的回答可被 verify_node 溯源校准出「低置信带出处」
    # 的结果，有出处的回答总比纯模型常识（fallback 丢弃证据）更可靠。
    if state.get("attempts", 0) < MAX_REFINE:
        return "refine"
    return "generate" if state.get("evidence") else "fallback"


def route_after_refine(state: LiteratureState) -> str:
    """refine 决策路由：rewrite → 回 retrieve 重检（串行回边，query 已更新为 new_query）；
    giveup（含护栏转判/异常 fail-safe）→ 直接 fallback，不浪费检索轮次。"""
    return "retrieve" if state.get("agentic_action") == "rewrite" else "fallback"
