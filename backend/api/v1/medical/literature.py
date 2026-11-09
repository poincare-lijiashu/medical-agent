"""文献 agent 端点（W4.3 router 拆分）：非流式 ask + SSE 流式 stream。

LangGraph 文献管线懒加载单例（_lit_graph，双检锁）——测试 monkeypatch
「backend.api.v1.medical.literature._lit_graph」替换替身图。
"""
from __future__ import annotations

import asyncio
import threading
import uuid

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from backend.api.deps import model_quota, rate_limit, require_user
from backend.api.v1.medical.schemas import AUDIT_Q_SNIPPET, AskReq, AskResp
from backend.api.v1.medical import shared as _shared
from backend.api.v1.medical.shared import (_enqueue_if_risk, _is_fallback_answer, _sse,
                                           redactor as _redactor)
from backend.core import chat_memory
from backend.core.intent import offtopic_tech_reply, prefilter_reply

router = APIRouter(dependencies=[Depends(rate_limit)])

_lit_graph = None
_graph_lock = threading.Lock()  # 懒加载双检锁：并发首访只构建一次，防重复构建竞态


def _get_lit_graph():
    global _lit_graph
    if _lit_graph is None:
        with _graph_lock:
            if _lit_graph is None:  # 双检：拿到锁后再确认，避免重复构建
                from backend.agents.medical.literature.graph import build_literature_graph
                _lit_graph = build_literature_graph()
    return _lit_graph


@router.post("/literature/ask", response_model=AskResp)
async def literature_ask(req: AskReq, user: dict = Depends(require_user),
                         _quota: None = Depends(model_quota)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    # 任务3 会话记忆：session_id 可选（前端每会话固定 uuid；评估路径不带 → 天然跳过）。
    # 构建最近 N 轮「前文对话」块（turns=0/无历史 → 空串），仅注入 generate 的 prompt 头部。
    chat_sid = (req.session_id or "").strip()[:64]
    memory_ctx, mem_used = ("", 0)
    if chat_sid:
        memory_ctx, mem_used = await asyncio.to_thread(chat_memory.build_context, chat_sid)
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False, sources=["intent:prefilter"])
    # 任务C：技术领域守门（极保守：命中技术词且零医学信号才拒答）。
    # 拒答路径：固定文案、不检索、不入审核队列、不调 LLM；审计记 offtopic_reject。
    off = offtopic_tech_reply(q)
    if off:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="offtopic_reject",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=off, confidence=0.9, needs_human_review=False, sources=["intent:offtopic"])
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="query",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET], "memory_used": mem_used})
    try:
        g = _get_lit_graph()
        sid = "lit-" + uuid.uuid4().hex[:12]
        # original_query 与 stream 入口同初值纪律：refine 决策的 question 取 original_query，
        # 两条路径 state 必须一致（此前 ask 漏传，仅靠 refine_node 兜底 question）。
        out = await g.ainvoke({"session_id": sid, "question": q, "query": q,
                               "original_query": q, "attempts": 0,
                               "memory_context": memory_ctx},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001 —— 三层兑底：provider/检索异常→体面降级不裸500
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="degraded",
                                actor=actor, payload={"err": str(e)[:120]})
        return AskResp(answer="模型/知识库服务暂时不可用（多为瞬时），已记录并标记需人工复核；请稍后重试。",
                       confidence=0.3, needs_human_review=True, sources=["degraded:llm"])
    a = out["answer"]
    # 任务3：响应完成后写入会话记忆（只存本轮问答精简文本，deque 自动截断旧轮次）。
    if chat_sid:
        await asyncio.to_thread(chat_memory.remember, chat_sid, q, a["text"])
    # 任务1：fallback 兜底答案（sources 含 fallback: 前缀）不写审核队列——常识兜底已强制
    # 降置信 + 「请人工复核」文案，仅提示性内容、无 second-reviewer 可实质核对；审计留痕。
    if _is_fallback_answer(a):
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature",
                                action="fallback_no_enqueue", actor=actor,
                                payload={"conf": a["confidence"]})
        rid = None
    else:
        rid = await _enqueue_if_risk("literature", q, a["text"], a["confidence"],
                               a.get("needs_human_review", False), "低置信或未支持论断", actor,
                               sources=a.get("sources", []))
    return AskResp(answer=a["text"], confidence=a["confidence"],
                   needs_human_review=a.get("needs_human_review", False),
                   sources=a.get("sources", []), review_id=rid,
                   evidence=a.get("evidence", []),
                   refine_trace=out.get("refine_trace", []))


@router.post("/literature/stream")
async def literature_stream(req: AskReq, user: dict = Depends(require_user),
                            _quota: None = Depends(model_quota)):
    """SSE 流式：逐节点推进度(retrieve/grade/generate/verify) + 末尾 result。失败发 error 事件。
    任务3：与非流式 ask 同一会话记忆语义（session_id 可选 → 注入/写入/审计一致）。"""
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    chat_sid = (req.session_id or "").strip()[:64]
    memory_ctx, mem_used = ("", 0)
    if chat_sid:
        memory_ctx, mem_used = await asyncio.to_thread(chat_memory.build_context, chat_sid)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="stream",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET], "memory_used": mem_used})

    async def gen():
        pre = prefilter_reply(q)
        if pre:
            yield _sse("result", {"answer": pre, "confidence": 0.95,
                                  "needs_human_review": False, "sources": ["intent:prefilter"], "review_id": None})
            return
        # 任务C：技术领域守门（与非流式 ask 同判定同文案）：不检索、不入队、不调 LLM。
        off = offtopic_tech_reply(q)
        if off:
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="offtopic_reject",
                                    actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
            yield _sse("result", {"answer": off, "confidence": 0.9,
                                  "needs_human_review": False, "sources": ["intent:offtopic"], "review_id": None})
            return
        final = None
        # agentic 改写轨迹就地累积：stream_mode="updates" 只给逐节点增量、拿不到最终 state，
        # 因此在流式过程中捕获 refine 节点输出——①每轮实时推送 event: refine（前端思考气泡
        # 即时显示「改写查询X→Y再检索」）；②收尾随 result 事件的 refine_trace 整体下发。
        # 该方案不侵入图内部（无需节点回调/自定义事件），是 SSE 协议下最稳的实现。
        trace = []
        try:
            g = _get_lit_graph()
            sid = "lit-" + uuid.uuid4().hex[:12]
            async for upd in g.astream({"session_id": sid, "question": q, "query": q,
                                        "original_query": q, "attempts": 0,
                                        "memory_context": memory_ctx},
                                       config={"configurable": {"thread_id": sid}}, stream_mode="updates"):
                for node, out in upd.items():
                    if node in ("retrieve", "grade", "generate"):
                        yield _sse("progress", {"step": node})
                    elif node == "refine" and isinstance(out, dict):
                        # refine 决策产出为累积轨迹（末位即本轮），实时推送本轮 + 留存全量
                        tr = out.get("refine_trace") or []
                        if tr:
                            trace = tr
                            yield _sse("refine", tr[-1])
                    elif node == "fallback":
                        yield _sse("progress", {"step": "fallback"})
                        if isinstance(out, dict) and out.get("answer"):
                            final = out["answer"]
                    elif node == "verify" and isinstance(out, dict) and out.get("answer"):
                        final = out["answer"]
        except Exception as e:  # noqa: BLE001
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature", action="stream_error",
                                    actor=actor, payload={"err": str(e)[:160]})
            yield _sse("error", {"detail": "生成过程出现异常，已记录审计并标记复核；请稍后重试"})
            return
        if not final:
            yield _sse("error", {"detail": "未生成结果"})
            return
        # 任务3：响应完成后写入会话记忆（与非流式 ask 同纪律；error/无结果不写）。
        if chat_sid:
            await asyncio.to_thread(chat_memory.remember, chat_sid, q, final["text"])
        # 任务1：fallback 兜底答案不写审核队列（与非流式 ask 同一纪律），审计记 fallback_no_enqueue。
        if _is_fallback_answer(final):
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="literature",
                                    action="fallback_no_enqueue", actor=actor,
                                    payload={"conf": final["confidence"]})
            rid = None
        else:
            rid = await _enqueue_if_risk("literature", q, final["text"], final["confidence"],
                                   final.get("needs_human_review", False), "低置信或未支持论断", actor,
                                   sources=final.get("sources", []))  # 诊断2：与非流式 ask 同纪律补传 sources
        yield _sse("result", {"answer": final["text"], "confidence": final["confidence"],
                              "needs_human_review": final.get("needs_human_review", False),
                              "sources": final.get("sources", []), "review_id": rid,
                              "evidence": final.get("evidence", []),
                              "refine_trace": trace})

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
