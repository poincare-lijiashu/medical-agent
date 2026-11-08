"""审核中心端点（W4.3 router 拆分）：高危双人核对队列（pending/history/resolve/reopen…）。

签字权矩阵（任务2 收权 + 问题4 收窄 + 药剂科一票）见 _REVIEW_ROLES/_RESOLVE_ROLES/
_DRUG_RESOLVE_ROLES；图片可见性统一规则见 _review_image_view。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from backend.api.deps import rate_limit, require_role, require_user
from backend.api.v1.medical import shared as _shared
from backend.core import case_archive  # 阶段4：病例库合规归档（qc approve 自动归档挂点）
from backend.core import pg_store
from backend.core import medical_review as review

router = APIRouter(dependencies=[Depends(rate_limit)])


class ResolveReq(BaseModel):
    decision: str  # approved | rejected
    note: str = ""


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


def _match_query(item: dict, q: str) -> bool:
    """列表关键字匹配：与前端旧实现同字段（助手/问题/提交人/风险原因），大小写不敏感。"""
    return q in " ".join(str(item.get(k) or "") for k in
                         ("agent", "question", "submitted_by", "risk_reason")).lower()


_PREVIEW_CHARS = 160


def _compact_item(item: dict) -> dict:
    """列表瘦身（第 69 批）：去掉 answer 与 images，改下发预览长度/影像张数。

    实测 52 条待核对 = 212KB，其中 base64 影像 105KB、回答全文 31KB；列表只需要
    摘要，全文与影像按需走 GET /review/item/{rid}。影像按 _review_image_view 已做
    角色收窄后再计数，因此医生看他人条目时 imageCount=0（与其可见性一致）。"""
    out = {k: v for k, v in item.items() if k not in ("answer", "images")}
    answer = str(item.get("answer") or "")
    out["answerPreview"] = answer[:_PREVIEW_CHARS]
    out["answerLength"] = len(answer)
    out["imageCount"] = len(item.get("images") or [])
    return out


@router.get("/review/pending")
async def review_pending(
        limit: int | None = Query(None, ge=0, le=500,
                                  description="返回条数上限；缺省=全部（旧行为）。0=只取计数"),
        offset: int = Query(0, ge=0, description="跳过条数（与 limit 配套分页）"),
        q: str | None = Query(None, max_length=100, description="关键字筛选（服务端过滤后再分页）"),
        compact: bool = Query(False, description="列表瘦身：省略 answer/images，改下发预览/张数（详情走 /review/item/{rid}）"),
        user: dict = Depends(require_role(*_REVIEW_ROLES))):
    """待核对列表（角色过滤 + 可选关键字/分页/瘦身）。

    第 63 批：/review/pending 实测 76 条 = 229KB，而侧边角标每 60s 轮询只为拿一个数字。
    新增 ``limit/offset/q`` 与响应 ``total``（**缺省仍返回全部**，旧调用方零影响）：
    - 角标/清空确认只传 ``limit=0`` → 响应只有 ``total``（约 100B）；
    - 前端列表可用 ``limit+offset+q`` 做服务端分页/筛选，不再整表下发。
    ``total`` = 角色过滤（qc 剔除 drug）+ 关键字过滤后的条数；``q`` 在服务端过滤，
    因此分页不会漏配（不会出现"第 2 页看不到第 1 页的匹配项"）。
    """
    role = user.get("role", "")
    me = user.get("username", "system")
    keyword = (q or "").strip().lower()
    pg = await asyncio.to_thread(review.page_pending, role == "qc", keyword, limit, offset)
    if pg is not None:  # 第 82 批：status/角色收窄/关键字/分页/计数已在 PG 完成
        page, total = pg["items"], pg["total"]
    else:  # 无池/离线/异常/空表 → 既有内存过滤 + 切片（行为不变）
        items = await asyncio.to_thread(review.pending)
        if role == "qc":  # 问题4 彻底收窄：qc 界面不见 drug 项（含旧遗留），
            items = [i for i in items if i.get("agent") != "drug"]  # drug 审核权=pharmacist/admin
        if keyword:
            items = [i for i in items if _match_query(i, keyword)]
        total = len(items)
        page = items[offset:offset + limit] if limit is not None else items[offset:]
    # 图片可见性统一规则（与 /review/history 一致）：qc 审非 drug 项可见 images（修复
    # 「qc 看不到待审 imaging 图片」）；doctor 他人待审项剥 images（原全量返回，同步收紧）。
    # 只在切片后处理：分页请求不再为整表做图片裁剪。
    page = [_review_image_view(i, role, me) for i in page]
    if compact:  # 第 69 批：full answer/base64 影像不进列表；详情按需单条取
        page = [_compact_item(i) for i in page]
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="list_pending",
                            actor=user.get("username", "system"),
                            payload={"n": len(page), "total": total, "role": role,
                                     "limit": limit, "offset": offset, "compact": compact})
    return {"pending": page, "me": user.get("username"), "total": total}


@router.get("/review/item/{rid}")
async def review_item(rid: str, user: dict = Depends(require_role(*_REVIEW_ROLES))):
    """单条核对详情（第 69 批：compact 列表的内容来源）。

    角色可见性与 /review/pending 完全一致：qc 的 drug 项按「不存在」处理（不通过详情
    接口反推被列表过滤的条目）；图片仍走 _review_image_view 统一收窄（医生看他人条目
    不给影像，提交人本人/admin/该项审核角色可见）。审计只记 rid 与角色，不记内容。
    """
    key = (rid or "").strip()[:64]
    role = user.get("role", "")
    me = user.get("username", "system")
    item = await asyncio.to_thread(review.get, key) if key else None
    if item is None or (role == "qc" and item.get("agent") == "drug"):
        raise HTTPException(404, "复核单不存在")
    item = _review_image_view(item, role, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review",
                            action="detail", actor=me,
                            payload={"rid": key, "role": role})
    return {"item": item}


def _history_view(item: dict, role: str, me: str) -> dict:
    """历史视图的图片合规规则（第 73 批抽出，列表与详情共用）。

    与 /review/pending 不同：**人工签发后不留原图**（FIND-03a），只有 AI 自动签发才可能
    保留影像；doctor/pharmacist 还要求「本人提交」（list_involved 的既有语义）。
    qc/admin 在自动签发记录上再走 _review_image_view（qc 非 drug 可见）。
    """
    auto = review.is_auto_signed(item)
    if role in ("doctor", "pharmacist"):
        if auto and item.get("submitted_by") == me:
            return dict(item)
        return {k: v for k, v in item.items() if k != "images"}
    if not auto:
        item = {k: v for k, v in item.items() if k != "images"}
    return _review_image_view(item, role, me)


@router.get("/review/history")
async def review_history(
        limit: int | None = Query(None, ge=0, le=500,
                                  description="返回条数上限；缺省=全部（旧行为）。0=只取计数"),
        offset: int = Query(0, ge=0, description="跳过条数（与 limit 配套分页）"),
        q: str | None = Query(None, max_length=100, description="关键字筛选（服务端过滤后再分页）"),
        compact: bool = Query(False, description="省略 answer/images，改下发预览/张数"),
        hide_pending: bool = Query(False, description="排除 pending（与前端历史区口径一致）"),
        hide_drug: bool = Query(False, description="排除 agent=drug（历史区统一不展示药物项）"),
        user: dict = Depends(require_role(*_REVIEW_ROLES))):
    """历史列表（第 73 批：新增可选分页/筛选/瘦身，缺省行为与旧版完全一致）。

    语义保持：list_all 按签发方式剥离（人工签发不留原图，AI 自动签发保留）；
    可见性 = 提交人本人 + admin + 该项审核所需角色；doctor/pharmacist 只看本人参与记录。
    新增参数只影响"取哪一页/怎么裁字段"：
    - ``hide_pending`` / ``hide_drug`` 供前端把原本在浏览器做的过滤移到服务端，
      否则分页会出现"当前页不足 12 条"或跨页漏配；
    - ``compact`` 改回 ``answerPreview/answerLength/imageCount``，全文走
      ``GET /review/history/item/{rid}``（历史视图规则与列表同源）。
    """
    me = user.get("username", "system")
    role = user.get("role", "")
    keyword = (q or "").strip().lower()
    # 问题3修复（实测结论）：doctor/pharmacist 的「我的历史」数据源改为服务端按人检索
    # （submitted_by=me OR reviewed_by=me）——原 list_all(limit=50)
    # 按插入序全局截断，真实队列中 pharmacist 处理的 drug 项被其后大量自动签发条目挤出
    # 最新 50 条窗口，前端按 reviewed_by=me 过滤恒空。qc/admin 全局档案语义不变。
    involves = me if role in ("doctor", "pharmacist") else None
    pg = await asyncio.to_thread(review.page_history, involves,
                                 200 if involves else 50, hide_pending, hide_drug,
                                 keyword, limit, offset)
    if pg is not None:  # 第 83 批：基础集合截断 + 过滤 + 计数 + 切片全在 PG（语义与内存一致）
        page, total = pg["items"], pg["total"]
    else:  # 无池/离线/异常/空表 → 既有内存实现（行为不变）
        if involves:
            items = await asyncio.to_thread(review.list_involved, me)
        else:
            items = await asyncio.to_thread(review.list_all, limit=50)
        if hide_pending:
            items = [i for i in items if i.get("status") != "pending"]
        if hide_drug:
            items = [i for i in items if i.get("agent") != "drug"]
        if keyword:
            items = [i for i in items if _match_query(i, keyword)]
        total = len(items)
        page = items[offset:offset + limit] if limit is not None else items[offset:]
    page = [_history_view(i, role, me) for i in page]
    if compact:
        page = [_compact_item(i) for i in page]
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="history",
                            actor=me,
                            payload={"n": len(page), "total": total, "role": role,
                                     "limit": limit, "offset": offset, "compact": compact})
    return {"items": page, "total": total}


@router.get("/review/history/item/{rid}")
async def review_history_item(rid: str, user: dict = Depends(require_role(*_REVIEW_ROLES))):
    """单条历史详情（第 73 批：compact 历史列表的内容来源）。

    与 /review/item/{rid} 的关键差异：**人工签发记录剥 images**（FIND-03a），
    AI 自动签发才按角色可见；doctor/pharmacist 仅限本人参与过的记录（否则 404，
    与列表的数据源 list_involved 一致）。审计只记 rid/角色，不记临床正文。
    """
    key = (rid or "").strip()[:64]
    role = user.get("role", "")
    me = user.get("username", "system")
    item = await asyncio.to_thread(review.get, key) if key else None
    if item is None:
        raise HTTPException(404, "复核单不存在")
    if role in ("doctor", "pharmacist") and me not in (item.get("submitted_by"),
                                                       item.get("reviewed_by")):
        raise HTTPException(404, "复核单不存在")
    item = _history_view(item, role, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review",
                            action="history_detail", actor=me,
                            payload={"rid": key, "role": role})
    return {"item": item}


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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="status",
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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="self_confirmed",
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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action=f"resolved_{body.decision}",
                            actor=reviewer, payload={"rid": rid, "role": user.get("role", "")})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit(f"review.{body.decision}", reviewer, {"rid": rid})
    if item.get("agent") == "qc" and body.decision == "approved":
        # 阶段4.2 归档挂点：质控（qc）approve 成功 → 自动归档病例库。全兜底：失败仅审计
        # case_archive.failed，绝不影响质控主流程（质控结论已签发生效）。
        try:
            entry = await asyncio.to_thread(case_archive.archive_from_review, item)
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case_archive",
                                    action="created", actor=reviewer,
                                    payload={"rid": rid, "arch_id": entry["id"],
                                             "dept": entry.get("dept", ""),
                                             "role": user.get("role", "")})
            await pg_store.mirror_audit("case_archive.created", reviewer,
                                        {"rid": rid, "arch_id": entry["id"]})
        except Exception as e:  # noqa: BLE001 —— 归档失败绝不冒泡
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case_archive",
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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="reopened",
                            actor=operator, payload={"rid": rid, "role": user.get("role", "")})
    await pg_store.mirror_review(item)
    await pg_store.mirror_audit("review.reopened", operator, {"rid": rid})
    return item
