"""MDT 多智能体会诊 + 跨科室真实会诊流转端点（W4.3 router 拆分）。

- /mdt/consult：AI 多智能体模拟会诊（MDT 图懒加载单例，双检锁）。
- /consults/*：真实流转到目标科室在职医生（Agent 动态组队 → 分发 → 各科意见 → 结束汇总）。

测试 monkeypatch「backend.api.v1.medical.consults._mdt_graph」替换替身图。
"""
from __future__ import annotations

import asyncio
import threading
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from backend.api.deps import rate_limit, require_user
from backend.api.v1.medical.schemas import AskReq
from backend.api.v1.medical import shared as _shared
from backend.api.v1.medical.shared import _enqueue_if_risk, redactor as _redactor
from backend.core import pg_store
from backend.core.intent import offtopic_tech_reply

router = APIRouter(dependencies=[Depends(rate_limit)])

_mdt_graph = None
_graph_lock = threading.Lock()  # 懒加载双检锁：并发首访只构建一次，防重复构建竞态


def _get_mdt_graph():
    global _mdt_graph
    if _mdt_graph is None:
        with _graph_lock:
            if _mdt_graph is None:  # 双检：拿到锁后再确认，避免重复构建
                from backend.agents.medical.mdt import build_mdt_graph
                _mdt_graph = build_mdt_graph()
    return _mdt_graph


class MdtReq(BaseModel):
    case: str = Field(max_length=20000)


@router.post("/mdt/consult")
async def mdt_consult(req: MdtReq, user: dict = Depends(require_user)):
    case = _redactor.redact(req.case)
    actor = user.get("username", "system")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="mdt", action="query",
                            actor=actor, payload={"len": len(case)})
    try:
        g = _get_mdt_graph()
        sid = "mdt-" + uuid.uuid4().hex[:12]
        out = await g.ainvoke({"session_id": sid, "case": case, "opinions": []},
                              config={"configurable": {"thread_id": sid}})
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="mdt", action="degraded",
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
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="prefilter_reject",
                                actor=actor, payload={"len": len(q), "role": user.get("role", "")})
        raise HTTPException(400, "请补充患者病史/症状等病例信息后再发起会诊")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="query",
                            actor=actor, payload={"len": len(q), "role": user.get("role", "")})
    try:
        # 轮 B1（MDT 传图）：带图时组队/定向意见节点自动切 VL 模型（读影像判断专科/引用所见）
        team = await pick_departments(q, req.images)  # Agent 动态组队（实时科室清单，绝不缓存）
        briefs = await dept_briefs(q, team["departments"], req.images)  # 按科室定向初步意见
    except consults.ConsultError as e:
        # 诊断4：组队兜底不再全量召集（宁可失败也不乱分发）→ 400 明确引导补充病例
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="team_failed",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(400, str(e))
    except Exception as e:  # noqa: BLE001 —— 组队链路异常体面降级，不裸 500
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="degraded",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(502, "会诊组队服务暂时不可用（多为瞬时），已记录审计；请稍后重试")
    ai_analysis = "\n\n".join(f"【{dep}】{txt}" for dep, txt in briefs.items())
    cid = await asyncio.to_thread(
        consults.create, initiator=actor, question=q, ai_analysis=ai_analysis,
        target_depts=team["departments"], team=team, images=req.images,
        initiator_dept=user.get("dept", ""))
    item = await asyncio.to_thread(consults.get, cid)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="consult_created",
                            actor=actor, payload={"cid": cid, "role": user.get("role", ""),
                                                  "depts": team["departments"],
                                                  # 轮 B1：随单入库影像张数（images_count 审计留痕）
                                                  "images_count": len(item.get("images") or [])})
    # Agent 决策点审计：组队结果（选中科室+理由）留痕，可回溯「为什么召集这些科室」
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="consult_dispatch",
                            actor=actor, payload={"cid": cid, "role": user.get("role", ""),
                                                  "departments": team["departments"],
                                                  "reasoning": team.get("reasoning", "")})
    await pg_store.mirror_audit("consult.created", actor, {"cid": cid})
    return item


@router.get("/consults/inbox")
async def consult_inbox(
        silent: bool = False,
        limit: int | None = Query(None, ge=0, le=200,
                                  description="返回条数上限；缺省=全部（旧行为）。0=只取计数"),
        offset: int = Query(0, ge=0, description="跳过条数"),
        summary: bool = Query(False, description="只回计数（items=[]），供角标轮询"),
        q: str = Query("", max_length=100,
                       description="关键字过滤（病例/AI 意见/发起人/受邀科室/已填意见，子串）"),
        user: dict = Depends(require_user)):
    """「其它科室会诊协助」收件箱：当前医生 dept 匹配的 open 会诊单
    （服务端按 dept 实时过滤隔离；账号未设置科室则恒为空）。
    silent=true 供前端角标轮询（读操作降噪）：跳过 list_inbox 审计写入，
    数据返回与非 silent 完全一致；视图内刷新不传 silent，业务审计照旧保留。
    第 77 批：新增 `limit/offset/summary` 与 `total/pendingForMe`——角标只需
    "本科室还有几单未填意见"，此前每 60s 把整个收件箱（含影像/意见）拉回前端再过滤。
    `pendingForMe` 由服务端按本科室意见计算（与前端原 `opinions.some(dept)` 同口径）。
    第 111 批：`q` 在 dept 隔离之后、分页之前过滤（total/pendingForMe 同口径）；
    审计只记"是否带关键字"（不记录关键字本身，避免病例内容进审计）。
    角标轮询（summary=1）不传 q，口径保持"整箱待我填"。
    """
    from backend.core import consults
    me = user.get("username", "system")
    dept = user.get("dept", "")
    q_f = (q or "").strip()[:100]
    # 第 116 批：q 下推到 PG（consults.inbox_for 内部拼条件），不再先拉全箱再过滤
    items = await asyncio.to_thread(consults.inbox_for, dept, q_f) if dept else []
    total = len(items)
    pending_for_me = sum(
        1 for c in items
        if not any((o or {}).get("dept") == dept for o in (c.get("opinions") or []))
    )
    page = [] if summary else (
        items[offset:offset + limit] if limit is not None else items[offset:])
    if not silent:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="list_inbox",
                                actor=me, payload={"n": len(page), "total": total, "dept": dept,
                                                   "role": user.get("role", ""),
                                                   "limit": limit, "offset": offset,
                                                   "summary": summary, "q": bool(q_f)})
    return {"items": page, "me": me, "dept": dept, "total": total,
            "pendingForMe": pending_for_me}


@router.get("/consults/mine")
async def consult_mine(user: dict = Depends(require_user),
                       q: str = Query("", max_length=100,
                                      description="关键字过滤（病例/AI 意见/受邀科室/已填意见）"),
                       limit: int | None = Query(None, ge=0, le=200,
                                                 description="每侧返回条数上限；缺省=全部（旧行为）"),
                       offset: int = Query(0, ge=0, description="跳过条数（两侧共用）")):
    """发起者视角：我发起的全部会诊（含已结束）+ 我参与过的（作为受邀科室填过意见）。
    数据隔离由服务端过滤保证。第 111 批：`q` 在两侧列表上同口径过滤（审计只记是否带关键字）。
    第 119 批：`limit/offset` 服务端分页（**两侧共用同一页**，返回各自 total）；
    缺省 `limit=None` 时保持旧的整表返回，前端旧调用不受影响。
    第 121 批：切片与 total 一起下推 PG（不再"取全量再内存切片"）。"""
    from backend.core import consults
    me = user.get("username", "system")
    q_f = (q or "").strip()[:100]
    initiated, init_total = await asyncio.to_thread(
        consults.by_initiator_page, me, q_f, limit, offset)
    participated, part_total = await asyncio.to_thread(
        consults.participated_page, me, q_f, limit, offset)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="list_mine",
                            actor=me, payload={"initiated": len(initiated),
                                               "participated": len(participated),
                                               "initiatedTotal": init_total,
                                               "participatedTotal": part_total,
                                               "role": user.get("role", ""), "q": bool(q_f),
                                               "limit": limit, "offset": offset})
    return {"initiated": initiated, "participated": participated, "me": me,
            "initiatedTotal": init_total, "participatedTotal": part_total,
            "offset": offset, "limit": limit}


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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="consult_opinion",
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
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="consult", action="consult_closed",
                            actor=me, payload={"cid": cid, "role": user.get("role", ""),
                                               "opinions": len(item.get("opinions") or [])})
    await pg_store.mirror_audit("consult.closed", me, {"cid": cid})
    return item
