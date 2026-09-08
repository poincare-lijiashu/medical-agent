"""MedAssist 医疗 API：4 agent + 审核中心 + health。

临床医生版：置信度按证据强度真实校准；仅高风险/低置信项进入「双人核对」队列。
全部 /ask 受 Bearer 保护；请求经 PHI 脱敏与审计（记录真实操作者）。
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import threading
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field, field_validator

from backend.api.deps import require_role, require_user, rate_limit, revoke_user
from backend.config import settings
from backend.core import kb_ingest, llm_config
from backend.core import medical_review as review
from backend.core import pg_store, qc
from backend.core.intent import prefilter_reply
from backend.core.medical_audit import PHIRedactor, get_audit_logger
from backend.core.medical_drug import check as drug_check

router = APIRouter(dependencies=[Depends(rate_limit)])
_redactor = PHIRedactor()
_lit_graph = None
_mdt_graph = None
_graph_lock = threading.Lock()  # 懒加载双检锁：并发首访只构建一次，防重复构建竞态
_START = time.time()

MAX_IMAGES = 6  # 与 medical_imaging.describe_images 上限一致

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
            "milvus": milvus}


@router.get("/overview")
async def overview(user: dict = Depends(require_user)):
    """概览面板：真实运行与合规指标（不编造）。"""
    from backend.core.embedder import device
    from backend.core.medical_kb import doc_count
    items = await asyncio.to_thread(review.list_all)  # 队列文件读盘移出事件循环（QUALITY-001）
    pending = sum(1 for i in items if i.get("status") == "pending")
    resolved = sum(1 for i in items if i.get("status") in ("approved", "rejected"))
    al = get_audit_logger()
    recent = [{"ts": e["ts"][11:19], "event": e["event_type"],
               "action": e["payload"].get("action", ""), "actor": e["actor"]}
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
    images: list[str] | None = Field(default=None, max_length=MAX_IMAGES)  # 多图（优先于 image），单张 ≤6MB

    @field_validator("images")
    @classmethod
    def _cap_images(cls, v: list[str] | None) -> list[str] | None:
        if v is not None:
            if len(v) > MAX_IMAGES:
                raise ValueError(f"影像图片最多 {MAX_IMAGES} 张，当前 {len(v)} 张")
            for s in v:
                if len(s) > 8_000_000:
                    raise ValueError("单张影像过大（>6MB），请压缩后上传")
        return v


class AskResp(BaseModel):
    answer: str
    confidence: float
    needs_human_review: bool
    sources: list = []
    review_id: str | None = None
    status: str = "ok"
    evidence: list = []


class ResolveReq(BaseModel):
    decision: str  # approved | rejected
    note: str = ""


async def _enqueue_if_risk(agent: str, q: str, answer: str, conf: float, review_flag: bool,
                           risk_reason: str, actor: str,
                           images: list[str] | None = None) -> str | None:
    await pg_store.mirror_audit(f"{agent}.answer", actor, {"conf": conf, "review": review_flag})
    if not review_flag:
        return None
    rid = await asyncio.to_thread(review.submit, agent=agent, question=q, answer=answer, confidence=conf,
                                  risk_reason=risk_reason, submitted_by=actor, images=images)
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="review_enqueued",
                            actor=actor, payload={"rid": rid, "reason": risk_reason})
    await pg_store.mirror_review(await asyncio.to_thread(review.get, rid))
    return rid


@router.get("/health")
async def health():
    return {"ok": True, "service": "MedAssist"}


@router.post("/literature/ask", response_model=AskResp)
async def literature_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="prefilter",
                                actor=actor, payload={"q": q[:60]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False, sources=["intent:prefilter"])
    await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="query",
                            actor=actor, payload={"q": q[:200]})
    try:
        g = _get_lit_graph()
        sid = "lit-" + uuid.uuid4().hex[:12]
        out = await g.ainvoke({"session_id": sid, "question": q, "query": q, "attempts": 0},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001 —— 三层兑底：provider/检索异常→体面降级不裸500
        await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="degraded",
                                actor=actor, payload={"err": str(e)[:120]})
        return AskResp(answer="模型/知识库服务暂时不可用（多为瞬时），已记录并标记需人工复核；请稍后重试。",
                       confidence=0.3, needs_human_review=True, sources=["degraded:llm"])
    a = out["answer"]
    rid = await _enqueue_if_risk("literature", q, a["text"], a["confidence"],
                           a.get("needs_human_review", False), "低置信或未支持论断", actor)
    return AskResp(answer=a["text"], confidence=a["confidence"],
                   needs_human_review=a.get("needs_human_review", False),
                   sources=a.get("sources", []), review_id=rid,
                   evidence=a.get("evidence", []))


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/literature/stream")
async def literature_stream(req: AskReq, user: dict = Depends(require_user)):
    """SSE 流式：逐节点推进度(retrieve/grade/generate/verify) + 末尾 result。失败发 error 事件。"""
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    await asyncio.to_thread(get_audit_logger().write, event_type="literature", action="stream",
                            actor=actor, payload={"q": q[:200]})

    async def gen():
        pre = prefilter_reply(q)
        if pre:
            yield _sse("result", {"answer": pre, "confidence": 0.95,
                                  "needs_human_review": False, "sources": ["intent:prefilter"], "review_id": None})
            return
        final = None
        try:
            g = _get_lit_graph()
            sid = "lit-" + uuid.uuid4().hex[:12]
            async for upd in g.astream({"session_id": sid, "question": q, "query": q, "attempts": 0},
                                       config={"configurable": {"thread_id": sid}}, stream_mode="updates"):
                for node, out in upd.items():
                    if node in ("retrieve", "grade", "generate"):
                        yield _sse("progress", {"step": node})
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
        rid = await _enqueue_if_risk("literature", q, final["text"], final["confidence"],
                               final.get("needs_human_review", False), "低置信或未支持论断", actor)
        yield _sse("result", {"answer": final["text"], "confidence": final["confidence"],
                              "needs_human_review": final.get("needs_human_review", False),
                              "sources": final.get("sources", []), "review_id": rid,
                              "evidence": final.get("evidence", [])})

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# 药物置信度显式映射：max_severity → (conf, risk, review_flag)。
# 取值与旧子串判定（"禁忌"/"高危"/"发现以下相互作用"）完全一致，消除对 answer 文本的子串耦合。
_DRUG_SEV_CONF = {
    "禁忌": (0.90, "药物禁忌", True),
    "高危": (0.86, "高危相互作用", True),
    "中危": (0.80, "中危相互作用", False),
}


@router.post("/drug/ask", response_model=AskResp)
async def drug_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type="drug", action="prefilter",
                                actor=actor, payload={"q": q[:200]})
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
    auto_sign = (settings.qc_auto_sign and sev == "中危" and bool(r.get("sources"))
                 and str(r["sources"][0]).startswith("drug_rules:"))
    rid = await _enqueue_if_risk("drug", q, ans, conf, review_flag or auto_sign, risk, actor)
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


@router.post("/imaging/ask", response_model=AskResp)
async def imaging_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type="imaging", action="prefilter",
                                actor=actor, payload={"q": q[:200]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        try:
            from backend.core.medical_imaging import describe_images, vl_model_name
            desc = await describe_images(imgs, q)
        except Exception as e:  # noqa: BLE001
            await asyncio.to_thread(get_audit_logger().write, event_type="imaging", action="describe_failed",
                                    actor=actor, payload={"err": str(e)[:120]})
            raise HTTPException(500, "影像描述服务暂时不可用，请稍后重试")
        risky = _has_risk(desc)
        conf = 0.60 if risky else 0.72
        rid = await _enqueue_if_risk("imaging", q, desc, conf, risky, "影像可疑高危征象", actor, images=imgs)
        suffix = "（已标记为需放射科医师双人核对）" if risky else "\n\n以上为 AI 初步所见，供医师参考。"
        await asyncio.to_thread(get_audit_logger().write, event_type="imaging", action="vl_described",
                                actor=actor, payload={"len": len(desc), "risky": risky})
        return AskResp(answer=desc + suffix, confidence=conf, needs_human_review=risky,
                       sources=["VL:" + vl_model_name()], review_id=rid)
    await asyncio.to_thread(get_audit_logger().write, event_type="imaging", action="need_image",
                            actor=actor, payload={"q": q[:200]})
    return AskResp(answer="影像辅助阅片：请上传 DICOM/JPG/PNG 影像，我将给出结构化初步所见。",
                   confidence=0.0, needs_human_review=False, sources=["imaging:needs_image"],
                   status="need_image")


@router.post("/case/ask", response_model=AskResp)
async def case_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type="case", action="prefilter",
                                actor=actor, payload={"q": q[:200]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        try:
            from backend.core.medical_imaging import CASE_PROMPT, describe_images, vl_model_name
            # F1：病例总结用病例专用 prompt，不再带放射科阅片人设
            desc = await describe_images(imgs, "结合病例信息描述关键发现：" + q,
                                         system_prompt=CASE_PROMPT)
        except Exception as e:  # noqa: BLE001
            await asyncio.to_thread(get_audit_logger().write, event_type="case", action="vl_failed",
                                    actor=actor, payload={"err": str(e)[:120]})
            raise HTTPException(500, "多模态解析服务暂时不可用，请稍后重试")
        risky = _has_risk(desc)
        conf = 0.60 if risky else 0.70
        rid = await _enqueue_if_risk("case", q, desc, conf, risky, "病例含高危征象", actor, images=imgs)
        await asyncio.to_thread(get_audit_logger().write, event_type="case", action="vl_described",
                                actor=actor, payload={"len": len(desc), "risky": risky})
        return AskResp(answer=desc + ("\n\n（已标记双人核对）" if risky else "\n\n供医师参考的初步摘要。"),
                       confidence=conf, needs_human_review=risky,
                       sources=["VL:" + vl_model_name()], review_id=rid)
    await asyncio.to_thread(get_audit_logger().write, event_type="case", action="scaffold",
                            actor=actor, payload={"q": q[:200]})
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
    rid = await _enqueue_if_risk("mdt", case, rep["text"], rep["confidence"],
                           rep.get("needs_human_review", False), "MDT存在分歧或高危", actor)
    return {"report": rep["text"], "confidence": rep["confidence"], "opinions": rep["opinions"],
            "disagreements": rep["disagreements"], "needs_human_review": rep["needs_human_review"],
            "sources": rep["sources"], "review_id": rid,
            "headline": rep.get("headline", ""), "urgency": rep.get("urgency", "中"),
            "key_actions": rep.get("key_actions", [])}


# ---------- 审核中心（高危双人核对）----------
@router.get("/review/pending")
async def review_pending(user: dict = Depends(require_user)):
    items = await asyncio.to_thread(review.pending)
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="list_pending",
                            actor=user.get("username", "system"), payload={"n": len(items)})
    return {"pending": items, "me": user.get("username")}


@router.get("/review/history")
async def review_history(user: dict = Depends(require_user)):
    items = await asyncio.to_thread(review.list_all, limit=50)
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="history",
                            actor=user.get("username", "system"), payload={"n": len(items)})
    return {"items": items}


@router.post("/review/{rid}/resolve")
async def review_resolve(rid: str, body: ResolveReq, user: dict = Depends(require_user)):
    reviewer = user.get("username", "system")
    try:
        item = await asyncio.to_thread(review.resolve, rid, body.decision, reviewer, body.note)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action=f"resolved_{body.decision}",
                            actor=reviewer, payload={"rid": rid})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit(f"review.{body.decision}", reviewer, {"rid": rid})
    return item


@router.post("/review/{rid}/reopen")
async def review_reopen(rid: str, user: dict = Depends(require_user)):
    """翻案（转人工复核）：已处理（含 AI·阈值自动签发）条目回到待核对，清除签署信息。
    双控对称：操作人不能是提交人本人（管理员/其他审核人可翻案）。翻案本身记审计留痕。"""
    operator = user.get("username", "system")
    try:
        item = await asyncio.to_thread(review.reopen, rid, operator)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="reopened",
                            actor=operator, payload={"rid": rid})
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


@router.post("/qc/record", response_model=AskResp)
async def qc_record(req: QcReq, user: dict = Depends(require_user)):
    actor = user.get("username", "system")
    dept = user.get("dept", "")
    if not dept:  # 科室由服务端绑定（签名/科室同源权威），未设置科室拒绝出报告
        raise HTTPException(400, "请先联系管理员设置科室")
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
    text = "\n".join(lines)
    await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="query", actor=actor,
                            payload={"fields": len(req.record), "labs": len(req.labs or {})})
    if settings.qc_auto_pass:
        # 三档分流：①确定性硬伤 → 自动驳回不入队；②硬伤全过且置信 ≥0.85 → 自动归档不入队；
        # ③其余（内涵轨缺陷/危急值）→ 走现行为入队人工终审。默认关闭，行为完全不变。
        hard = out.get("hard_defects") or []
        if hard:
            await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="auto_reject",
                                    actor=actor, payload={"hard": hard[:8]})
            return AskResp(answer="⛔ 自动驳回（AI 预筛）：存在确定性硬伤：" + "；".join(hard),
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None, status="auto_rejected")
        if out["confidence"] >= 0.85:
            await asyncio.to_thread(get_audit_logger().write, event_type="qc", action="auto_pass",
                                    actor=actor, payload={"conf": out["confidence"]})
            return AskResp(answer=text + f"\n\n✅ AI 预审通过（置信 {out['confidence']:.2f}），已归档",
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None)
    rid = await _enqueue_if_risk("qc", "病历质控", text, out["confidence"], True, "病案质控终审", actor)
    return AskResp(answer=text, confidence=out["confidence"], needs_human_review=True,
                   sources=sources, review_id=rid)


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
                            payload={"len": len(req.text)})
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


# ---------- 知识库管理（仅 admin）----------
class KbUploadReq(BaseModel):
    name: str = Field(max_length=250)
    data_b64: str = Field(max_length=30_000_000)  # 解码后约 22MB 上限


@router.post("/admin/kb/upload")
async def kb_upload(req: KbUploadReq, user: dict = Depends(require_role("admin"))):
    await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload",
                            actor=user.get("username", "admin"), payload={"name": req.name[:120]})
    try:
        return await asyncio.to_thread(kb_ingest.ingest_b64, req.name[:250], req.data_b64)
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="upload_failed",
                                actor=user.get("username", "admin"),
                                payload={"name": req.name[:120], "err": str(e)[:160]})
        raise HTTPException(400, "文件摄取失败：请确认格式为 .md/.txt/.pdf 且内容可解析")


@router.get("/admin/kb")
async def kb_list(user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.list_docs)


@router.get("/admin/kb/{tag}/preview")
async def kb_preview(tag: str, user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.preview_doc, tag)


@router.delete("/admin/kb/{tag}")
async def kb_delete(tag: str, user: dict = Depends(require_role("admin"))):
    await asyncio.to_thread(get_audit_logger().write, event_type="kb", action="delete",
                            actor=user.get("username", "admin"), payload={"tag": tag})
    return {"deleted": await asyncio.to_thread(kb_ingest.delete_doc, tag)}


# ---------- admin 用户管理 ----------
class UserCreateReq(BaseModel):
    username: str
    password: str
    role: str
    dept: str = Field(default="", max_length=40)  # 所属科室（质控签名/科室绑定用）


@router.post("/admin/users")
async def admin_create_user(body: UserCreateReq, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import create_user, get_user
    uname = (body.username or "").strip()
    if not (3 <= len(uname) <= 32) or not re.fullmatch(r"[A-Za-z0-9_\-]+", uname):
        raise HTTPException(400, "用户名需 3-32 位，仅字母/数字/下划线/连字符")
    if len(body.password or "") < 8:
        raise HTTPException(400, "密码至少 8 位")
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
async def admin_data(user: dict = Depends(require_role("admin"))):
    """管理员只读数据面板：用户、审计流、队列统计、PG 镜像与知识库规模。"""
    from backend.core.auth import all_users
    from backend.core.medical_kb import doc_count
    from backend.core.embedder import device
    users = [{"username": u, "role": rec.get("role", ""), "created": rec.get("created", ""),
              "dept": rec.get("dept", "")}
             for u, rec in (await asyncio.to_thread(all_users)).items()]
    items = await asyncio.to_thread(review.list_all)
    stats = {"pending": sum(1 for i in items if i.get("status") == "pending"),
             "approved": sum(1 for i in items if i.get("status") == "approved"),
             "rejected": sum(1 for i in items if i.get("status") == "rejected")}
    al = get_audit_logger()
    # F5：保留完整 ISO 串（含 +00:00 偏移）。此前 [:19] 切掉时区后，前端 new Date 按
    # 本地时区解析 UTC 壁钟，审计流时间比本地慢 8 小时；fmtTs 依赖时区信息做本地化。
    audit_recent = [{"ts": e["ts"], "event": e["event_type"],
                     "action": e["payload"].get("action", ""), "actor": e["actor"],
                     "payload": e.get("payload", {})}  # 完整 payload 下传，供前端人话化详情
                    for e in al.recent(50)]
    try:
        pg_counts = await pg_store.counts()
    except Exception:  # noqa: BLE001
        pg_counts = {}
    return {"users": users, "audit_recent": audit_recent, "review": stats,
            "pg_counts": pg_counts, "kb_docs": await asyncio.to_thread(doc_count),
            "events_total": len(al.entries), "device": device(), "uptime_s": int(time.time() - _START)}


class PurgeReq(BaseModel):
    confirm_text: str = ""  # 必须为「清空」，服务端强制校验，杜绝前端误触/绕过


@router.post("/admin/review/purge-pending")
async def admin_purge_pending(body: PurgeReq, user: dict = Depends(require_role("admin"))):
    """清空全部待核对项（保留已处理历史），用于清理测试数据。需确认词「清空」。"""
    if (body.confirm_text or "").strip() != "清空":
        raise HTTPException(400, "确认词不符：请输入「清空」以确认执行")
    n = await asyncio.to_thread(review.purge_pending)
    await asyncio.to_thread(get_audit_logger().write, event_type="review", action="purge_pending",
                            actor=user.get("username", "admin"), payload={"count": n})
    await pg_store.mirror_audit("review.purge_pending", user.get("username", "admin"), {"count": n})
    await pg_store.purge_pending_reviews()
    return {"purged": n}


# ---------- admin 模型配置管理（F6：对话/视觉双通道，openai/anthropic 双格式）----------
class LlmProviderReq(BaseModel):
    kind: str                       # chat | vision
    api_format: str                 # openai | anthropic
    base_url: str = Field(max_length=2000)
    model_id: str = Field(max_length=200)
    display_name: str = Field(default="", max_length=200)
    api_key: str = Field(default="", max_length=2000)


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
            req.model_id, req.display_name, req.api_key)
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


@router.post("/admin/llm/test")
async def llm_test(req: LlmProviderReq, user: dict = Depends(require_role("admin"))):
    """连通性测试（不落盘）。返回 {"ok","latency_ms","detail"}，detail 不含 api_key。"""
    r = await asyncio.to_thread(llm_config.test_provider, req.api_format, req.base_url,
                                req.model_id, req.api_key)
    await asyncio.to_thread(get_audit_logger().write, event_type="admin", action="llm_test",
                            actor=user.get("username", "admin"),
                            payload={"kind": req.kind if req.kind in ("chat", "vision") else None,
                                     "api_format": req.api_format, "model_id": req.model_id[:80],
                                     "ok": r["ok"]})
    return r
