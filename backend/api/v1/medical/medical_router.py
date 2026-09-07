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
from pydantic import BaseModel, Field, field_validator

from backend.api.deps import require_role, require_user, rate_limit, revoke_user
from backend.config import settings
from backend.core import kb_ingest
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
    """前端据此动态显示真实模型名（不再硬编码）。"""
    return {"chat_model": settings.qwen_model_chat, "vl_model": settings.qwen_model_vl,
            "reasoning_model": settings.qwen_model_reasoning}


@router.get("/overview")
async def overview(user: dict = Depends(require_user)):
    """概览面板：真实运行与合规指标（不编造）。"""
    from backend.core.embedder import device
    from backend.core.medical_kb import doc_count
    items = review.list_all()
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
                           risk_reason: str, actor: str) -> str | None:
    await pg_store.mirror_audit(f"{agent}.answer", actor, {"conf": conf, "review": review_flag})
    if not review_flag:
        return None
    rid = review.submit(agent=agent, question=q, answer=answer, confidence=conf,
                        risk_reason=risk_reason, submitted_by=actor)
    get_audit_logger().write(event_type=agent, action="review_enqueued", actor=actor,
                             payload={"rid": rid, "reason": risk_reason})
    await pg_store.mirror_review(review.get(rid))
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
        get_audit_logger().write(event_type="literature", action="prefilter", actor=actor,
                                 payload={"q": q[:60]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False, sources=["intent:prefilter"])
    get_audit_logger().write(event_type="literature", action="query", actor=actor, payload={"q": q[:200]})
    try:
        g = _get_lit_graph()
        sid = "lit-" + uuid.uuid4().hex[:12]
        out = await g.ainvoke({"session_id": sid, "question": q, "query": q, "attempts": 0},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001 —— 三层兑底：provider/检索异常→体面降级不裸500
        get_audit_logger().write(event_type="literature", action="degraded", actor=actor,
                                 payload={"err": str(e)[:120]})
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
    get_audit_logger().write(event_type="literature", action="stream", actor=actor,
                             payload={"q": q[:200]})

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
            get_audit_logger().write(event_type="literature", action="stream_error", actor=actor,
                                     payload={"err": str(e)[:160]})
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
    r = drug_check(q)
    ans = r["answer"]
    sev = r.get("max_severity", "无")
    if sev in _DRUG_SEV_CONF:
        conf, risk, review_flag = _DRUG_SEV_CONF[sev]
    elif len(r.get("drugs") or []) >= 2:  # max_severity="无" 且识别 ≥2 药 → 规则库未收录（旧 "未收录" 分支取值）
        conf, risk, review_flag = 0.55, "未知相互作用", False
    else:  # 识别不足 2 药（旧 else 分支取值）
        conf, risk, review_flag = 0.50, "信息不足", False
    rid = await _enqueue_if_risk("drug", q, ans, conf, review_flag, risk, actor)
    get_audit_logger().write(event_type="drug", action="answered", actor=actor,
                             payload={"conf": conf, "review": review_flag})
    return AskResp(answer=ans, confidence=conf, needs_human_review=review_flag,
                   sources=r["sources"], review_id=rid)


@router.post("/imaging/ask", response_model=AskResp)
async def imaging_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        try:
            from backend.core.medical_imaging import describe_images, vl_model_name
            desc = await describe_images(imgs, q)
        except Exception as e:  # noqa: BLE001
            get_audit_logger().write(event_type="imaging", action="describe_failed", actor=actor,
                                     payload={"err": str(e)[:120]})
            raise HTTPException(500, "影像描述服务暂时不可用，请稍后重试")
        risky = _has_risk(desc)
        conf = 0.60 if risky else 0.72
        rid = await _enqueue_if_risk("imaging", q, desc, conf, risky, "影像可疑高危征象", actor)
        suffix = "（已标记为需放射科医师双人核对）" if risky else "\n\n以上为 AI 初步所见，供医师参考。"
        get_audit_logger().write(event_type="imaging", action="vl_described", actor=actor,
                                 payload={"len": len(desc), "risky": risky})
        return AskResp(answer=desc + suffix, confidence=conf, needs_human_review=risky,
                       sources=["VL:" + vl_model_name()], review_id=rid)
    get_audit_logger().write(event_type="imaging", action="need_image", actor=actor,
                             payload={"q": q[:200]})
    return AskResp(answer="影像辅助阅片：请上传 DICOM/JPG/PNG 影像，我将给出结构化初步所见。",
                   confidence=0.0, needs_human_review=False, sources=["imaging:needs_image"],
                   status="need_image")


@router.post("/case/ask", response_model=AskResp)
async def case_ask(req: AskReq, user: dict = Depends(require_user)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        try:
            from backend.core.medical_imaging import describe_images, vl_model_name
            desc = await describe_images(imgs, "结合病例信息描述关键发现：" + q)
        except Exception as e:  # noqa: BLE001
            get_audit_logger().write(event_type="case", action="vl_failed", actor=actor,
                                     payload={"err": str(e)[:120]})
            raise HTTPException(500, "多模态解析服务暂时不可用，请稍后重试")
        risky = _has_risk(desc)
        conf = 0.60 if risky else 0.70
        rid = await _enqueue_if_risk("case", q, desc, conf, risky, "病例含高危征象", actor)
        get_audit_logger().write(event_type="case", action="vl_described", actor=actor,
                                 payload={"len": len(desc), "risky": risky})
        return AskResp(answer=desc + ("\n\n（已标记双人核对）" if risky else "\n\n供医师参考的初步摘要。"),
                       confidence=conf, needs_human_review=risky,
                       sources=["VL:" + vl_model_name()], review_id=rid)
    get_audit_logger().write(event_type="case", action="scaffold", actor=actor, payload={"q": q[:200]})
    return AskResp(answer="多模态病例总结：粘贴病例要点（可附检验/影像图片），我生成结构化摘要与待鉴别清单。",
                   confidence=0.0, needs_human_review=False, sources=["case:needs_input"],
                   status="need_input")


class MdtReq(BaseModel):
    case: str = Field(max_length=20000)


@router.post("/mdt/consult")
async def mdt_consult(req: MdtReq, user: dict = Depends(require_user)):
    case = _redactor.redact(req.case)
    actor = user.get("username", "system")
    get_audit_logger().write(event_type="mdt", action="query", actor=actor,
                             payload={"len": len(case)})
    try:
        g = _get_mdt_graph()
        sid = "mdt-" + uuid.uuid4().hex[:12]
        out = await g.ainvoke({"session_id": sid, "case": case, "opinions": []},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001
        get_audit_logger().write(event_type="mdt", action="degraded", actor=actor,
                                 payload={"err": str(e)[:120]})
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
    get_audit_logger().write(event_type="review", action="list_pending",
                             actor=user.get("username", "system"), payload={})
    return {"pending": review.pending(), "me": user.get("username")}


@router.get("/review/history")
async def review_history(user: dict = Depends(require_user)):
    return {"items": review.list_all(limit=50)}


@router.post("/review/{rid}/resolve")
async def review_resolve(rid: str, body: ResolveReq, user: dict = Depends(require_user)):
    reviewer = user.get("username", "system")
    try:
        item = review.resolve(rid, body.decision, reviewer, body.note)
    except review.ReviewError as e:
        raise HTTPException(400, str(e))
    get_audit_logger().write(event_type="review", action=f"resolved_{body.decision}",
                             actor=reviewer, payload={"rid": rid})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit(f"review.{body.decision}", reviewer, {"rid": rid})
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
    # PHI 脱敏：病历内容入 LLM/队列前逐字段处理（与其它 agent 同纪律）。
    # lab 键名是已知的医学项目名（如 血钾=6.8），不应被当手机号脱敏；仅对值脱敏并限制键名白名单外的处置。
    known_lab_keys = {"血钾", "钾", "血钠", "钠", "血钙", "血糖", "血氯", "血肌酐", "肌酐",
                      "尿素氮", "BUN", "血白蛋白", "白蛋白", "ALT", "AST", "WBC", "Hb", "PLT"}
    redacted = {k: _redactor.redact(str(v)) for k, v in (req.record or {}).items()}
    labs = {}
    for k, v in (req.labs or {}).items():
        if k in known_lab_keys:
            labs[k] = str(v)  # 已知项目：值保持原值（数值需送 critical_value_check）
        else:
            labs[k] = _redactor.redact(str(v))  # 其它（姓名/电话/身份证）走脱敏
    icd_table = _load_optional_table("icd_table.json")        # 需院方提供，否则 ICD 轨未启用
    thresholds = _load_optional_table("critical_values.json")  # 需院方提供，不臆造安全阈值
    out = await asyncio.to_thread(qc.review_quality, redacted, icd_table, thresholds, labs)
    lines = [f"【病案质控·AI建议】完整性缺 {len(out['completeness'])} 项、内涵缺陷 {len(out['connotation'])} 项；"
             f"ICD {out['icd']['note'] if not out['icd']['enabled'] else '已核对'}；"
             f"危急值 {out['critical']['note'] if not out['critical']['enabled'] else ('标记'+str(len(out['critical']['flags']))+'项')}。"]
    for d in out["defects"][:12]:
        lines.append(f"- [{d['track']}/{d['level']}] {d.get('field','')}：{d.get('issue','')}")
    for f in out["critical"].get("flags", []):
        lines.append(f"- [危急值] {f['item']}={f['value']} {f['issue']}")
    text = "\n".join(lines)
    get_audit_logger().write(event_type="qc", action="query", actor=actor,
                             payload={"fields": len(req.record)})
    rid = await _enqueue_if_risk("qc", "病历质控", text, out["confidence"], True, "病案质控终审", actor)
    return AskResp(answer=text, confidence=out["confidence"], needs_human_review=True,
                   sources=["track:完整性", "track:内涵质量", "track:ICD", "track:危急值"], review_id=rid)


# ---------- 知识库管理（仅 admin）----------
class KbUploadReq(BaseModel):
    name: str = Field(max_length=250)
    data_b64: str = Field(max_length=30_000_000)  # 解码后约 22MB 上限


@router.post("/admin/kb/upload")
async def kb_upload(req: KbUploadReq, user: dict = Depends(require_role("admin"))):
    get_audit_logger().write(event_type="kb", action="upload",
                             actor=user.get("username", "admin"), payload={"name": req.name[:120]})
    try:
        return await asyncio.to_thread(kb_ingest.ingest_b64, req.name[:250], req.data_b64)
    except Exception as e:  # noqa: BLE001
        get_audit_logger().write(event_type="kb", action="upload_failed",
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
    get_audit_logger().write(event_type="kb", action="delete",
                             actor=user.get("username", "admin"), payload={"tag": tag})
    return {"deleted": await asyncio.to_thread(kb_ingest.delete_doc, tag)}


# ---------- admin 用户管理 ----------
class UserCreateReq(BaseModel):
    username: str
    password: str
    role: str


@router.post("/admin/users")
async def admin_create_user(body: UserCreateReq, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import create_user, get_user
    uname = (body.username or "").strip()
    if not (3 <= len(uname) <= 32) or not re.fullmatch(r"[A-Za-z0-9_\-]+", uname):
        raise HTTPException(400, "用户名需 3-32 位，仅字母/数字/下划线/连字符")
    if len(body.password or "") < 8:
        raise HTTPException(400, "密码至少 8 位")
    try:
        ok = await asyncio.to_thread(create_user, uname, body.password, body.role)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(400, "用户名已存在")
    get_audit_logger().write(event_type="admin", action="user_created",
                             actor=user.get("username", "admin"),
                             payload={"user": uname, "role": body.role})
    await pg_store.mirror_audit("admin.user_created", user.get("username", "admin"), {"user": uname})
    rec = get_user(uname)
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
    get_audit_logger().write(event_type="admin", action="user_deleted",
                             actor=user.get("username", "admin"), payload={"user": username})
    await pg_store.mirror_audit("admin.user_deleted", user.get("username", "admin"), {"user": username})
    return {"ok": True}


# ---------- admin 运维：数据面板 / 队列清理（只读优先，不做裸 SQL） ----------
@router.get("/admin/data")
async def admin_data(user: dict = Depends(require_role("admin"))):
    """管理员只读数据面板：用户、审计流、队列统计、PG 镜像与知识库规模。"""
    from backend.core.auth import all_users
    from backend.core.medical_kb import doc_count
    from backend.core.embedder import device
    users = [{"username": u, "role": rec.get("role", ""), "created": rec.get("created", "")}
             for u, rec in all_users().items()]
    items = review.list_all()
    stats = {"pending": sum(1 for i in items if i.get("status") == "pending"),
             "approved": sum(1 for i in items if i.get("status") == "approved"),
             "rejected": sum(1 for i in items if i.get("status") == "rejected")}
    al = get_audit_logger()
    audit_recent = [{"ts": e["ts"][:19], "event": e["event_type"],
                     "action": e["payload"].get("action", ""), "actor": e["actor"]}
                    for e in al.recent(50)]
    try:
        pg_counts = await pg_store.counts()
    except Exception:  # noqa: BLE001
        pg_counts = {}
    return {"users": users, "audit_recent": audit_recent, "review": stats,
            "pg_counts": pg_counts, "kb_docs": doc_count(),
            "events_total": len(al.entries), "device": device(), "uptime_s": int(time.time() - _START)}


class PurgeReq(BaseModel):
    confirm_text: str = ""  # 必须为「清空」，服务端强制校验，杜绝前端误触/绕过


@router.post("/admin/review/purge-pending")
async def admin_purge_pending(body: PurgeReq, user: dict = Depends(require_role("admin"))):
    """清空全部待核对项（保留已处理历史），用于清理测试数据。需确认词「清空」。"""
    if (body.confirm_text or "").strip() != "清空":
        raise HTTPException(400, "确认词不符：请输入「清空」以确认执行")
    n = await asyncio.to_thread(review.purge_pending)
    get_audit_logger().write(event_type="review", action="purge_pending",
                             actor=user.get("username", "admin"), payload={"count": n})
    await pg_store.mirror_audit("review.purge_pending", user.get("username", "admin"), {"count": n})
    await pg_store.purge_pending_reviews()
    return {"purged": n}
