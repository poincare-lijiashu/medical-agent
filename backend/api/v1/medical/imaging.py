"""影像辅助阅片 + 多模态病例总结端点（W4.3 router 拆分）。

共用 VL 描述流程（_vl_describe_flow）在 shared.py，两端点语义经参数化区分。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends

from backend.api.deps import model_quota, rate_limit, require_user
from backend.api.v1.medical.schemas import AUDIT_Q_SNIPPET, AskReq, AskResp
from backend.api.v1.medical import shared as _shared
from backend.api.v1.medical.shared import (_ask_prefilter, _vl_describe_flow,
                                           redactor as _redactor)

router = APIRouter(dependencies=[Depends(rate_limit)])


@router.post("/imaging/ask", response_model=AskResp)
async def imaging_ask(req: AskReq, user: dict = Depends(require_user),
                      _quota: None = Depends(model_quota)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = await _ask_prefilter("imaging", q, actor)
    if pre:
        return pre
    imgs = req.images or ([req.image] if req.image else [])
    if imgs:
        return await _vl_describe_flow("imaging", imgs, q, actor, question=q)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="imaging", action="need_image",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
    return AskResp(answer="影像辅助阅片：请上传 DICOM/JPG/PNG 影像，我将给出结构化初步所见。",
                   confidence=0.0, needs_human_review=False, sources=["imaging:needs_image"],
                   status="need_image")


@router.post("/case/ask", response_model=AskResp)
async def case_ask(req: AskReq, user: dict = Depends(require_user),
                   _quota: None = Depends(model_quota)):
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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case", action="scaffold",
                            actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
    return AskResp(answer="多模态病例总结：粘贴病例要点（可附检验/影像图片），我生成结构化摘要与待鉴别清单。",
                   confidence=0.0, needs_human_review=False, sources=["case:needs_input"],
                   status="need_input")
