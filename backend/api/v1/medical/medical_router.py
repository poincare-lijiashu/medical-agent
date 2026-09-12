"""MedAssist 医疗 API：4 agent + 审核中心 + health。

临床医生版：置信度按证据强度真实校准；仅高风险/低置信项进入「双人核对」队列。
全部 /ask 受 Bearer 保护；请求经 PHI 脱敏与审计（记录真实操作者）。
"""
from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import threading
import time
import uuid
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field, field_validator

from backend.api.deps import require_role, require_user, rate_limit, revoke_user
from backend.config import settings
from backend.core import chat_memory, kb_ingest, llm_config, runtime_flags
from backend.core import medical_review as review
from backend.core import case_archive  # 阶段4：病例库合规归档（qc approve 自动归档挂点）
from backend.core import pg_store, qc
from backend.core import prescriptions as rx  # 阶段2.3 处方流转域层（状态机/repo）
from backend.core.intent import offtopic_tech_reply, prefilter_reply
from backend.core.llm_factory import get_llm  # suggest 端点走模块级符号（测试 monkeypatch 兼容）
from backend.core.logger import get_logger
from backend.core.medical_audit import PHIRedactor, current_channel, get_audit_logger
from backend.core.medical_drug import check as drug_check
from backend.integration import registry as his_registry
from backend.integration.base import NullAdapter

router = APIRouter(dependencies=[Depends(rate_limit)])
_redactor = PHIRedactor()
logger = get_logger(__name__)  # suggest 硬校验丢弃幻觉药名等留痕
_lit_graph = None
_mdt_graph = None
_graph_lock = threading.Lock()  # 懒加载双检锁：并发首访只构建一次，防重复构建竞态
_START = time.time()

MAX_IMAGES = 10  # 轮 A2：图片上限 6→10（与 medical_imaging.describe_images 上限一致）

# 审计 payload 中问题文本的截断长度（外部审查 L2 常量化）：各端点 prefilter/offtopic/
# query/stream 等 q 字段统一取前 AUDIT_Q_SNIPPET 字符——此前 literature prefilter/
# stream 主审计/offtopic_reject 分别为 60/200/60 的硬编码混用，现全部统一走本常量。
AUDIT_Q_SNIPPET = 200

# 影像/病例高危征象词表：默认覆盖常见危急值类征象；院方可经 IMAGING_RISK_WORDS（逗号分隔）覆盖
_DEFAULT_RISK_WORDS = ("恶性", "肿瘤", "癌", "转移", "占位", "出血", "梗死", "栓塞", "血栓",
                       "穿孔", "坏死", "气胸", "骨折", "梗阻", "破裂", "脓肿", "卒中", "中风",
                       "夹层", "积液", "禁忌", "可疑")
RISK_WORDS = (tuple(w.strip() for w in (settings.imaging_risk_words or "").split(",") if w.strip())
              or _DEFAULT_RISK_WORDS)
_NEGATED = re.compile(r"(?:无|未见|未发现|未提示|未见明确|排除)[^，。；,;]{0,6}")


def _has_risk(desc: str) -> bool:
    """高危词判定：先剔除“无/未见…”等否定短语（≤6 字），避免“未见可疑征象”误报复核。"""
    d = _NEGATED.sub("", desc or "")
    return any(w in d for w in RISK_WORDS)


def _is_fallback_answer(a: dict | None) -> bool:
    """任务1：fallback 兜底答案判定（fallback_node 契约：sources 含 fallback: 前缀）。"""
    return any(str(s).startswith("fallback:") for s in ((a or {}).get("sources") or []))


def _get_lit_graph():
    global _lit_graph
    if _lit_graph is None:
        with _graph_lock:
            if _lit_graph is None:  # 双检：拿到锁后再确认，避免重复构建
                from backend.agents.medical.literature.graph import build_literature_graph
                _lit_graph = build_literature_graph()
    return _lit_graph


def _get_mdt_graph():
    global _mdt_graph
    if _mdt_graph is None:
        with _graph_lock:
            if _mdt_graph is None:  # 双检：拿到锁后再确认，避免重复构建
                from backend.agents.medical.mdt import build_mdt_graph
                _mdt_graph = build_mdt_graph()
    return _mdt_graph


@router.get("/config")
async def get_config(user: dict = Depends(require_user)):
    """前端据此动态显示真实模型名（不再硬编码）。FIND-15：管理端激活 provider 的 model_id
    优先（展示与实跑一致），无配置回落 .env 内置值。仅返回模型名，不回显密钥。"""
    chat = await asyncio.to_thread(llm_config.active_provider, "chat")
    vision = await asyncio.to_thread(llm_config.active_provider, "vision")
    from backend.core.medical_kb import milvus_status
    milvus = await asyncio.to_thread(milvus_status)
    return {"chat_model": (chat or {}).get("model_id") or settings.qwen_model_chat,
            "vl_model": (vision or {}).get("model_id") or settings.qwen_model_vl,
            "reasoning_model": settings.qwen_model_reasoning,
            "milvus": milvus,
            # 任务3：前端据此切换审核中心形态（True=留痕模式：qc/admin 全局只读档案）。
            # runtime_flags 优先（admin 运行时切换即时生效），无键回落 settings 启动值。
            "qc_auto_sign_full": runtime_flags.full_mode()}


class ConfigToggleReq(BaseModel):
    key: str = Field(max_length=64)
    value: bool | None = None  # 缺省=在当前值上取反（toggle）


@router.post("/admin/config-toggle")
async def admin_config_toggle(req: ConfigToggleReq, user: dict = Depends(require_role("admin"))):
    """任务3：admin 运行时切换行为开关（如「留痕模式」qc_auto_sign_full）。

    写 data/runtime_flags.json（backend/core/runtime_flags.py 白名单键）——决策点
    （决策点 _enqueue_if_risk（drug 类豁免，本批次任务2）/ GET /config）经
    runtime_flags.full_mode() 读值，
    flags 文件优先于启动时 settings，切换即时生效、无需重启。admin 权限；审计留痕。
    """
    if req.key not in runtime_flags.ALLOWED_FLAGS:
        raise HTTPException(400, f"不支持的运行时开关：{req.key}")
    cur = runtime_flags.full_mode() if req.key == "qc_auto_sign_full" else False
    new_val = (not cur) if req.value is None else bool(req.value)
    val = await asyncio.to_thread(runtime_flags.set_flag, req.key, new_val)
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="config_toggle",
                            actor=user.get("username", "admin"),
                            payload={"key": req.key, "from": cur, "to": val})
    await pg_store.mirror_audit("admin.config_toggle", user.get("username", "admin"),
                                {"key": req.key, "value": val})
    return {"ok": True, "key": req.key, "value": val}


@router.get("/overview")
async def overview(user: dict = Depends(require_user)):
    """概览面板：真实运行与合规指标（不编造）。"""
    from backend.core.embedder import device
    from backend.core.medical_kb import doc_count
    items = await asyncio.to_thread(review.list_all, with_images=False)  # 队列文件读盘移出事件循环（QUALITY-001）；概览只计数，剥离 images 控响应体积（任务5）
    pending = sum(1 for i in items if i.get("status") == "pending")
    resolved = sum(1 for i in items if i.get("status") in ("approved", "rejected"))
    al = get_audit_logger()
    # 任务1：recent 携带完整 payload，前端 humanizeAudit 生成人话描述（仅 event 名不可读）
    recent = [{"ts": e["ts"][11:19], "event": e["event_type"],
               "action": e["payload"].get("action", ""), "actor": e["actor"],
               "payload": e.get("payload", {})}
              for e in al.recent(8)]
    kb_docs = await asyncio.to_thread(doc_count)  # Milvus 冷启动可能慢，移出事件循环防阻塞全服务
    return {
        "kb_docs": kb_docs, "pending": pending, "resolved": resolved,
        "events_total": len(al.entries), "device": device(),
        "uptime_s": int(time.time() - _START), "version": settings.app_version,
        "env": settings.app_env,
        "compliance": {"auth": True, "phi_redact": True, "audit_append": True,
                       "dual_control": True, "data_local": True, "hybrid_rerank": True},
        "recent": recent,
    }


class AskReq(BaseModel):
    question: str = Field(max_length=20000)
    image: str | None = Field(default=None, max_length=8_000_000)
    # 轮 A2：张数上限收口到 _cap_images 校验器（Field max_length 会先于校验器拦截，
    # 中文 422 文案拿不到）；校验器先查张数再逐张压缩，超大列表无压缩开销
    images: list[str] | None = Field(default=None)  # 多图（优先于 image），单张 ≤6MB（超限自动压缩）
    # 任务3 会话记忆：前端每个对话会话固定一个 uuid（「新建对话」时更换）；
    # 可选——评估脚本（run_eval）与旧客户端不携带 → 无记忆、行为与旧版一致。
    session_id: str | None = Field(default=None, max_length=64)

    @field_validator("images")
    @classmethod
    def _cap_images(cls, v: list[str] | None) -> list[str] | None:
        # 轮 A2「压缩替代拒绝」：入口统一 normalize（长边>2000 缩放 + JPEG 质量递减
        # 重编码至 ≤6MB），压缩后仍 >6MB 才 422；非图/损坏原样返回，交由下游既有丢弃逻辑。
        if v is not None:
            if len(v) > MAX_IMAGES:
                raise ValueError(f"影像图片最多 {MAX_IMAGES} 张，当前 {len(v)} 张")
            from backend.core.img_utils import enforce_data_url, normalize_data_url
            out = []
            for s in v:
                enforce_data_url(s)  # SSRF 收口（终评 F1）：非 data:image/ → 422（拒绝而非改写）
                s2 = normalize_data_url(s)
                if len(s2) > 8_000_000:
                    raise ValueError("单张影像压缩后仍超过 6MB，请压缩后上传")
                out.append(s2)
            return out
        return v


class AskResp(BaseModel):
    answer: str
    confidence: float
    needs_human_review: bool
    sources: list = []
    review_id: str | None = None
    status: str = "ok"
    evidence: list = []
    # agentic 检索改写轨迹 [{attempt,action,old_query,new_query,reason}]：
    # 仅文献 agent 非空（refine 节点逐轮累积），其余 agent 恒为 []（前端据此选择性渲染）
    refine_trace: list = []
    # 任务1：质控结构化缺陷 [{category,level,issue,field,track}]（category 已归一六枚举），
    # 仅 /qc/record 填充，供前端按维度分组渲染与快速过滤；其它 agent 恒为 []
    qc_defects: list = []


class ResolveReq(BaseModel):
    decision: str  # approved | rejected
    note: str = ""


async def _enqueue_if_risk(agent: str, q: str, answer: str, conf: float, review_flag: bool,
                           risk_reason: str, actor: str,
                           images: list[str] | None = None,
                           self_confirm: bool | None = None,
                           resubmit_of: str | None = None,
                           attempt: int = 1,
                           meta: dict | None = None,
                           sources: list | None = None) -> str | None:
    """任务3 全交互留痕：runtime_flags.full_mode()=True（默认开）时**全部 ask 类响应无条件
    入队**并立即自动签发（reviewed_by=「AI·留痕模式(自动)」，影像存档保留 keep_images），
    医生侧不阻塞；full=False 时回落旧语义（仅高危 review_flag 入队 + 人工双控）。
    待确认规则（任务3）：conf<0.6 或高危（review_flag）→ self_confirm_required=True
    （提交医生本人在「我的待确认」补知情确认）；否则纯留痕。显式传 self_confirm=True
    时强制待确认（drug 高危语义）。
    fallback/offtopic 拒答不入队（调用方维持既有分支，不进本函数）。
    sources：响应溯源来源随条目存档。resubmit_of/attempt/meta 透传（任务6）。"""
    await pg_store.mirror_audit(f"{agent}.answer", actor, {"conf": conf, "review": review_flag})
    if current_channel() == "mcp":
        # 批2 任务1 安全边界（docs/archive/MCP.md）：MCP 外部 AI 客户端的调用【不写审核队列】——
        # 结果由外部客户端消费自担（免责声明见 MCP 工具 docstring），避免外部批量调用
        # 污染审核流；审计留痕照常（各端点 query/answered 事件 + channel/tool 标记）。
        # 与 web 流量唯一语义差异：不入队、无 review_id；答案正文/置信度/来源照常返回。
        return None
    full = runtime_flags.full_mode()  # runtime_flags.json 优先（admin 运行时切换即时生效）
    # 本批次任务2（drug 类豁免留痕模式）：FULL 短路（入队即自动签发）只适用于非 drug
    # agent——drug 类无论 QC_AUTO_SIGN_FULL 如何都走既有三档（QC_AUTO_SIGN=on 时中危
    # 规则库提示自动签发、高危恒人工、两开关均 off 时全部人工），保证药剂科始终有
    # drug 高危待核对项（配合 resolve 端点的药剂科一票）。实现上仅令 drug 视角
    # full=False：入队条件回落旧语义（review_flag 才入队）、无「AI·留痕模式(自动)」
    # 短路、无待确认强制（人工路径由药剂科复核，不吃知情确认流程）。
    if agent == "drug":
        # 问题2 注记：两药快查已下线，drug 项不再入队（开药走 prescriptions）。
        # 端点仅为 API 兼容保留；此分支仅存档语义（full=False 豁免留痕模式），行为不变——
        # 正常流量不会到达（前端无 drug 视图入口），遗留历史由 scripts/purge_drug_review_items.py 清理。
        full = False
    if not review_flag and not full:
        return None  # 非留痕模式维持旧语义：仅高危入队
    if self_confirm is True:
        need_self = full  # 显式高危标记：留痕模式下强制待确认
    elif self_confirm is False:
        # drug 显式传 False（中危规则库留痕/信息不足非高危）：待确认仅按 conf<0.6 判，
        # 不吃入队用的 review_flag（旧语义里 review_flag or mid_risk_rule 只决定"入队"）
        need_self = full and conf < 0.6
    else:
        need_self = full and (bool(review_flag) or conf < 0.6)  # 任务3 待确认规则
    rid = await asyncio.to_thread(review.submit, agent=agent, question=q, answer=answer,
                                  confidence=conf, risk_reason=risk_reason, submitted_by=actor,
                                  images=images, sources=sources,
                                  self_confirm_required=need_self,
                                  resubmit_of=resubmit_of, attempt=attempt, meta=meta)
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="review_enqueued",
                            actor=actor, payload={"rid": rid, "reason": risk_reason,
                                                  "trace_mode": full})
    await pg_store.mirror_review(await asyncio.to_thread(review.get, rid))
    if full:
        # 任务3：留痕模式——入队即自动签发（不等人工），全程 AI 留痕；医生侧 AskResp 不变。
        # keep_images=True：签发后保留影像存档（留痕可回溯；人工签发路径仍清空）。
        try:
            item = await asyncio.to_thread(review.resolve, rid, "approved", "AI·留痕模式(自动)",
                                           "QC_AUTO_SIGN_FULL：留痕模式自动签发（全交互留痕）",
                                           True)
            await pg_store.mirror_review(item)
        except review.ReviewError:  # pragma: no cover —— 刚入队必 pending，reviewer 亦非提交人
            pass
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="auto_sign_full",
                                actor=actor, payload={"rid": rid, "reason": risk_reason})
    return rid


@router.get("/health")
async def health():
    return {"ok": True, "service": "MedAssist"}


@router.post("/literature/ask", response_model=AskResp)
async def literature_ask(req: AskReq, user: dict = Depends(require_user)):
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
        await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False, sources=["intent:prefilter"])
    # 任务C：技术领域守门（极保守：命中技术词且零医学信号才拒答）。
    # 拒答路径：固定文案、不检索、不入审核队列、不调 LLM；审计记 offtopic_reject。
    off = offtopic_tech_reply(q)
    if off:
        await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="offtopic_reject",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=off, confidence=0.9, needs_human_review=False, sources=["intent:offtopic"])
    await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="query",
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
        await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="degraded",
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
        await asyncio.to_thread(get_audit_logger().write, event_type="literature",
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


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/literature/stream")
async def literature_stream(req: AskReq, user: dict = Depends(require_user)):
    """SSE 流式：逐节点推进度(retrieve/grade/generate/verify) + 末尾 result。失败发 error 事件。
    任务3：与非流式 ask 同一会话记忆语义（session_id 可选 → 注入/写入/审计一致）。"""
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    chat_sid = (req.session_id or "").strip()[:64]
    memory_ctx, mem_used = ("", 0)
    if chat_sid:
        memory_ctx, mem_used = await asyncio.to_thread(chat_memory.build_context, chat_sid)
    await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="stream",
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
            await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="offtopic_reject",
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
            await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="stream_error",
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
            await asyncio.to_thread(get_audit_logger().write, event_type="literature",
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


# 药物置信度显式映射：max_severity → (conf, risk, review_flag)。
# 取值与旧子串判定（"禁忌"/"高危"/"发现以下相互作用"）完全一致，消除对 answer 文本的子串耦合。
_DRUG_SEV_CONF = {
    "禁忌": (0.90, "药物禁忌", True),
    "高危": (0.86, "高危相互作用", True),
    "中危": (0.80, "中危相互作用", False),
}


@router.get("/drug/dict")
async def drug_dict_list(user: dict = Depends(require_user)):
    """阶段1.5 预留：药品字典管理 list 接口（全量字典+规则+免责声明）。
    阶段1.2：管理页数据源（只读），增删改端点随阶段1.5 落地（invalidate 钩子已就绪）。"""
    from backend.core import drug_dict as dd
    d = await asyncio.to_thread(dd.load_dict)
    rules = await asyncio.to_thread(dd.load_rules)
    # 评测行动项4：stats 并入审校覆盖率（reviewed/unreviewed/高危子集；既有键不丢）
    stats = await asyncio.to_thread(dd.stats)
    stats.update(await asyncio.to_thread(dd.rules_review_stats))
    return {"disclaimer": dd.DISCLAIMER, "drugs": d, "rules": rules, "stats": stats}


# ---------- admin 药品字典/规则管理（阶段1.5）：add/update/delete 三 action，写透 + 审计 ----------
_DRUG_DICT_ACTIONS = ("add", "update", "delete")
_DRUG_RULE_SEVERITIES = ("高危", "中危")  # 新写入口径：legacy「禁忌」仅存量保留，不再接受


class DrugDictManageReq(BaseModel):
    """字典/规则管理请求体：{action, item}；item 形态随端点不同（药品条目/规则条目）。"""
    action: str
    item: dict


@router.post("/admin/drug/dict")
async def admin_drug_dict_manage(req: DrugDictManageReq,
                                 user: dict = Depends(require_role("pharmacist", "admin"))):
    """药品字典管理（问题3 权限放宽·语义变更：admin → pharmacist+admin）：药学专业数据
    由药剂科维护（与 /admin/drug/rules 同源双角色），admin 保留兜底。
    action=add/update/delete，item 按 name 定位、整体替换。
    变更经 save_dict 写透（JSON 原子写兜底 + PG 全量同步）并刷新进程内缓存
    （save 内部失效别名图，查询热路径无需重启即生效）；审计 admin.drug_dict_changed。
    非法项 422：name 必填；add 重名 / update、delete 目标不存在 / 未知 action 均拒绝。"""
    from backend.core import drug_dict as dd
    action = (req.action or "").strip().lower()
    if action not in _DRUG_DICT_ACTIONS:
        raise HTTPException(422, "action 需为 add/update/delete")
    name = str((req.item or {}).get("name") or "").strip()
    if not name:
        raise HTTPException(422, "name 必填")
    drugs = await asyncio.to_thread(dd.load_dict)
    idx = next((i for i, d in enumerate(drugs)
                if str(d.get("name") or "").strip() == name), -1)
    if action == "add" and idx >= 0:
        raise HTTPException(422, f"药品已存在：{name}")
    if action in ("update", "delete") and idx < 0:
        raise HTTPException(422, f"药品不存在：{name}")
    if action == "delete":
        drugs.pop(idx)
    else:
        item = dict(req.item)  # 拷贝后再写，避免污染请求对象
        # 别名/商品名仅接受字符串列表，非法形态归一为空数组（防下游 alias_map 迭代异常）
        for k in ("aliases", "brand_names"):
            if not (isinstance(item.get(k), list)
                    and all(isinstance(a, str) for a in item[k])):
                item[k] = []
        item["name"] = name
        if idx >= 0:
            drugs[idx] = item
        else:
            drugs.append(item)
    await asyncio.to_thread(dd.save_dict, drugs)
    actor = user.get("username", "admin")
    await asyncio.to_thread(get_audit_logger().write, event_type="admin",
                            action="drug_dict_changed", actor=actor,
                            payload={"target": "dict", "action": action, "name": name})
    await pg_store.mirror_audit("admin.drug_dict_changed", actor,
                                {"target": "dict", "action": action, "name": name})
    return {"ok": True}


@router.post("/admin/drug/rules")
async def admin_drug_rules_manage(req: DrugDictManageReq,
                                  user: dict = Depends(require_role("pharmacist", "admin"))):
    """相互作用规则管理（pharmacist/admin 双角色）：语义同 /admin/drug/dict，item 按
    规范药对（_norm_pair，与输入顺序无关）定位、整体替换。非法项 422：drug_a/drug_b
    必填且不得相同、severity 限枚举 高危|中危。审计 admin.drug_dict_changed（带药对）。"""
    from backend.core import drug_dict as dd
    action = (req.action or "").strip().lower()
    if action not in _DRUG_DICT_ACTIONS:
        raise HTTPException(422, "action 需为 add/update/delete")
    item = req.item or {}
    a = str(item.get("drug_a") or "").strip()
    b = str(item.get("drug_b") or "").strip()
    if not a or not b:
        raise HTTPException(422, "drug_a/drug_b 必填")
    if a == b:
        raise HTTPException(422, "drug_a 与 drug_b 不能为同一药品")
    sev = str(item.get("severity") or "").strip()
    if action != "delete" and sev not in _DRUG_RULE_SEVERITIES:
        raise HTTPException(422, "severity 需为 高危|中危")  # delete 按药对定位，无需 severity
    rules = await asyncio.to_thread(dd.load_rules)
    pair = pg_store.norm_pair(a, b)
    idx = next((i for i, r in enumerate(rules)
                if pg_store.norm_pair(r.get("drug_a"), r.get("drug_b")) == pair), -1)
    if action == "add" and idx >= 0:
        raise HTTPException(422, f"规则已存在：{pair[0]} × {pair[1]}")
    if action in ("update", "delete") and idx < 0:
        raise HTTPException(422, f"规则不存在：{pair[0]} × {pair[1]}")
    if action == "delete":
        rules.pop(idx)
    else:
        # 药对按规范序落盘（与 pg_store UPSERT 键一致）；机制/处置缺省空串，与 _rule_row 语义对齐
        stored = {"drug_a": pair[0], "drug_b": pair[1], "severity": sev,
                  "mechanism": str(item.get("mechanism") or ""),
                  "management": str(item.get("management") or ""),
                  "source": str(item.get("source") or "admin·人工维护").strip()}
        if idx >= 0:
            rules[idx] = stored
        else:
            rules.append(stored)
    await asyncio.to_thread(dd.save_rules, rules)
    actor = user.get("username", "admin")
    await asyncio.to_thread(get_audit_logger().write, event_type="admin",
                            action="drug_dict_changed", actor=actor,
                            payload={"target": "rules", "action": action,
                                     "drug_a": pair[0], "drug_b": pair[1]})
    await pg_store.mirror_audit("admin.drug_dict_changed", actor,
                                {"target": "rules", "action": action,
                                 "drug_a": pair[0], "drug_b": pair[1]})
    return {"ok": True}


class DrugRulesReviewReq(BaseModel):
    """规则审校批量标记请求体（评测行动项4）：keys 为 "drug_a||drug_b" 编码键列表
    （路由层编码，规范药对与输入顺序无关）；decision=approved|unreviewed。"""
    keys: list[str]
    decision: str


@router.post("/admin/drug/rules/review")
async def admin_drug_rules_review(req: DrugRulesReviewReq,
                                  user: dict = Depends(require_role("pharmacist", "admin"))):
    """相互作用规则审校批量标记（评测行动项4，pharmacist/admin 双角色）：
    drug_rules.json 为 AI 初稿，执业药师人工核对后批量标记（实际医学审校由药师人工执行）。
    keys 编码键 → mark_reviewed 写透（JSON 原子写兜底 + PG 全量同步）+ 缓存即时生效；
    reviewed_by 落当前登录名（不信任前端传名）；撤销（unreviewed）移除三审校字段。
    审计 drug_rules.reviewed（count/actor/decision）；返回标记后新 stats（前端统计头刷新）。
    422：keys 空/键格式非法/键不存在（原子拒绝不部分写入）/decision 非枚举。"""
    from backend.core import drug_dict as dd
    decision = (req.decision or "").strip().lower()
    if decision not in dd.REVIEW_DECISIONS:
        raise HTTPException(422, "decision 需为 approved|unreviewed")
    keys = [str(k or "").strip() for k in (req.keys or []) if str(k or "").strip()]
    if not keys:
        raise HTTPException(422, "keys 必填（drug_a||drug_b 编码键列表）")
    actor = user.get("username", "admin")
    try:
        n = await asyncio.to_thread(dd.mark_reviewed, keys, actor, decision)
    except ValueError as e:
        raise HTTPException(422, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="drug_rules",
                            action="reviewed", actor=actor,
                            payload={"count": n, "decision": decision})
    await pg_store.mirror_audit("drug_rules.reviewed", actor,
                                {"count": n, "decision": decision})
    return {"ok": True, "stats": await asyncio.to_thread(dd.rules_review_stats)}


@router.post("/drug/ask", response_model=AskResp)
async def drug_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type="drug", action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    r = drug_check(q)
    ans = r["answer"]
    sev = r.get("max_severity", "无")
    if sev in _DRUG_SEV_CONF:
        conf, risk, review_flag = _DRUG_SEV_CONF[sev]
    elif len(r.get("drugs") or []) >= 2:  # max_severity="无" 且识别 ≥2 药 → 规则库未收录（旧 "未收录" 分支取值）
        conf, risk, review_flag = 0.55, "未知相互作用", False
    else:  # 识别不足 2 药（旧 else 分支取值）
        conf, risk, review_flag = 0.50, "信息不足", False
    # 中危规则库命中决定「入队留痕」（任何模式下都入队）；「AI·阈值自动」签发不受
    # 留痕模式影响——本批次任务2：drug 类豁免留痕模式（FULL 短路不适用，_enqueue_if_risk
    # 内已令 drug 视角 full=False），高危/禁忌恒人工（药剂科一票），中危规则库提示
    # 自动签发保持既有三档语义。
    mid_risk_rule = (settings.qc_auto_sign and sev == "中危" and bool(r.get("sources"))
                     and str(r["sources"][0]).startswith("drug_rules:"))
    auto_sign = mid_risk_rule
    # self_confirm=review_flag：本批次任务2 后 drug 豁免留痕模式（_enqueue_if_risk 内
    # drug 视角 full 恒为 False），该标记不再产生知情确认强制（高危走人工三档，由药剂科
    # 复核，不吃「我的待确认」流程）；传参保留以维持函数契约（非留痕语义下无害）。
    rid = await _enqueue_if_risk("drug", q, ans, conf, review_flag or mid_risk_rule, risk, actor,
                                 self_confirm=review_flag, sources=r["sources"])
    if auto_sign and rid:
        # QC_AUTO_SIGN：中危规则库提示自动签发（AI 预审替代人工，全程留痕）；
        # 高危/禁忌（sev 高危/禁忌）不入此分支，仍走人工双控。医生侧 AskResp 不受影响。
        try:
            item = await asyncio.to_thread(review.resolve, rid, "approved",
                                           "AI·阈值自动(规则库v2)", "QC_AUTO_SIGN：中危规则库提示自动签发")
            await pg_store.mirror_review(item)
        except review.ReviewError:  # pragma: no cover —— 刚入队必 pending，reviewer 亦非提交人
            pass
        await asyncio.to_thread(get_audit_logger().write, event_type="drug", action="auto_sign",
                                actor=actor, payload={"rid": rid, "sev": sev})
    await asyncio.to_thread(get_audit_logger().write, event_type="drug", action="answered",
                            actor=actor, payload={"conf": conf, "review": review_flag})
    return AskResp(answer=ans, confidence=conf, needs_human_review=review_flag,
                   sources=r["sources"], review_id=rid)


@router.get("/drug/my-rejections")
async def drug_my_rejections(user: dict = Depends(require_user)):
    """阶段0.2：当前用户的「药物助手曾被药剂科驳回」轻量数据（require_user，只返回自己的）。

    过渡实现：drug 类驳回从「我的质控驳回」卡片分流后，改在药物助手视图置顶提示
    「曾被药剂科驳回：{问题}」（含驳回原因），帮助医生回顾未通过的联用查询。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(review.my_drug_rejections, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="drug", action="my_rejections",
                            actor=me, payload={"n": len(items)})
    return {"items": items, "me": me}


# ---------- 阶段2.2：智能开药 suggest（字典约束 LLM 推荐 + 相互作用硬校验） ----------

class PrescribeSuggestReq(BaseModel):
    """开药建议请求：病例摘要 20-5000 字（pydantic 校验，越界 422）。"""
    case_text: str = Field(min_length=20, max_length=5000)


def _extract_llm_json_list(raw) -> list:
    """LLM 响应 → list（剥 markdown 代码围栏后取首个 [...] 子串；解析失败返回 []，
    绝不让格式抖动拖垮端点）。"""
    text = raw.strip() if isinstance(raw, str) else str(raw or "")
    if text.startswith("```"):  # 剥 ```json ... ``` 围栏
        text = text.strip("`").strip()
        if "\n" in text:
            text = text.split("\n", 1)[1]
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        parsed = json.loads(text[start:end + 1])
    except Exception:  # noqa: BLE001 —— JSON 损坏降级空清单
        return []
    return parsed if isinstance(parsed, list) else []


@router.post("/prescriptions/suggest")
async def prescription_suggest(req: PrescribeSuggestReq,
                               user: dict = Depends(require_role("doctor"))):
    """智能开药建议（阶段2.2）：病例摘要 → 字典约束 LLM 推荐 + 相互作用硬校验。

    流程：药品字典候选池（规范名+类别）注入 prompt 强制 LLM 只能从清单选药 →
    get_llm("medical_literature") 输出 JSON [{name,dose,freq,reason}] 3-6 种 →
    硬校验：药名经别名图规范化后不在字典即丢弃并告警（幻觉=0，绝不透出）→
    推荐组合两两查 drug_rules：高危 → blocked=true + conflicts（不落库，需医生自行规避）；
    中危 → warnings 附响应提示。全程审计 prescription.suggested；响应携带 DISCLAIMER。
    """
    from backend.core import drug_dict as dd
    actor = user.get("username", "system")
    case_text = req.case_text.strip()
    # 候选池：规范名+类别（LLM 只能从清单选药；空字典兜底空清单 → 全部丢弃）
    drugs = await asyncio.to_thread(dd.load_dict)
    pool = "\n".join(f"- {d.get('name')}（{d.get('category') or '其他'}）"
                     for d in drugs if d.get("name"))
    system = ("你是临床药物推荐助手。你只能从下方候选药品清单中选择药物，"
              "严禁使用清单之外的任何药品名称（不得编造、不得使用别名变体）。\n"
              "只输出一个 JSON 数组，不要任何解释或 markdown 代码块。数组每项形如 "
              '{"name":"清单内规范名","dose":"剂量","freq":"频次","reason":"推荐理由"}，'
              "推荐 3-6 种。\n\n候选药品清单：\n" + (pool or "（空）"))
    llm = get_llm("medical_literature")
    try:
        resp = await asyncio.to_thread(
            llm.invoke, [SystemMessage(content=system), HumanMessage(content=case_text)])
    except Exception as e:  # noqa: BLE001 —— LLM 故障降级空推荐，绝不冒泡 500
        logger.warning("prescription.suggest_llm_failed", error=str(e)[:150])
        resp = None
    content = getattr(resp, "content", "") or ""
    if isinstance(content, list):  # 兼容分块 content（取文本块拼接）
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    # 硬校验：别名图归一后不在字典 → 丢弃 + 告警（幻觉=0）
    amap = await asyncio.to_thread(dd.alias_map)
    suggestions: list[dict] = []
    dropped: list[str] = []
    for s in _extract_llm_json_list(content):
        if not isinstance(s, dict):
            continue
        name = str(s.get("name") or "").strip()
        norm = amap.get(name)
        if not norm:
            if name:
                dropped.append(name)
                logger.warning("prescription.suggest_hallucination_dropped", name=name)
            continue
        suggestions.append({"name": norm, "dose": str(s.get("dose") or "").strip(),
                            "freq": str(s.get("freq") or "").strip(),
                            "reason": str(s.get("reason") or "").strip()})
    # 组合两两查相互作用规则：高危阻断（不落库）、中危提示
    rulemap = await asyncio.to_thread(dd.rule_map)
    conflicts: list[dict] = []
    warnings: list[dict] = []
    names = [s["name"] for s in suggestions]
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            rule = rulemap.get(frozenset((names[i], names[j])))
            if not rule:
                continue
            sev, mech, mgmt = rule
            entry = {"pair": [names[i], names[j]], "severity": sev,
                     "note": mech + (f"；{mgmt}" if mgmt else "")}
            if sev in ("高危", "禁忌"):
                conflicts.append(entry)
            else:
                warnings.append(entry)
    blocked = bool(conflicts)
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                            action="suggested", actor=actor,
                            payload={"q": case_text[:AUDIT_Q_SNIPPET], "n": len(suggestions),
                                     "blocked": blocked, "dropped": dropped})
    await pg_store.mirror_audit("prescription.suggested", actor,
                                {"q": case_text[:AUDIT_Q_SNIPPET], "n": len(suggestions),
                                 "blocked": blocked, "dropped": dropped})
    return {"suggestions": suggestions, "blocked": blocked, "conflicts": conflicts,
            "warnings": warnings, "disclaimer": dd.DISCLAIMER}


# ---------- 阶段2.3：处方流转（提交 / 我的处方 / 药剂科待审 / 审核双控 / 改写重提） ----------

class PrescriptionSubmitReq(BaseModel):
    """医生提交开药请求：病例摘要 20-5000 字 + 至少一种药品（pydantic 越界 → 422）。"""
    case_text: str = Field(min_length=20, max_length=5000)
    drugs: list[dict] = Field(min_length=1)
    contraindication_reason: str = ""


class PrescriptionReviewReq(BaseModel):
    """药剂科审核请求：action 仅 approve|reject（Literal 约束，非法值 422）；
    opinion 驳回时必填（端点层校验 → 422）。"""
    action: Literal["approve", "reject"]
    opinion: str = ""


class PrescriptionRewriteReq(BaseModel):
    """被驳回处方改写请求：字段均可选（None=沿用原值）；case_text 越界由 pydantic 422。"""
    case_text: str | None = Field(default=None, min_length=20, max_length=5000)
    drugs: list[dict] | None = None
    contraindication_reason: str | None = None


def _hard_validate_drugs(drugs: list, contraindication_reason: str) -> tuple[list[dict], list[dict], bool, list[str]]:
    """提交前服务端规则硬校验（不信任前端；提交与改写重提共用）。
    整改轮 B 任务2 语义变更（字典外药方案 A，用户拍板）：
    - 字典外药**不再 422 拒绝**——真实临床字典不会穷尽所有药，人工手动添加允许外典；
      该药条目标记 out_of_dict=true，且**必须带 note（≥5 字理由，说明何药/为何用）**，
      否则 ValueError（端点层转 422）：「字典外药品「X」需填写使用理由（≥5字），
      将交药剂科重点审核」。suggest 端点的幻觉丢弃**保持不变**（LLM 推荐仍只准字典内，
      人与 AI 的区别：人工可外典留痕，AI 绝不幻觉）。
    - 其余不变：药名经别名图归一；组合两两查相互作用规则：高危/禁忌命中 → 强制提交
      必须附 contraindication_reason（缺失 ValueError → 422），有理由放行并标记 forced。
    返回 (归一化药品清单, 高危冲突列表, forced 标记, 字典外药名清单)。"""
    from backend.core import drug_dict as dd
    amap = dd.alias_map()
    cleaned: list[dict] = []
    out_of_dict: list[str] = []
    for d in drugs or []:
        if not isinstance(d, dict):
            continue
        raw = str(d.get("name") or "").strip()
        note = str(d.get("note") or "").strip()
        name = amap.get(raw)
        if not name:
            # 方案A：外典药放行但强制留痕理由（≥5 字），标记交药剂科重点审核
            if len(note) < 5:
                raise ValueError(f"字典外药品「{raw or '空'}」需填写使用理由（≥5字），将交药剂科重点审核")
            name = raw
            out_of_dict.append(name)
        entry = {"name": name, "dose": str(d.get("dose") or "").strip(),
                 "freq": str(d.get("freq") or "").strip(), "note": note}
        if name in out_of_dict:  # 仅外典药带条目级标记（域层据此透传/重算处方级标记）
            entry["out_of_dict"] = True
        cleaned.append(entry)
    if not cleaned:
        raise ValueError("至少需一种字典内有效药品（name 必填）")
    rulemap = dd.rule_map()
    conflicts: list[dict] = []
    for i in range(len(cleaned)):
        for j in range(i + 1, len(cleaned)):
            rule = rulemap.get(frozenset((cleaned[i]["name"], cleaned[j]["name"])))
            if not rule:
                continue
            sev, mech, mgmt = rule
            if sev in ("高危", "禁忌"):
                conflicts.append({"pair": [cleaned[i]["name"], cleaned[j]["name"]],
                                  "severity": sev, "note": mech + (f"；{mgmt}" if mgmt else "")})
    forced = bool(conflicts)
    if forced and not (contraindication_reason or "").strip():
        pair = "、".join(" × ".join(c["pair"]) for c in conflicts)
        raise ValueError(f"存在高危相互作用组合（{pair}）："
                         "强制提交必须填写禁忌理由 contraindication_reason")
    return cleaned, conflicts, forced, out_of_dict


def _rx_audit(user: dict, **extra) -> dict:
    """处方审计公共 payload：角色 + 入口渠道（web 端默认 "web"）+ 调用方附加字段（rid/n/...）。"""
    p = {"role": user.get("role", ""), "channel": current_channel() or "web"}
    p.update(extra)
    return p


@router.post("/prescriptions")
async def prescription_create(req: PrescriptionSubmitReq,
                              user: dict = Depends(require_role("doctor"))):
    """医生提交开药（阶段2.3）：服务端规则硬校验（不信任前端）→ 域层 create 直送药剂科
    （→ pending_pharm）。高危/禁忌组合强制提交时 contraindication_reason 必填（缺失 422）；
    整改轮 B 任务2（方案A 语义变更）：字典外药允许提交但必须带 ≥5 字使用理由（缺失 422），
    处方标记 out_of_dict 供药剂科重点审核；审计 prescription.submitted payload 增
    out_of_dict_drugs=[名]。"""
    me = user.get("username", "system")
    try:
        cleaned, conflicts, forced, ood = _hard_validate_drugs(req.drugs, req.contraindication_reason)
    except ValueError as e:  # 硬校验不过 → 422（请求内容不合规）
        raise HTTPException(422, str(e))
    try:
        item = await asyncio.to_thread(rx.create, doctor=me, dept=user.get("dept", ""),
                                       case_text=req.case_text.strip(), drugs=cleaned,
                                       contraindication_reason=req.contraindication_reason.strip(),
                                       forced_high_risk=forced)
    except ValueError as e:  # 域层兜底（pydantic 已拦大半）→ 400 业务错
        raise HTTPException(400, str(e))
    payload = _rx_audit(user, rid=item["id"], n=len(cleaned),
                        forced_high_risk=forced, forced_with_reason=forced,
                        out_of_dict_drugs=ood)  # 方案A：外典药名单随审计留痕
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                            action="submitted", actor=me, payload=payload)
    await pg_store.mirror_audit("prescription.submitted", me, payload)
    if forced:  # 高危强制开立单独留痕（审计矩阵 forced_with_reason 事件）
        fp = _rx_audit(user, rid=item["id"],
                       reason=req.contraindication_reason.strip()[:AUDIT_Q_SNIPPET])
        await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                                action="forced_with_reason", actor=me, payload=fp)
        await pg_store.mirror_audit("prescription.forced_with_reason", me, fp)
    return item


@router.get("/prescriptions/mine")
async def prescription_mine(user: dict = Depends(require_role("doctor"))):
    """我的处方列表（新→旧；服务端按登录医生过滤，只回本人数据）。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_mine, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription", action="list",
                            actor=me, payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.get("/prescriptions/reviewed-by-me")
async def prescription_reviewed_by_me(user: dict = Depends(require_role("pharmacist"))):
    """问题3②：本人（药剂科）审核过的处方清单（pharm_reviewer=me，approved/rejected，新→旧）。

    背景：pharmacist 驳回/签发的处方此前不在 /review/history 体系（处方与 review 队列
    双轨），审核中心「我的历史」看不到——本端点供前端「我的历史」tab 合并渲染处方
    审核记录（rid/状态/意见/时间）。仅 pharmacist（admin 走全局档案，doctor/qc 403）。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_reviewed_by, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                            action="list_reviewed_by_me", actor=me,
                            payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.get("/prescriptions/pending")
async def prescription_pending(user: dict = Depends(require_role("pharmacist", "admin"))):
    """药剂科待审队列（pending_pharm，新→旧；pharmacist/admin 可见，doctor/qc 403）。
    问题4 收窄（语义变更）：原 pharmacist/qc/admin——处方审核权=药剂科+管理员，
    质控科（qc）只管病例质控，不再读取开药待审队列。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_pending)
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                            action="list_pending", actor=me, payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.post("/prescriptions/{rid}/review")
async def prescription_review(rid: str, body: PrescriptionReviewReq,
                              user: dict = Depends(require_role("pharmacist", "admin"))):
    """药剂科签发/驳回（approve|reject）。双控：审核人≠提交医生（本人提交 → 403）；
    驳回意见必填（缺失 422）；非 pending_pharm 流转由域层状态机拒绝（400）。
    审计 prescription.approved / prescription.rejected（actor + opinion + 药数）。
    问题4 收窄（语义变更）：原 pharmacist/qc/admin——处方审核权=药剂科（pharmacist）
    +管理员，质控科（qc）只管病例质控。"""
    me = user.get("username", "system")
    cur = await asyncio.to_thread(rx.get, rid)
    if cur is None:
        raise HTTPException(404, "处方不存在")
    if cur.get("doctor") == me:
        raise HTTPException(403, "双控约束：审核人不能是提交医生本人")
    if body.action == "reject" and not body.opinion.strip():
        raise HTTPException(422, "驳回必须填写审核意见（opinion）")
    payload = _rx_audit(user, rid=rid, n=len(cur.get("drugs") or []),
                        opinion=body.opinion.strip()[:AUDIT_Q_SNIPPET])
    try:
        if body.action == "approve":
            item = await asyncio.to_thread(rx.approve, rid, me, body.opinion)
            await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                                    action="approved", actor=me, payload=payload)
            await pg_store.mirror_audit("prescription.approved", me, payload)
        else:
            item = await asyncio.to_thread(rx.reject, rid, me, body.opinion)
            await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                                    action="rejected", actor=me, payload=payload)
            await pg_store.mirror_audit("prescription.rejected", me, payload)
    except ValueError as e:  # 状态机拒绝（draft/approved/rejected 再签发等）→ 400
        raise HTTPException(400, str(e))
    return item


@router.post("/prescriptions/{rid}/rewrite")
async def prescription_rewrite(rid: str, body: PrescriptionRewriteReq,
                               user: dict = Depends(require_role("doctor"))):
    """医生改写被驳回处方（仅本人）：更新 case_text/drugs/理由（缺省沿用原值）→ 服务端硬校验 →
    rejected→draft→pending_pharm 一次完成重提。校验不过保持 rejected（422，医生修正后可重试）。
    整改轮 B 任务2（方案A 语义变更）：改写药单同样适用字典外药规则（外典药必须带 ≥5 字
    使用理由）；out_of_dict 标记随改写重算。审计 prescription.rewritten。"""
    me = user.get("username", "system")
    cur = await asyncio.to_thread(rx.get, rid)
    if cur is None:
        raise HTTPException(404, "处方不存在")
    if cur.get("doctor") != me:
        raise HTTPException(403, "仅处方医生本人可改写")
    if cur.get("status") != "rejected":
        raise HTTPException(400, "仅被驳回（rejected）的处方可改写")
    eff_case = body.case_text if body.case_text is not None else cur["case_text"]
    eff_drugs = body.drugs if body.drugs is not None else cur["drugs"]
    eff_reason = (body.contraindication_reason if body.contraindication_reason is not None
                  else (cur.get("contraindication_reason") or ""))
    try:
        cleaned, conflicts, forced, _ood = _hard_validate_drugs(eff_drugs, eff_reason)
    except ValueError as e:  # 校验不过 → 422，处方保持 rejected 供继续修正（不产生死态）
        raise HTTPException(422, str(e))
    try:
        await asyncio.to_thread(rx.rewrite, rid, me, eff_case, cleaned, eff_reason)
        item = await asyncio.to_thread(rx.submit, rid, me)
    except ValueError as e:  # 域层兜底（本人/状态已前置校验，理论不可达）→ 400
        raise HTTPException(400, str(e))
    payload = _rx_audit(user, rid=rid, n=len(cleaned), forced_high_risk=forced)
    await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                            action="rewritten", actor=me, payload=payload)
    await pg_store.mirror_audit("prescription.rewritten", me, payload)
    if forced:  # 改写后仍高危强制开立 → 单独留痕
        fp = _rx_audit(user, rid=rid, reason=eff_reason.strip()[:AUDIT_Q_SNIPPET])
        await asyncio.to_thread(get_audit_logger().write, event_type="prescription",
                                action="forced_with_reason", actor=me, payload=fp)
        await pg_store.mirror_audit("prescription.forced_with_reason", me, fp)
    return item


async def _ask_prefilter(agent: str, q: str, actor: str) -> AskResp | None:
    """imaging/case 共用预检（任务C 意图前置）：命中闲聊/身份 → 审计 prefilter 并返回
    免 LLM 固定兜底响应；未命中返回 None（继续走端点主流程）。"""
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    return None


async def _vl_describe_flow(agent: str, imgs: list[str], q: str, actor: str,
                            question: str, system_prompt: str | None = None,
                            fail_action: str = "describe_failed",
                            fail_msg: str = "影像描述服务暂时不可用，请稍后重试",
                            conf_ok: float = 0.72,
                            risk_reason: str = "影像可疑高危征象",
                            suffix_risky: str = "（已标记为需放射科医师双人核对）",
                            suffix_ok: str = "\n\n以上为 AI 初步所见，供医师参考。",
                            audit_truncation: bool = True) -> AskResp:
    """imaging/case 共用 VL 描述流程（外部审查重构：两端点此前为近乎复制的
    「describe→空响应兜底→截断审计→入队→vl_described 留痕」流程）。

    两端点各自语义经参数化保留（行为完全等价）：
    - question/system_prompt：imaging 传原问题 + 默认放射科 prompt；case 传病例前缀 +
      CASE_PROMPT（F1 人设分离）；
    - fail_action/fail_msg：describe_failed vs vl_failed 及各自 500 文案；
    - conf_ok（非高危置信 0.72 vs 0.70）、risk_reason、响应 suffix；
    - audit_truncation：vl_truncated 审计此前仅 imaging 记录（case 保持现状不记），
      显式参数区分，不静默改变任一端点行为。
    """
    try:
        from backend.core.medical_imaging import describe_images_detailed, vl_model_name
        # 诊断1：空响应自动重试 1 次（内部换温度），并带回 vl_raw_len/stop_reason 诊断线索
        d = await describe_images_detailed(imgs, question, system_prompt=system_prompt)
        desc = d["text"]
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action=fail_action,
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(500, fail_msg)
    desc = (desc or "").strip()
    # 任务3 空响应兜底：VL 空内容（重试后仍空）→ 明确提示重试，不入队不留档
    # （与 fallback/offtopic 拒答同纪律），审计记 vl_empty + 诊断1
    # vl_raw_len/stop_reason（原始响应长度/停止原因，为换稳定 vision 模型留线索）。
    if not desc:
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_empty",
                                actor=actor, payload={"imgs": len(imgs),
                                                      "vl_raw_len": d["vl_raw_len"],
                                                      "stop_reason": d["stop_reason"]})
        return AskResp(answer="AI 未生成所见，请重新上传或补充描述。",
                       confidence=0.0, needs_human_review=False,
                       sources=[f"{agent}:vl_empty"], review_id=None, status="vl_empty")
    risky = _has_risk(desc)
    conf = 0.60 if risky else conf_ok
    src = ["VL:" + vl_model_name()]
    # 任务4：finish_reason=length（max_tokens 上限截断）→ 审计记 vl_truncated
    # （文案提示由 medical_imaging 统一追加"（输出因长度截断，已按最大长度返回）"）。
    if audit_truncation and (d.get("stop_reason") or "").strip().lower() in (
            "length", "max_tokens", "max_output_tokens"):
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_truncated",
                                actor=actor, payload={"vl_raw_len": d["vl_raw_len"],
                                                      "stop_reason": d["stop_reason"]})
    rid = await _enqueue_if_risk(agent, q, desc, conf, risky, risk_reason, actor,
                                 images=imgs, sources=src)
    suffix = suffix_risky if risky else suffix_ok
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_described",
                            actor=actor, payload={"len": len(desc), "risky": risky})
    return AskResp(answer=desc + suffix, confidence=conf, needs_human_review=risky,
                   sources=src, review_id=rid)


@router.post("/imaging/ask", response_model=AskResp)
async def imaging_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = await _ask_prefilter("imaging", q, actor)
    if pre:
        return pre
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        return await _vl_describe_flow("imaging", imgs, q, actor, question=q)
    await asyncio.to_thread(get_audit_logger().write, event_type="imaging", action="need_image",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
    return AskResp(answer="影像辅助阅片：请上传 DICOM/JPG/PNG 影像，我将给出结构化初步所见。",
                   confidence=0.0, needs_human_review=False, sources=["imaging:needs_image"],
                   status="need_image")


@router.post("/case/ask", response_model=AskResp)
async def case_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = await _ask_prefilter("case", q, actor)
    if pre:
        return pre
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        from backend.core.medical_imaging import CASE_PROMPT
        # F1：病例总结用病例专用 prompt，不再带放射科阅片人设（差异点经参数化传入共用流程）
        return await _vl_describe_flow(
            "case", imgs, q, actor,
            question="结合病例信息描述关键发现：" + q, system_prompt=CASE_PROMPT,
            fail_action="vl_failed", fail_msg="多模态解析服务暂时不可用，请稍后重试",
            conf_ok=0.70, risk_reason="病例含高危征象",
            suffix_risky="\n\n（已标记双人核对）", suffix_ok="\n\n供医师参考的初步摘要。",
            audit_truncation=False)
    await asyncio.to_thread(get_audit_logger().write, event_type="case", action="scaffold",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
    return AskResp(answer="多模态病例总结：粘贴病例要点（可附检验/影像图片），我生成结构化摘要与待鉴别清单。",
                   confidence=0.0, needs_human_review=False, sources=["case:needs_input"],
                   status="need_input")


class MdtReq(BaseModel):
    case: str = Field(max_length=20000)


@router.post("/mdt/consult")
async def mdt_consult(req: MdtReq, user: dict = Depends(require_user)):
    case = _redactor.redact(req.case)
    actor = user.get("username", "system")
    await asyncio.to_thread(get_audit_logger().write, event_type="mdt", action="query",
                            actor=actor, payload={"len": len(case)})
    try:
        g = _get_mdt_graph()
        sid = "mdt-" + uuid.uuid4().hex[:12]
        out = await g.ainvoke({"session_id": sid, "case": case, "opinions": []},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(get_audit_logger().write, event_type="mdt", action="degraded",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(500, "会诊服务暂时不可用（多为瞬时），已记录并标记需人工复核；请稍后重试")
    rep = out["report"]
    # 任务3：MDT 全交互留痕首次接入——报告全文/置信/来源无条件入队（留痕模式自动签发）
    rid = await _enqueue_if_risk("mdt", case, rep["text"], rep["confidence"],
                           rep.get("needs_human_review", False), "MDT存在分歧或高危", actor,
                           sources=rep.get("sources", []))
    return {"report": rep["text"], "confidence": rep["confidence"], "opinions": rep["opinions"],
            "disagreements": rep["disagreements"], "needs_human_review": rep["needs_human_review"],
            "sources": rep["sources"], "review_id": rid,
            "headline": rep.get("headline", ""), "urgency": rep.get("urgency", "中"),
            "key_actions": rep.get("key_actions", [])}


# ---------- 跨科室会诊协助（真实会诊流转：Agent 动态组队 → 分发 → 各科意见 → 结束汇总） ----------
# 与 /mdt/consult（AI 多智能体模拟会诊）的区别：真实流转到目标科室的在职医生
# （users.dept 匹配），各科医生在审核中心「其它科室会诊协助」填意见（仅建议权，每人一票）。

class ConsultOpinionReq(BaseModel):
    content: str = Field(min_length=1, max_length=5000)


@router.post("/consults")
async def consult_create(req: AskReq, user: dict = Depends(require_user)):
    """发起跨科室会诊：Agent 决策点（LLM 读病例+实时科室清单自主选专科）→ 按科室生成
    定向初步意见 → 分发给目标科室全部在职医生。images 复用 AskReq 既有图片白名单
    （≤6 张、单张 ≤6MB）。审计：consult_created + consult_dispatch（Agent 组队留痕）。"""
    from backend.agents.medical.mdt import dept_briefs, pick_departments
    from backend.core import consults
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    # 诊断4 发起预检：问题过短（<8 字）或命中技术领域守门（无医学信号）→ 不建单不分发，
    # 400 引导补充病例——防「你好」类闲聊/占位输入空跑组队 LLM 并误分发给真实医生。
    if len(q.strip()) < 8 or offtopic_tech_reply(q):
        await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="prefilter_reject",
                                actor=actor, payload={"len": len(q), "role": user.get("role", "")})
        raise HTTPException(400, "请补充患者病史/症状等病例信息后再发起会诊")
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="query",
                            actor=actor, payload={"len": len(q), "role": user.get("role", "")})
    try:
        # 轮 B1（MDT 传图）：带图时组队/定向意见节点自动切 VL 模型（读影像判断专科/引用所见）
        team = await pick_departments(q, req.images)  # Agent 动态组队（实时科室清单，绝不缓存）
        briefs = await dept_briefs(q, team["departments"], req.images)  # 按科室定向初步意见
    except consults.ConsultError as e:
        # 诊断4：组队兜底不再全量召集（宁可失败也不乱分发）→ 400 明确引导补充病例
        await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="team_failed",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(400, str(e))
    except Exception as e:  # noqa: BLE001 —— 组队链路异常体面降级，不裸 500
        await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="degraded",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(502, "会诊组队服务暂时不可用（多为瞬时），已记录审计；请稍后重试")
    ai_analysis = "\n\n".join(f"【{dep}】{txt}" for dep, txt in briefs.items())
    cid = await asyncio.to_thread(
        consults.create, initiator=actor, question=q, ai_analysis=ai_analysis,
        target_depts=team["departments"], team=team, images=req.images,
        initiator_dept=user.get("dept", ""))
    item = await asyncio.to_thread(consults.get, cid)
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="consult_created",
                            actor=actor, payload={"cid": cid, "role": user.get("role", ""),
                                                  "depts": team["departments"],
                                                  # 轮 B1：随单入库影像张数（images_count 审计留痕）
                                                  "images_count": len(item.get("images") or [])})
    # Agent 决策点审计：组队结果（选中科室+理由）留痕，可回溯「为什么召集这些科室」
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="consult_dispatch",
                            actor=actor, payload={"cid": cid, "role": user.get("role", ""),
                                                  "departments": team["departments"],
                                                  "reasoning": team.get("reasoning", "")})
    await pg_store.mirror_audit("consult.created", actor, {"cid": cid})
    return item


@router.get("/consults/inbox")
async def consult_inbox(silent: bool = False, user: dict = Depends(require_user)):
    """「其它科室会诊协助」收件箱：当前医生 dept 匹配的 open 会诊单
    （服务端按 dept 实时过滤隔离；账号未设置科室则恒为空）。
    silent=true 供前端角标轮询（读操作降噪）：跳过 list_inbox 审计写入，
    数据返回与非 silent 完全一致；视图内刷新不传 silent，业务审计照旧保留。"""
    from backend.core import consults
    me = user.get("username", "system")
    dept = user.get("dept", "")
    items = await asyncio.to_thread(consults.inbox_for, dept) if dept else []
    if not silent:
        await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="list_inbox",
                                actor=me, payload={"n": len(items), "dept": dept,
                                                   "role": user.get("role", "")})
    return {"items": items, "me": me, "dept": dept}


@router.get("/consults/mine")
async def consult_mine(user: dict = Depends(require_user)):
    """发起者视角：我发起的全部会诊（含已结束）+ 我参与过的（作为受邀科室填过意见）。
    数据隔离由服务端过滤保证。"""
    from backend.core import consults
    me = user.get("username", "system")
    initiated = await asyncio.to_thread(consults.by_initiator, me)
    participated = await asyncio.to_thread(consults.participated, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="list_mine",
                            actor=me, payload={"initiated": len(initiated),
                                               "participated": len(participated),
                                               "role": user.get("role", "")})
    return {"initiated": initiated, "participated": participated, "me": me}


@router.post("/consults/{cid}/opinion")
async def consult_opinion(cid: str, body: ConsultOpinionReq, user: dict = Depends(require_user)):
    """被分发科室医生填写本科室意见（仅建议权无驳回权，每人一票）。
    非受邀科室/未设置科室 → 403；重复填写/会诊已结束/内容为空 → 400；未找到 → 404。
    发起者若在自己科室受邀名单内同样可填（正常参与）。审计：consult_opinion（带 role）。"""
    from backend.core import consults
    me = user.get("username", "system")
    try:
        item = await asyncio.to_thread(consults.add_opinion, cid, me,
                                       user.get("dept", ""), body.content)
    except consults.ConsultNotFoundError as e:
        raise HTTPException(404, str(e))
    except consults.ConsultPermissionError as e:
        raise HTTPException(403, str(e))
    except consults.ConsultError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="consult_opinion",
                            actor=me, payload={"cid": cid, "dept": user.get("dept", ""),
                                               "role": user.get("role", "")})
    await pg_store.mirror_audit("consult.opinion", me, {"cid": cid})
    return item


@router.post("/consults/{cid}/close")
async def consult_close(cid: str, user: dict = Depends(require_user)):
    """仅发起者可结束会诊并汇总：AI 意见 + 各科医生意见并排署名，汇总同步回发起者的
    会诊记录（summary 字段）。非发起者 → 403；已结束 → 400；未找到 → 404。
    审计：consult_closed（带 role）。"""
    from backend.core import consults
    me = user.get("username", "system")
    try:
        item = await asyncio.to_thread(consults.close, cid, me)
    except consults.ConsultNotFoundError as e:
        raise HTTPException(404, str(e))
    except consults.ConsultPermissionError as e:
        raise HTTPException(403, str(e))
    except consults.ConsultError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="consult", action="consult_closed",
                            actor=me, payload={"cid": cid, "role": user.get("role", ""),
                                               "opinions": len(item.get("opinions") or [])})
    await pg_store.mirror_audit("consult.closed", me, {"cid": cid})
    return item


# ---------- 审核中心（高危双人核对）----------
# 任务2+3 审核中心转型：GET 列表（pending/history）保持多角色可见（qc/admin 全局档案 +
# doctor/pharmacist 前端按「我的记录」过滤展示）；签发/驳回/翻案等**复核操作**收窄为
# qc/admin（行为变更：原 doctor/pharmacist 可签发，现由质控员/管理员统一行使签字权）。
# 本批次任务2 药剂科一票：drug 类签发/驳回放宽到 pharmacist（药物高危结论需药剂科复核，
# 且 drug 豁免留痕模式——高危恒在 pending，药剂科任何一人可签发/驳回）；翻案仍仅 qc/admin。
# 问题4 收窄（用户拍板）：drug 待核对签字权与处方审核权 = **pharmacist+admin**——质控科
# （qc）只管病例质控，不再参与药物核对与处方审核（_DRUG_RESOLVE_ROLES 移除 qc、
# /prescriptions/* 审核端点移除 qc）；qc 仍可读 review/pending 处理其它助手高危项，
# 且响应对 qc 剔除 agent=drug（qc 界面彻底不见 drug 项，含旧遗留；pharmacist/admin 不变）。
_REVIEW_ROLES = ("qc", "admin", "doctor", "pharmacist")
_RESOLVE_ROLES = ("qc", "admin")                       # 非 drug 类签字权（含翻案）
_DRUG_RESOLVE_ROLES = ("admin", "pharmacist")    # drug 类签字权（问题4 收窄：药剂科一票+管理员，qc 移除）


def _review_image_view(item: dict, role: str, username: str) -> dict:
    """审核中心图片可见性统一收窄（/review/pending 与 /review/history 两条路径共用）。

    规则：图片返回给 = **提交人本人 + admin + 该项审核所需角色**——
    agent 非 drug 项（imaging/qc 等）→ qc+admin（qc 核对影像是审核依据，需要看图）；
    agent=drug → pharmacist+admin；doctor 仅本人提交的（患者数据隐私边界保留，
    他人条目一律剥 images，不因角色放宽暴露面）。"""
    if item.get("submitted_by") == username or role == "admin":
        return dict(item)
    drug = item.get("agent") == "drug"
    if (role == "qc" and not drug) or (role == "pharmacist" and drug):
        return dict(item)  # 该项审核所需角色：看图核对
    return {k: v for k, v in item.items() if k != "images"}


@router.get("/review/pending")
async def review_pending(user: dict = Depends(require_role(*_REVIEW_ROLES))):
    items = await asyncio.to_thread(review.pending)
    role = user.get("role", "")
    me = user.get("username", "system")
    if role == "qc":  # 问题4 彻底收窄：qc 界面不见 drug 项（含旧遗留），
        items = [i for i in items if i.get("agent") != "drug"]  # drug 审核权=pharmacist/admin
    # 图片可见性统一规则（与 /review/history 一致）：qc 审非 drug 项可见 images（修复
    # 「qc 看不到待审 imaging 图片」）；doctor 他人待审项剥 images（原全量返回，同步收紧）
    items = [_review_image_view(i, role, me) for i in items]
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="list_pending",
                            actor=user.get("username", "system"),
                            payload={"n": len(items), "role": user.get("role", "")})
    return {"pending": items, "me": user.get("username")}


@router.get("/review/history")
async def review_history(user: dict = Depends(require_role(*_REVIEW_ROLES))):
    """历史列表（任务5 语义更新）：list_all 现按签发方式剥离——仅人工签发记录剥离 images
    （FIND-03a 人工复核后不留原图）；AI·留痕模式(自动) 签发的记录保留 images（用户自己的
    交互留痕，前端历史区缩略图可见）。
    可见性边界（服务端强制，本批修复统一规则）：图片返回给 = 提交人本人 + admin +
    该项审核所需角色（agent 非 drug 项 → qc+admin：qc 审核需要看图；agent=drug →
    pharmacist+admin）——原「非本人一律剥 images」导致 qc 历史区看不到 imaging 影像，
    已修复；doctor 仅本人参与记录可见（隐私边界保留，不扩大暴露面）。"""
    items = await asyncio.to_thread(review.list_all, limit=50)
    me = user.get("username", "system")
    role = user.get("role", "")
    # 问题3修复（实测结论）：doctor/pharmacist 的「我的历史」数据源改为服务端按人检索
    # （submitted_by=me OR reviewed_by=me）——原 list_all(limit=50)
    # 按插入序全局截断，真实队列中 pharmacist 处理的 drug 项被其后大量自动签发条目挤出
    # 最新 50 条窗口，前端按 reviewed_by=me 过滤恒空。qc/admin 全局档案语义不变。
    if role in ("doctor", "pharmacist"):
        items = await asyncio.to_thread(review.list_involved, me)
    # 图片可见性统一规则（与 /review/pending 一致）：qc 审非 drug 项可见 images（修复
    # qc 历史区看不到 imaging 影像）；pharmacist 的 drug 项可见；doctor 他人项剥 images；
    # admin/提交人本人全可见
    items = [_review_image_view(i, role, me) for i in items]
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="history",
                            actor=user.get("username", "system"),
                            payload={"n": len(items), "role": user.get("role", "")})
    return {"items": items}


@router.get("/review/status")
async def review_status(review_id: str | None = None, user: dict = Depends(require_user)):
    """复核队列状态只读查询（MCP review_status 工具的承载端点）。

    不带 review_id → 最近 100 条记录的三态计数（pending/approved/rejected + total）；
    带 review_id → 单条状态元数据（状态/提交人/审核人/风险原因等）。
    安全边界：单条视图刻意剥离 question/answer/images/meta 等临床内容（只给状态元数据，
    需要内容走 /review/pending 或 /review/history）；未找到 → 404。
    """
    rid = (review_id or "").strip()[:64]
    actor = user.get("username", "system")
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="status",
                            actor=actor, payload={"rid": rid or None})
    if rid:
        item = await asyncio.to_thread(review.get, rid)
        if item is None:
            raise HTTPException(404, "复核单不存在")
        keys = ("id", "agent", "status", "confidence", "risk_reason", "submitted_by",
                "reviewed_by", "review_note", "ts", "resolved_at",
                "self_confirm_required", "resubmit_of", "attempt")
        return {k: item.get(k) for k in keys if k in item}
    items = await asyncio.to_thread(review.list_all, with_images=False)
    counts = {"pending": 0, "approved": 0, "rejected": 0}
    for it in items:
        st = it.get("status")
        if st in counts:
            counts[st] += 1
    return {"total": len(items), **counts}


@router.get("/review/my-pending-confirm")
async def review_my_pending_confirm(user: dict = Depends(require_user)):
    """任务3：当前医生的「待知情确认」项（submitted_by=本人 且 self_confirm_required 且未确认）。

    留痕模式（QC_AUTO_SIGN_FULL）下高危项入队即被 AI 自动签发，医生侧不阻塞；
    此端点让提交医生补一个知情确认（数据隔离：只返回自己的）。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(review.my_pending_confirm, me)
    return {"items": items, "me": me}


@router.post("/review/{rid}/self-confirm")
async def review_self_confirm(rid: str, user: dict = Depends(require_user)):
    """任务3：提交医生本人对「AI·留痕模式(自动)」签发的高危项做知情确认（仅本人）。

    标记 confirmed_by_self/self_confirmed_at；审计记 self_confirmed；非本人/无标记/重复确认 → 400。
    """
    me = user.get("username", "system")
    try:
        item = await asyncio.to_thread(review.self_confirm, rid, me)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="self_confirmed",
                            actor=me, payload={"rid": rid})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit("review.self_confirmed", me, {"rid": rid})
    return item


@router.post("/review/{rid}/resolve")
async def review_resolve(rid: str, body: ResolveReq,
                         user: dict = Depends(require_role("qc", "admin", "pharmacist"))):
    """签发/驳回（按 agent 分流的签字权矩阵）：
    - 非 drug 类：仅 qc/admin（任务2 收权，原 doctor/pharmacist 可调，属显式行为变更）；
    - drug 类：pharmacist/admin（问题4 收窄，语义变更：原 qc/admin/pharmacist——用户拍板
      质控科只管病例，药物核对权=药剂科一票+管理员；drug 亦豁免留痕模式、高危恒在
      pending，药剂科任何一人可签发/驳回）。
    双控不变：审核人≠提交人由 review.resolve 强制；翻案（reopen）仍仅 qc/admin。"""
    reviewer = user.get("username", "system")
    item = await asyncio.to_thread(review.get, rid)
    if item is not None:  # 条目不存在时保持旧行为：落入 review.resolve 抛 ReviewError → 400
        allowed = _DRUG_RESOLVE_ROLES if item.get("agent") == "drug" else _RESOLVE_ROLES
        if user.get("role", "") not in allowed:
            if item.get("agent") == "drug":
                raise HTTPException(403, "药物核对项：仅药师（pharmacist）/管理员可签发/驳回")
            raise HTTPException(403, "签发/驳回仅质控员（qc）/管理员可执行")
    try:
        item = await asyncio.to_thread(review.resolve, rid, body.decision, reviewer, body.note)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action=f"resolved_{body.decision}",
                            actor=reviewer, payload={"rid": rid, "role": user.get("role", "")})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit(f"review.{body.decision}", reviewer, {"rid": rid})
    if item.get("agent") == "qc" and body.decision == "approved":
        # 阶段4.2 归档挂点：质控（qc）approve 成功 → 自动归档病例库。全兜底：失败仅审计
        # case_archive.failed，绝不影响质控主流程（质控结论已签发生效）。
        try:
            entry = await asyncio.to_thread(case_archive.archive_from_review, item)
            await asyncio.to_thread(get_audit_logger().write, event_type="case_archive",
                                    action="created", actor=reviewer,
                                    payload={"rid": rid, "arch_id": entry["id"],
                                             "dept": entry.get("dept", ""),
                                             "role": user.get("role", "")})
            await pg_store.mirror_audit("case_archive.created", reviewer,
                                        {"rid": rid, "arch_id": entry["id"]})
        except Exception as e:  # noqa: BLE001 —— 归档失败绝不冒泡
            await asyncio.to_thread(get_audit_logger().write, event_type="case_archive",
                                    action="failed", actor=reviewer,
                                    payload={"rid": rid, "err": str(e)[:150]})
            await pg_store.mirror_audit("case_archive.failed", reviewer, {"rid": rid})
    return item


@router.post("/review/{rid}/reopen")
async def review_reopen(rid: str, user: dict = Depends(require_role(*_RESOLVE_ROLES))):
    """翻案（转人工复核）：已处理（含 AI 自动签发）条目回到待核对，清除签署信息。
    双控对称：操作人不能是提交人本人；任务2 收权：仅 qc/admin 可翻案。翻案本身记审计留痕。"""
    operator = user.get("username", "system")
    try:
        item = await asyncio.to_thread(review.reopen, rid, operator)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="reopened",
                            actor=operator, payload={"rid": rid, "role": user.get("role", "")})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit("review.reopened", operator, {"rid": rid})
    return item


# ---------- 病历质控（三轨）----------
def _load_optional_table(name: str):
    p = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))), "data", name)
    try:
        if not os.path.isfile(p):
            return None
        with open(p, encoding="utf-8") as f:  # with-open 确保句柄及时关闭（防 Windows 句柄占用锁文件）
            return json.load(f)
    except Exception:  # noqa: BLE001
        return None


class QcReq(BaseModel):
    record: dict
    labs: dict | None = None
    # 任务6：质控重提——指向本人被驳回的原记录 id；服务端校验存在/归属/驳回态，
    # attempt 沿原记录链递增，第 ≥2 次不合格自动升级病案科复核（rejected_escalated）
    resubmit_of: str | None = Field(default=None, max_length=64)

    @field_validator("record")
    @classmethod
    def _cap_record(cls, v: dict) -> dict:
        """白名单上限：≤20 项、键≤40 字符、值≤2000 字符（超限 422，防大 payload 撑爆链路）。"""
        if len(v) > 20:
            raise ValueError("record 最多 20 项")
        for k, val in v.items():
            if len(str(k)) > 40:
                raise ValueError("record 键名过长（>40 字符）")
            if len(str(val)) > 2000:
                raise ValueError("record 值过长（>2000 字符）")
        return v

    @field_validator("labs")
    @classmethod
    def _cap_labs(cls, v: dict | None) -> dict | None:
        """白名单上限：≤30 项、键≤40 字符、值≤2000 字符（与 record 同约束、项数放宽到 30）。"""
        if v is None:
            return v
        if len(v) > 30:
            raise ValueError("labs 最多 30 项")
        for k, val in v.items():
            if len(str(k)) > 40:
                raise ValueError("labs 键名过长（>40 字符）")
            if len(str(val)) > 2000:
                raise ValueError("labs 值过长（>2000 字符）")
        return v


def _chain_attempt(origin_id: str) -> int:
    """任务6：沿 resubmit_of 链向上读取既有最大 attempt（链上限 50 防环/脏数据自旋）。
    每次重提条目都落盘自己的 attempt=父链 attempt+1，正常链只需读直接父级；
    向上遍历兜底链中 attempt 缺失的历史数据。"""
    attempt, seen, cur = 1, set(), origin_id
    while cur and cur not in seen and len(seen) < 50:
        seen.add(cur)
        item = review.get(cur)
        if not item:
            break
        try:
            attempt = max(attempt, int(item.get("attempt") or 1))
        except (TypeError, ValueError):
            pass
        cur = item.get("resubmit_of")
    return attempt


# ---------- HIS 外推（integration 层端口-适配器接入点，唯一示例，见 docs/archive/INTEGRATION.md） ----------

async def _his_push_qc_result(record_id: str, result: dict, actor: str) -> None:
    """质控结论外推医院 HIS（backend/integration 端口-适配器边界；批2 任务2 接入示例）。

    核心纪律——质控主流程零感知、HIS 故障零影响：
    - settings.his_adapter="none"（默认未对接）→ NullAdapter：静默跳过，零审计噪音；
    - 适配器成功 → 审计 his.pushed；适配器显式拒收（返回 falsy）→ 审计 his.push_skipped；
    - 任何异常（含配置未知厂商标识）→ 审计 his.push_failed，绝不向上抛——失败仅留痕，
      医生拿质控报告不受 HIS 可用性影响。
    """
    try:
        adapter = his_registry.get_adapter()
    except Exception as e:  # noqa: BLE001 —— 配置错误（未知标识）也要暴露为失败审计
        await asyncio.to_thread(get_audit_logger().write, event_type="his", action="push_failed",
                                actor=actor,
                                payload={"stage": "resolve_adapter", "error": str(e)[:200]})
        return
    if isinstance(adapter, NullAdapter):
        return  # 未对接 HIS：优雅跳过（默认形态零开销零噪音）
    try:
        ok = await asyncio.to_thread(adapter.push_qc_result, record_id, result)
    except Exception as e:  # noqa: BLE001 —— HIS 侧任何故障全兜底
        await asyncio.to_thread(get_audit_logger().write, event_type="his", action="push_failed",
                                actor=actor, payload={"record_id": record_id,
                                                      "error": str(e)[:200],
                                                      "adapter": type(adapter).__name__})
        return
    if ok:
        await asyncio.to_thread(get_audit_logger().write, event_type="his", action="pushed",
                                actor=actor, payload={"record_id": record_id,
                                                      "adapter": type(adapter).__name__,
                                                      "status": str((result or {}).get("status", ""))})
    else:
        await asyncio.to_thread(get_audit_logger().write, event_type="his", action="push_skipped",
                                actor=actor, payload={"record_id": record_id,
                                                      "adapter": type(adapter).__name__})


@router.post("/qc/record", response_model=AskResp)
async def qc_record(req: QcReq, user: dict = Depends(require_user)):
    actor = user.get("username", "system")
    dept = user.get("dept", "")
    if not dept:  # 科室由服务端绑定（签名/科室同源权威），未设置科室拒绝出报告
        raise HTTPException(400, "请先联系管理员设置科室")
    # 任务6：重提链校验——resubmit_of 必须指向「存在 + 本人提交 + 已被驳回」的原记录；
    # attempt 沿原记录链 +1（首提为 1，首次重提为 2…）。
    resubmit_of = (req.resubmit_of or "").strip() or None
    attempt = 1
    if resubmit_of:
        orig = await asyncio.to_thread(review.get, resubmit_of)
        if orig is None:
            raise HTTPException(400, "重提失败：原记录不存在")
        if orig.get("submitted_by") != actor:
            raise HTTPException(403, "重提失败：仅原提交人本人可重新提交该记录")
        if orig.get("status") != "rejected":
            raise HTTPException(400, f"重提失败：原记录当前状态为 {orig.get('status')}，仅被驳回记录可重提")
        attempt = await asyncio.to_thread(_chain_attempt, resubmit_of) + 1
        await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="resubmit",
                                actor=actor, payload={"resubmit_of": resubmit_of, "attempt": attempt,
                                                      "role": user.get("role", "")})
    # PHI 脱敏：病历内容入 LLM/队列前逐字段处理（与其它 agent 同纪律）。
    # lab 键名是已知的医学项目名（如 血钾=6.8），不应被当手机号脱敏；仅对值脱敏并限制键名白名单外的处置。
    known_lab_keys = {"血钾", "钾", "血钠", "钠", "血钙", "血糖", "血氯", "血肌酐", "肌酐",
                      "尿素氮", "BUN", "血白蛋白", "白蛋白", "ALT", "AST", "WBC", "Hb", "PLT"}
    redacted = {k: _redactor.redact(str(v)) for k, v in (req.record or {}).items()}
    redacted["医师签名"] = actor  # 医师签名绑定当前账号：后端权威覆盖，不采信前端任何值
    redacted["科室"] = dept       # 科室同样服务端绑定（与账号 dept 一致）
    labs = {}
    for k, v in (req.labs or {}).items():
        if k in known_lab_keys:
            labs[k] = str(v)  # 已知项目：值保持原值（数值需送 critical_value_check）
        else:
            labs[k] = _redactor.redact(str(v))  # 其它（姓名/电话/身份证）走脱敏
    icd_table = _load_optional_table("icd_table.json")        # 需院方提供，否则 ICD 轨未启用
    thresholds = _load_optional_table("critical_values.json")  # 需院方提供，不臆造安全阈值
    out = await asyncio.to_thread(qc.review_quality, redacted, icd_table, thresholds, labs)
    sources = ["track:完整性", "track:内涵质量", "track:ICD", "track:危急值"]
    lines = [f"【病案质控·AI建议】完整性缺 {len(out['completeness'])} 项、内涵缺陷 {len(out['connotation'])} 项；"
             f"ICD {out['icd']['note'] if not out['icd']['enabled'] else '已核对'}；"
             f"危急值 {out['critical']['note'] if not out['critical']['enabled'] else ('标记'+str(len(out['critical']['flags']))+'项')}。"]
    for d in out["defects"][:12]:
        lines.append(f"- [{d['track']}/{d['level']}] {d.get('field','')}：{d.get('issue','')}")
    for f in out["critical"].get("flags", []):
        lines.append(f"- [危急值] {f['item']}={f['value']} {f['issue']}")
    # 任务6：重提自动升级——第 ≥2 次提交且 AI 判定不合格（存在缺陷/危急值）→ 结果标
    # rejected_escalated（升级病案科复核=qc 角色终审）并审计 qc_escalated。
    # escalated 优先级高于 QC_AUTO_PASS 三档：不合格的升级件永不自动归档（下方
    # auto_pass 分支显式短路）；确定性硬伤预筛仍在升级判定之前（立即驳回给医生确定性
    # 反馈，不入队故无升级对象）。
    escalated = bool(resubmit_of) and attempt >= 2 and bool(out["defects"] or out["has_critical"])
    if escalated:
        await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="qc_escalated",
                                actor=actor, payload={"attempt": attempt, "resubmit_of": resubmit_of})
        await pg_store.mirror_audit("qc.qc_escalated", actor,
                                    {"attempt": attempt, "resubmit_of": resubmit_of})
    text = "\n".join(lines)
    await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="query", actor=actor,
                            payload={"fields": len(req.record), "labs": len(req.labs or {}),
                                     "role": user.get("role", "")})
    if settings.qc_auto_pass:
        # 三档分流：①确定性硬伤 → 自动驳回不入队；②硬伤全过且置信 ≥0.85 → 自动归档不入队；
        # ③其余（内涵轨缺陷/危急值）→ 走现行为入队人工终审。默认关闭，行为完全不变。
        hard = out.get("hard_defects") or []
        if hard:
            await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="auto_reject",
                                    actor=actor, payload={"hard": hard[:8]})
            await _his_push_qc_result("", {"status": "auto_rejected",
                                           "confidence": out["confidence"],
                                           "defects": out["defects"]}, actor)
            return AskResp(answer="⛔ 自动驳回（AI 预筛）：存在确定性硬伤：" + "；".join(hard),
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None, status="auto_rejected",
                           qc_defects=out["defects"])
        if out["confidence"] >= 0.85 and not escalated:  # 任务6：escalated 永远进人工，优先于 auto_pass
            await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="auto_pass",
                                    actor=actor, payload={"conf": out["confidence"]})
            await _his_push_qc_result("", {"status": "auto_archived",
                                           "confidence": out["confidence"],
                                           "defects": out["defects"]}, actor)
            return AskResp(answer=text + f"\n\n✅ AI 预审通过（置信 {out['confidence']:.2f}），已归档",
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None, qc_defects=out["defects"])
    if escalated:
        text += f"\n\n⚠ 第 {attempt} 次提交仍不合格，已自动升级病案科复核（质控员终审）。"
    # 任务6：meta 携带已脱敏原病历+检验值入队留档，供「我的质控驳回→重新提交」预填。
    rid = await _enqueue_if_risk("qc", "病历质控", text, out["confidence"], True, "病案质控终审", actor,
                                 resubmit_of=resubmit_of, attempt=attempt,
                                 meta={"record": redacted, "labs": labs})
    # HIS 外推（批2 任务2 接入点）：质控结论生成后按 settings.his_adapter 推送；
    # 未对接静默跳过，异常仅审计，绝不影响上方的入队与下方响应。
    await _his_push_qc_result(rid or "", {"status": "rejected_escalated" if escalated else "enqueued",
                                          "confidence": out["confidence"],
                                          "defects": out["defects"], "escalated": escalated}, actor)
    return AskResp(answer=text, confidence=out["confidence"], needs_human_review=True,
                   sources=sources, review_id=rid, qc_defects=out["defects"],
                   status="rejected_escalated" if escalated else "ok")


@router.get("/qc/my-rejections")
async def qc_my_rejections(user: dict = Depends(require_user)):
    """任务2：当前医生的「我的质控驳回」轻量视图数据（require_user，只返回自己的）。

    返回本人提交且被驳回（status=rejected）的记录：含驳回原因（review_note）、
    审核人（reviewed_by）、驳回时间（resolved_at）。数据隔离由服务端过滤保证。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(review.my_rejections, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="my_rejections",
                            actor=me, payload={"n": len(items)})
    return {"items": items, "me": me}


# ---------- 阶段4：病例库（质控通过自动归档的合规检索/移除） ----------
class CaseArchiveRemoveReq(BaseModel):
    """移出病例库请求：原因必填（软删除留痕 removed_reason，缺失 422）。"""
    reason: str = Field(min_length=1, max_length=200)


@router.get("/case-archive")
async def case_archive_list(dept: str | None = None, status: str | None = None,
                            date_from: str | None = None, date_to: str | None = None,
                            user: dict = Depends(require_role("qc", "admin"))):
    """病例库全量清单（qc/admin）：按科室/状态/归档日期区间筛选 + 库内统计头。

    doctor 走 /case-archive/mine（仅本人 active）；removed 条目仍在本视图可见
    （软删除留痕审计）。审计 case_archive.list。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(case_archive.list_cases, dept=(dept or "").strip() or None,
                                    status=(status or "").strip() or None,
                                    date_from=(date_from or "").strip() or None,
                                    date_to=(date_to or "").strip() or None)
    st = await asyncio.to_thread(case_archive.stats)
    await asyncio.to_thread(get_audit_logger().write, event_type="case_archive", action="list",
                            actor=me, payload={"n": len(items), "role": user.get("role", "")})
    return {"items": items, "stats": st, "me": me}


@router.get("/case-archive/mine")
async def case_archive_mine(user: dict = Depends(require_role("doctor"))):
    """doctor「我的归档」：本人提交且质控通过、当前 status=active 的记录（只读）。

    数据隔离由服务端过滤保证（submitted_by=me）；removed 条目不下发（doctor 无移除权，
    移除留痕仅 qc/admin 病例库视图可见）。审计 case_archive.list（scope=mine）。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(case_archive.list_mine, me)
    await asyncio.to_thread(get_audit_logger().write, event_type="case_archive", action="list",
                            actor=me, payload={"n": len(items), "scope": "mine",
                                               "role": user.get("role", "")})
    return {"items": items, "me": me}


@router.post("/case-archive/{arch_id}/remove")
async def case_archive_remove(arch_id: str, body: CaseArchiveRemoveReq,
                              user: dict = Depends(require_role("qc", "admin"))):
    """移出病例库（qc/admin，软删除）：status=removed + removed_by/removed_reason 留痕。
    原因必填（缺失/纯空白 422）；重复移除/条目不存在 → 400。审计 case_archive.removed。"""
    me = user.get("username", "system")
    if not body.reason.strip():
        raise HTTPException(422, "移除原因必填")
    try:
        entry = await asyncio.to_thread(case_archive.remove, arch_id, me, body.reason.strip())
    except ValueError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="case_archive",
                            action="removed", actor=me,
                            payload={"arch_id": arch_id, "reason": body.reason.strip()[:120],
                                     "role": user.get("role", "")})
    await pg_store.mirror_audit("case_archive.removed", me,
                                {"arch_id": arch_id, "reason": body.reason.strip()[:120]})
    return entry


# ---------- 「粘贴整段病历」LLM 拆分（F2：质控表单快捷填充，仅辅助填表不参与质控判定）----------
QC_FIELDS = ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")

_QC_PARSE_PROMPT = (
    "你是病历结构化助手。从用户提供的病历文本中提取以下七个字段："
    "主诉、现病史、既往史、体格检查、辅助检查、初步诊断、医师签名。\n"
    "规则：1) 只输出一个 JSON 对象，键为上述七个中文字段名，值为病历中对应的内容；"
    "2) 文本中缺失或无法确定的字段，值用空字符串；"
    "3) 不得编造、不得输出解释文字或代码块标记，严格 JSON 输出。"
)


class QcParseReq(BaseModel):
    text: str = Field(max_length=20000)


def _extract_json_obj(raw) -> dict:
    """从 LLM 输出提取首个 JSON 对象（容忍 ```json 代码块与前后缀文字）。"""
    if isinstance(raw, dict):
        return raw
    s = str(raw or "")
    start, end = s.find("{"), s.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("输出中未找到 JSON 对象")
    return json.loads(s[start:end + 1])


@router.post("/qc/parse")
async def qc_parse(req: QcParseReq, user: dict = Depends(require_user)):
    """病历文本 → 七字段 JSON（{fields: {...}}）。缺字段留空字符串，解析失败 502。"""
    from backend.core.llm_factory import get_llm
    actor = user.get("username", "system")
    await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="parse", actor=actor,
                            payload={"len": len(req.text), "role": user.get("role", "")})
    try:
        llm = get_llm("medical_literature")
        r = await llm.ainvoke([SystemMessage(content=_QC_PARSE_PROMPT),
                               HumanMessage(content=req.text)])
        out = r.content if hasattr(r, "content") else str(r)
        if isinstance(out, list):  # 某些 provider 返回分段
            out = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in out)
        data = _extract_json_obj(out)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001 —— LLM/解析异常统一体面降级，前端提示手动填写
        raise HTTPException(502, f"病历拆分失败：{str(e)[:80]}；请手动填写")
    fields = {k: str(data.get(k, "") or "").strip() for k in QC_FIELDS}
    return {"fields": fields}


# ---------- 知识库纯检索（外部系统/MCP 复用检索层，不带 LLM 生成）----------
class KbSearchReq(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=3, ge=1, le=10)


@router.post("/kb/search")
async def kb_search(req: KbSearchReq, user: dict = Depends(require_user)):
    """知识库纯检索：稠密+稀疏混合召回 → BGE 精排，返回 chunks（content/source/score）。

    与 /literature/ask 的区别：本端点只检索不生成（无 LLM 产出）、不写审核队列——
    供外部系统（如经 MCP 的 AI 客户端）直接复用检索层做自有 RAG。检索失败体面降级 503。
    """
    from backend.core import medical_kb
    actor = user.get("username", "system")
    await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="search",
                            actor=actor, payload={"q": req.query[:120], "top_k": req.top_k})
    try:
        chunks = await asyncio.to_thread(medical_kb.search_hybrid, req.query, req.top_k)
    except Exception as e:  # noqa: BLE001 —— Milvus/嵌入模型未就绪时体面降级，不裸 500
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="search_failed",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(503, "知识库检索暂时不可用（Milvus/嵌入模型未就绪），请稍后重试")
    return {"query": req.query, "top_k": req.top_k, "chunks": chunks, "total": len(chunks)}


# ---------- 知识库管理（仅 admin）----------
class KbUploadReq(BaseModel):
    name: str = Field(max_length=250)
    # 任务5：大教材 PDF（实测 38.5MB）base64 后约 54MB，上限提到 90M 字符（解码约 67MB）；
    # 请求体整体仍受 max_body_mb=64 中间件约束，双保险。
    data_b64: str = Field(max_length=90_000_000)
    # 任务B：结构感知切分文档类型（如 "pdf"/"text"）；缺省按扩展名判定（PDF 默认
    # 结构感知）。前端暂不加 UI，后端参数就绪（透传给 ingest/异步任务）。
    doc_kind: str | None = Field(default=None, max_length=20)


@router.post("/admin/kb/upload")
async def kb_upload(req: KbUploadReq, user: dict = Depends(require_role("admin"))):
    """知识库上传（任务2 异步化）：小文件（PDF ≤20 页 / 文本 ≤20 万字符）保留同步模式
    直接返回摄取结果；大文件先落盘并立即返回 {task_id, mode:"async"}，后台线程执行
    extract→chunk→embed→入库（进度写 data/kb/tasks/{task_id}.json），前端轮询
    GET /admin/kb/upload/status/{task_id}——同步 HTTP 不再被分钟级向量化阻塞。
    类型校验前置：同步/异步都先拒绝非法扩展名（明确 400 而非落到异步任务里失败）。
    任务C：扫描版 PDF 自动逐页 OCR（未装 rapidocr 时明确 400）；png/jpg 单图 OCR 入库。"""
    actor = user.get("username", "admin")
    await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload",
                            actor=actor, payload={"name": req.name[:120]})
    ext = os.path.splitext(req.name)[1].lower()
    if ext not in kb_ingest.ALLOWED_EXTS:
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload_rejected",
                                actor=actor,
                                payload={"name": req.name[:120], "reason": f"不支持的文件类型：{ext}"})
        raise HTTPException(400, f"不支持的文件类型：{ext or '(无扩展名)'}（仅允许 md/txt/pdf/png/jpg）")
    try:
        raw = base64.b64decode(req.data_b64)
    except Exception:  # noqa: BLE001 —— base64 损坏
        raise HTTPException(400, "文件数据解码失败（data_b64 非 base64）")
    if len(raw) == 0:
        raise HTTPException(400, "文件内容为空")
    # 大文件 → 后台任务：立即返回 task_id（不再等待 BGE-M3 分钟级向量化）
    if await asyncio.to_thread(kb_ingest.should_ingest_async, req.name, raw):
        task_id = await asyncio.to_thread(kb_ingest.start_ingest_task,
                                          req.name[:250], raw, None, req.doc_kind)
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload_async",
                                actor=actor, payload={"name": req.name[:120], "task_id": task_id})
        return {"task_id": task_id, "mode": "async", "name": req.name[:250]}
    # 小文件 → 同步路径（现状行为完全保留）
    try:
        out = await asyncio.to_thread(kb_ingest.ingest_b64, req.name[:250], req.data_b64,
                                      None, req.doc_kind)
    except ValueError as e:
        # 任务5：确定性业务错误（扫描版 PDF/文档过大等）→ 透传具体原因，不再笼统"失败"
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload_rejected",
                                actor=actor,
                                payload={"name": req.name[:120], "reason": str(e)[:160]})
        raise HTTPException(400, str(e))
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload_failed",
                                actor=actor,
                                payload={"name": req.name[:120], "err": str(e)[:160]})
        raise HTTPException(400, "文件摄取失败：请确认格式为 .md/.txt/.pdf 且内容可解析")
    return {**out, "mode": "sync"}


@router.get("/admin/kb/upload/status/{task_id}")
async def kb_upload_status(task_id: str, user: dict = Depends(require_role("admin"))):
    """任务2：后台上传任务状态查询（上传者轮询，2s 间隔）：{status: processing/done/error,
    progress, total, error, result}。task_id 不存在/非法 → 404（read_task 白名单防路径穿越）。"""
    st = await asyncio.to_thread(kb_ingest.read_task, task_id)
    if st is None:
        raise HTTPException(404, "任务不存在（或 task_id 非法）")
    return st


@router.get("/admin/kb")
async def kb_list(user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.list_docs)


@router.get("/admin/kb/{tag}/preview")
async def kb_preview(tag: str, user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.preview_doc, tag)


@router.delete("/admin/kb/{tag}")
async def kb_delete(tag: str, user: dict = Depends(require_role("admin"))):
    """删除知识库文档（轮 A1 修复）：
    - 内置库允许 admin 删除（误删可重跑种子脚本恢复）；审计区分——内置记 kb.builtin_removed
      （tag+name），普通库记 kb.delete；
    - 兜底：任何异常返回 JSONResponse({"detail"})，永不裸文本 500（此前内置删除 ValueError
      未捕获 → FastAPI 纯文本 500 → 前端 JSON 解析报 "Unexpected token 'I'"）。"""
    actor = user.get("username", "admin")
    try:
        is_builtin = tag in kb_ingest.BUILTIN_TAGS
        name = str((await asyncio.to_thread(kb_ingest.doc_meta, tag)).get("name") or tag)
        await asyncio.to_thread(get_audit_logger().write, event_type="kb",
                                action="builtin_removed" if is_builtin else "delete",
                                actor=actor, payload={"tag": tag, "name": name[:120]})
        return {"deleted": await asyncio.to_thread(kb_ingest.delete_doc, tag)}
    except Exception as e:  # noqa: BLE001 —— 兜底：任何异常一律 JSON，绝不裸文本
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="delete_failed",
                                actor=actor, payload={"tag": tag, "err": str(e)[:160]})
        return JSONResponse({"detail": str(e)[:200]}, status_code=500)


# ---------- admin 用户管理 ----------
class UserCreateReq(BaseModel):
    username: str
    password: str
    role: str
    dept: str = Field(default="", max_length=40)  # 所属科室（质控签名/科室绑定用）


@router.post("/admin/users")
async def admin_create_user(body: UserCreateReq, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import create_user, get_user
    from backend.core.departments import validate_role_dept
    uname = (body.username or "").strip()
    if not (3 <= len(uname) <= 32) or not re.fullmatch(r"[A-Za-z0-9_\-]+", uname):
        raise HTTPException(400, "用户名需 3-32 位，仅字母/数字/下划线/连字符")
    if len(body.password or "") < 8:
        raise HTTPException(400, "密码至少 8 位")
    # 整改轮 B 任务3（科室-角色归属模型）：建号时校验角色×科室匹配（存量账号不追溯），
    # 违反 → 422 中文文案（语义变更：此前 dept 不做角色约束，任意组合可入库）。
    try:
        validate_role_dept(body.role, (body.dept or "").strip())
    except ValueError as e:
        raise HTTPException(422, str(e))
    try:
        ok = await asyncio.to_thread(create_user, uname, body.password, body.role, False,
                                     (body.dept or "").strip())
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(400, "用户名已存在")
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="user_created",
                            actor=user.get("username", "admin"),
                            payload={"user": uname, "role": body.role,
                                     "dept": (body.dept or "").strip()})
    await pg_store.mirror_audit("admin.user_created", user.get("username", "admin"), {"user": uname})
    rec = await asyncio.to_thread(get_user, uname)
    if rec:
        await pg_store.mirror_user(uname, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}


@router.delete("/admin/users/{username}")
async def admin_delete_user(username: str, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import delete_user
    if username == user.get("username"):
        raise HTTPException(400, "不能删除当前登录的账号")
    ok = await asyncio.to_thread(delete_user, username)
    if not ok:
        raise HTTPException(404, "用户不存在")
    revoke_user(username)  # 使其已签发的令牌立即失效
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="user_deleted",
                            actor=user.get("username", "admin"), payload={"user": username})
    await pg_store.mirror_audit("admin.user_deleted", user.get("username", "admin"), {"user": username})
    return {"ok": True}


# ---------- admin 科室管理（质控签名/科室绑定的取值来源）----------
class DeptReq(BaseModel):
    name: str = Field(min_length=1, max_length=40)


@router.get("/admin/departments")
async def admin_list_departments(user: dict = Depends(require_role("admin"))):
    from backend.core import departments
    return {"list": await asyncio.to_thread(departments.list_departments)}


@router.post("/admin/departments")
async def admin_add_department(req: DeptReq, user: dict = Depends(require_role("admin"))):
    from backend.core import departments
    try:
        ok = await asyncio.to_thread(departments.add_department, req.name)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(400, "科室已存在")
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="department_added",
                            actor=user.get("username", "admin"), payload={"name": req.name.strip()})
    return {"ok": True}


@router.delete("/admin/departments/{name}")
async def admin_delete_department(name: str, user: dict = Depends(require_role("admin"))):
    """name 走 path（前端 encodeURIComponent），FastAPI 自动百分号解码，中文名安全。"""
    from backend.core import departments
    try:
        ok = await asyncio.to_thread(departments.remove_department, name)
    except ValueError as e:  # 仍有账号绑定该科室 → 400，前端提示先调整账号
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(404, "科室不存在")
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="department_removed",
                            actor=user.get("username", "admin"), payload={"name": name})
    return {"ok": True}


# ---------- admin 运维：数据面板 / 队列清理（只读优先，不做裸 SQL） ----------
@router.get("/admin/data")
async def admin_data(user: dict = Depends(require_role("admin")), audit_offset: int = 0):
    """管理员只读数据面板：用户、审计流、队列统计、PG 镜像与知识库规模、基础设施状态。

    audit_offset（任务A）：审计流翻页偏移（每页 50，与前端「上一页/下一页」配合）；
    负数按 0 处理，绝不因越界参数 500。
    """
    from backend.core.auth import all_users
    from backend.core.medical_kb import doc_count, milvus_status
    from backend.core.embedder import device
    users = [{"username": u, "role": rec.get("role", ""), "created": rec.get("created", ""),
              "dept": rec.get("dept", "")}
             for u, rec in (await asyncio.to_thread(all_users)).items()]
    items = await asyncio.to_thread(review.list_all, with_images=False)  # 数据面板统计只计数，剥离 images 控响应体积（任务5）
    stats = {"pending": sum(1 for i in items if i.get("status") == "pending"),
             "approved": sum(1 for i in items if i.get("status") == "approved"),
             "rejected": sum(1 for i in items if i.get("status") == "rejected")}
    al = get_audit_logger()
    # 任务5：审计流数据源切换为 PG audit_log 真源（audit_recent_pg 按 ts 倒序分页，返回
    # 与 JSONL recent 相同结构 [{ts,event_type,actor,payload}]）；PG 未建池/异常/超时
    # → 回落 JSONL 现状（AuditLog 有界缓存），前端审计流/翻页/导出对数据源透明。
    # F5：保留完整 ISO 串（含 +00:00 偏移），fmtTs 依赖时区信息做本地化。
    # 任务A：audit_offset 翻页（每页 50）。
    off = max(0, audit_offset)
    try:
        audit_src = await pg_store.audit_recent_pg(50, offset=off)
    except Exception:  # noqa: BLE001 —— 数据源切换绝不影响面板主数据
        audit_src = None
    if audit_src is None:
        audit_src = al.recent(50, offset=off)
    audit_recent = [{"ts": e["ts"], "event": e["event_type"],
                     "action": (e.get("payload") or {}).get("action", ""), "actor": e["actor"],
                     "payload": e.get("payload", {})}  # 完整 payload 下传，供前端人话化详情
                    for e in audit_src]
    try:
        pg_counts = await pg_store.counts()
    except Exception:  # noqa: BLE001
        pg_counts = {}
    # 基础设施只读状态（前端运维卡片）：milvus TCP 预检（<0.5s fail-fast）+ pg SELECT 1 探针。
    # 安全红线：只报状态/命令文本供展示与复制，本端点绝不提供任何执行/重启能力。
    try:
        milvus = await asyncio.to_thread(milvus_status)
    except Exception:  # noqa: BLE001 —— 状态探针绝不影响面板主数据
        milvus = {"connected": False, "uri": ""}
    try:
        pg_ok = await pg_store.ping()
    except Exception:  # noqa: BLE001
        pg_ok = False
    return {"users": users, "audit_recent": audit_recent, "review": stats,
            "pg_counts": pg_counts, "kb_docs": await asyncio.to_thread(doc_count),
            "events_total": len(al.entries), "device": device(), "uptime_s": int(time.time() - _START),
            "infra": {"milvus": milvus, "pg": {"ok": pg_ok}},
            # 任务3：admin「留痕模式」开关卡数据源（runtime_flags 优先，运行时切换即时反映）
            "flags": {"qc_auto_sign_full": runtime_flags.full_mode()}}


class PurgeReq(BaseModel):
    confirm_text: str = ""  # 必须为「清空」，服务端强制校验，杜绝前端误触/绕过


@router.post("/admin/review/purge-pending")
async def admin_purge_pending(body: PurgeReq, user: dict = Depends(require_role("admin"))):
    """清空全部待核对项（保留已处理历史），用于清理测试数据。需确认词「清空」。"""
    if (body.confirm_text or "").strip() != "清空":
        raise HTTPException(400, "确认词不符：请输入「清空」以确认执行")
    n = await asyncio.to_thread(review.purge_pending)
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="purge_pending",
                            actor=user.get("username", "admin"),
                            payload={"count": n, "role": user.get("role", "")})
    await pg_store.mirror_audit("review.purge_pending", user.get("username", "admin"), {"count": n})
    await pg_store.purge_pending_reviews()
    return {"purged": n}


# ---------- admin 运维：应用日志 tail（任务5，只读） + 日志健康卡元数据（本批次） ----------
def _read_log_tail(n: int) -> tuple[str, list[str], str, dict]:
    """同步读 logs/app.log 尾部 n 行（RotatingFileHandler 主文件，与 logger.py 同源路径）。
    只读末尾 512KB 窗口防大文件全量载入；窗口截断的首个残行丢弃；总行数按 1MB 分块
    全文件扫描计数（轮转上限 5MB，admin 低频调用可接受）。
    返回 (文件绝对路径, 行列表[旧→新], 提示语, 元数据)；元数据 {path, size_bytes,
    exists, total_lines} 供前端「日志健康卡」展示；文件不存在 → 提示
    「应用日志未启用（LOG_TO_FILE）」且元数据归零。"""
    from backend.core.logger import _FILE_NAME
    log_dir = (settings.log_dir or "logs").strip() or "logs"
    path = os.path.abspath(os.path.join(log_dir, _FILE_NAME))  # 绝对路径（健康卡展示）
    meta = {"path": path, "size_bytes": 0, "exists": False, "total_lines": 0}
    if not os.path.isfile(path):
        return path, [], "应用日志未启用（LOG_TO_FILE）", meta
    size = os.path.getsize(path)
    meta.update(exists=True, size_bytes=size)
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        window = min(size, 512 * 1024)
        f.seek(size - window)
        raw = f.read(window)
        f.seek(0)
        meta["total_lines"] = sum(chunk.count(b"\n")
                                  for chunk in iter(lambda: f.read(1024 * 1024), b""))
    all_lines = raw.decode("utf-8", errors="replace").splitlines()
    if window < size and all_lines:
        all_lines = all_lines[1:]  # 丢弃窗口起点处可能被截断的残行
    return path, all_lines[-n:], f"共 {min(len(all_lines), n)} 行（最新在底部）", meta


@router.get("/admin/logs")
async def admin_logs(lines: int = 200, user: dict = Depends(require_role("admin"))):
    """任务5：应用日志尾部只读视图（仅 admin）。lines=N 可调（1~500 上限截断，默认 200）。

    本批次新增日志健康卡元数据：path（绝对路径）/ size_bytes / exists / total_lines，
    与既有 file/lines/note 并存（向后兼容）。安全边界：只读主日志文件（app.log），
    不提供任何下载/删除/执行能力；日志内容可能含用户输入，前端必须逐行 esc() 后
    渲染（防注入）。文件不存在/未启用返回提示不报错。
    """
    n = max(1, min(int(lines or 200), 500))
    try:
        path, tail, note, meta = await asyncio.to_thread(_read_log_tail, n)
    except OSError as e:  # 读取失败（权限/占用等）显式 500 带原因，不静默吞掉
        raise HTTPException(500, f"日志文件读取失败：{e}")
    return {"file": path, "lines": tail, "note": note, **meta}


# ---------- admin 模型配置管理（F6：对话/视觉双通道，openai/anthropic 双格式）----------
class LlmProviderReq(BaseModel):
    kind: str                       # chat | vision
    api_format: str                 # openai | anthropic
    base_url: str = Field(max_length=2000)
    model_id: str = Field(max_length=200)
    display_name: str = Field(default="", max_length=200)
    api_key: str = Field(default="", max_length=2000)
    # 任务2 额外参数逃生舱：厂商特殊参数（JSON 字符串，如 {"thinking":{"type":"high"}}）。
    # 空串合法（=无额外参数）；非空必须是 JSON object，否则 422（pydantic 校验层拦截）。
    extra_params: str = Field(default="", max_length=4000)

    @field_validator("extra_params")
    @classmethod
    def _validate_extra_json(cls, v: str) -> str:
        from backend.core.llm_config import parse_extra_params
        if (v or "").strip() and not parse_extra_params(v):
            raise ValueError("extra_params 需为合法的 JSON 对象字符串，如 {\"thinking\":{\"type\":\"high\"}}")
        return (v or "").strip()


class LlmActivateReq(BaseModel):
    kind: str
    pid: str = Field(max_length=64)


def _reset_llm_cache() -> None:
    """F6：provider 增删/激活后必须重建模型缓存，否则 LLMFactory 仍复用旧实例。"""
    from backend.core.llm_factory import LLMFactory
    LLMFactory._instances.clear()


def _masked_providers() -> dict:
    """读配置并把 api_key 脱敏（前6后4中***，空则空串），供 GET 响应。"""
    cfg = llm_config.load_config()
    return {kind: {"active": (cfg.get(kind) or {}).get("active"),
                   "providers": [{**p, "api_key": llm_config.mask_key(p.get("api_key", ""))}
                                 for p in (cfg.get(kind) or {}).get("providers", [])]}
            for kind in ("chat", "vision")}


@router.get("/admin/llm/providers")
async def llm_providers(user: dict = Depends(require_role("admin"))):
    return _masked_providers()


@router.post("/admin/llm/providers")
async def llm_add_provider(req: LlmProviderReq, user: dict = Depends(require_role("admin"))):
    try:
        pid = await asyncio.to_thread(
            llm_config.add_provider, req.kind, req.api_format, req.base_url,
            req.model_id, req.display_name, req.api_key, req.extra_params)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_add",
                            actor=user.get("username", "admin"), payload={"kind": req.kind, "pid": pid})
    return {"id": pid}


@router.delete("/admin/llm/providers/{kind}/{pid}")
async def llm_delete_provider(kind: str, pid: str, user: dict = Depends(require_role("admin"))):
    try:
        ok = await asyncio.to_thread(llm_config.remove_provider, kind, pid)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(404, "未找到该 provider")
    _reset_llm_cache()
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_remove",
                            actor=user.get("username", "admin"), payload={"kind": kind, "pid": pid})
    return {"ok": True}


@router.post("/admin/llm/activate")
async def llm_activate(req: LlmActivateReq, user: dict = Depends(require_role("admin"))):
    try:
        await asyncio.to_thread(llm_config.activate, req.kind, req.pid)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()  # 切换后旧模型实例立即失效，下次 get_llm/get_vl_llm 按新配置重建
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_activate",
                            actor=user.get("username", "admin"), payload={"kind": req.kind, "pid": req.pid})
    return {"ok": True, "active": req.pid}


class LlmDeactivateReq(BaseModel):
    kind: str


@router.post("/admin/llm/deactivate")
async def llm_deactivate(req: LlmDeactivateReq, user: dict = Depends(require_role("admin"))):
    """停用激活 provider（active 置 None），回落 .env 内置配置。"""
    try:
        await asyncio.to_thread(llm_config.deactivate, req.kind)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()  # 停用后旧模型实例立即失效
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_deactivate",
                            actor=user.get("username", "admin"), payload={"kind": req.kind})
    return {"ok": True}


@router.get("/admin/llm/suggest-extra")
async def llm_suggest_extra(base_url: str = "", model_id: str = "",
                            user: dict = Depends(require_role("admin"))):
    """任务2 厂商自动预设（仅建议不落库）：按 base_url+model_id 返回建议的
    extra_params JSON 字符串（无匹配返回空串），前端据此预填「额外参数」输入框。"""
    suggested = await asyncio.to_thread(llm_config.suggest_extra_params, base_url, model_id)
    return {"suggested": suggested}


@router.post("/admin/llm/test")
async def llm_test(req: LlmProviderReq, user: dict = Depends(require_role("admin"))):
    """连通性测试（不落盘）。返回 {"ok","latency_ms","detail"}，detail 不含 api_key。
    任务2：透传 extra_params 同合并逻辑（真实验证用户填的参数可用性）。"""
    r = await asyncio.to_thread(llm_config.test_provider, req.api_format, req.base_url,
                                req.model_id, req.api_key, req.extra_params)
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_test",
                            actor=user.get("username", "admin"),
                            payload={"kind": req.kind if req.kind in ("chat", "vision") else None,
                                     "api_format": req.api_format, "model_id": req.model_id[:80],
                                     "ok": r["ok"]})
    return r
