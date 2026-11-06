"""G86 Agent 控制面 API：目录 / A2A 能力卡 / 路由预览 / 工具调用 / 运行时开关。

分层：
- ``/card`` 与 ``/.well-known/agent-card.json``：公开**元数据**（无患者数据、无密钥）；
- ``/catalog``、``/route``、``/tools/*``：登录用户可用，按角色过滤；
- ``/flags``：仅 admin，读写运行时开关并写审计。

A2A：
- ``/a2a`` 实现最小 JSON-RPC ``message/send``（只做**路由任务**，不生成临床结论），
  用于验证跨智能体互操作；完整任务执行仍走业务接口，避免对外承诺未实现的能力。
"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from datetime import date
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import PlainTextResponse, StreamingResponse
from pydantic import BaseModel, Field

from backend.agents import bot_memory, bot_runtime, bot_teams, bots as bot_registry
from backend.agents import catalog, consult, consult_metrics, model_policy, second_opinion, skill_advice, skill_library
from backend.agents import skill_ops, skill_trends, skill_usage, sleep_memory, tool_loop
from backend.agents import skills as skill_cards
from backend.agents import router as router_agent
from backend.api.deps import rate_limit, require_role, require_user
from backend.core import agent_flags, agent_tools, web_sources

router = APIRouter(dependencies=[Depends(rate_limit)])


class RouteReq(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    multiAgent: bool | None = None


class FlagReq(BaseModel):
    key: str = Field(max_length=64)
    value: bool


class SkillPayload(BaseModel):
    code: str = Field(min_length=3, max_length=64)
    name: str = Field(min_length=2, max_length=80)
    description: str = Field(min_length=10, max_length=500)
    agentId: str = Field(min_length=2, max_length=32)
    promptTemplate: str = Field(min_length=20, max_length=4000)
    toolAllowlist: list[str] = Field(default_factory=list, max_length=8)
    evidenceRefs: list[str] = Field(default_factory=list, max_length=10)
    guardrails: dict = Field(default_factory=dict)


class SkillUpdateReq(SkillPayload):
    rowVersion: int = Field(ge=0)


class SkillVersionReq(BaseModel):
    rowVersion: int = Field(ge=0)


class SkillReviewReq(SkillVersionReq):
    approve: bool
    note: str | None = Field(default=None, max_length=2000)


class BotPayload(BaseModel):
    code: str = Field(min_length=3, max_length=64)
    name: str = Field(min_length=2, max_length=80)
    agentId: str = Field(min_length=2, max_length=32)
    persona: str = Field(min_length=20, max_length=2000)
    modelProvider: str = Field(default="default", max_length=64)
    modelId: str | None = Field(default=None, max_length=128)
    skillCodes: list[str] = Field(default_factory=list, max_length=6)
    toolAllowlist: list[str] = Field(default_factory=list, max_length=8)
    memoryScope: str = Field(default="bot", max_length=16)


class BotUpdateReq(BotPayload):
    rowVersion: int = Field(ge=0)


class MemoryPayload(BaseModel):
    key: str = Field(min_length=2, max_length=64)
    value: str = Field(min_length=2, max_length=500)
    source: str = Field(min_length=2, max_length=200)
    expiresAt: str | None = Field(default=None, max_length=10)


class TeamPayload(BaseModel):
    code: str = Field(min_length=3, max_length=64)
    name: str = Field(min_length=2, max_length=80)
    description: str = Field(default="", max_length=300)
    botCodes: list[str] = Field(default_factory=list, max_length=4)


class TeamUpdateReq(TeamPayload):
    rowVersion: int = Field(ge=0)


class CollaborateReq(BaseModel):
    question: str = Field(min_length=2, max_length=2000)


class SecondOpinionReq(BaseModel):
    question: str = Field(min_length=2, max_length=2000)
    providerIds: list[str] | None = Field(default=None, max_length=2)


class SleepScanReq(BaseModel):
    botCodes: list[str] | None = Field(default=None, max_length=50)


class ToolLoopReq(BaseModel):
    question: str = Field(min_length=2, max_length=2000)


class ExperiencePayload(BaseModel):
    agentId: str = Field(min_length=2, max_length=32)
    title: str = Field(min_length=2, max_length=80)
    pattern: str = Field(min_length=4, max_length=200)
    summary: str = Field(min_length=20, max_length=1000)
    sourceRef: str = Field(min_length=2, max_length=120)
    deidentified: bool = False
    toolAllowlist: list[str] = Field(default_factory=list, max_length=8)
    evidenceRefs: list[str] = Field(default_factory=list, max_length=10)
    contraindications: list[str] = Field(default_factory=list, max_length=5)


class ProposalRejectReq(BaseModel):
    note: str = Field(min_length=1, max_length=500)


class SnapshotReq(BaseModel):
    date: str | None = Field(default=None, max_length=10)


class AdviceNoteReq(BaseModel):
    note: str | None = Field(default=None, max_length=500)


class ToolInvokeReq(BaseModel):
    """工具调用请求（评审 M-09）：拒绝未知顶层键，参数交由工具 Schema 再校验。"""

    model_config = {"extra": "forbid"}

    arguments: dict = Field(default_factory=dict)


def _base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


@router.get("/card")
async def agent_card(request: Request):
    """A2A Agent Card（公开）。关闭 A2A 能力广告后返回 404。"""
    if not agent_flags.get_flag("agent_a2a"):
        raise HTTPException(404, "A2A 能力广告已关闭")
    return catalog.agent_card(_base_url(request))


@router.get("/catalog")
async def agent_catalog(user: dict = Depends(require_user)):
    """智能体目录 + 工具清单 + 当前开关（按角色过滤）。"""
    role = user.get("role", "")
    return {
        "agents": catalog.catalog_payload(role),
        "tools": agent_tools.list_tools(role),
        "flags": agent_flags.enabled_map(),
    }


@router.post("/route")
async def route_preview(req: RouteReq, user: dict = Depends(require_user)):
    """路由预览（不执行智能体）：返回主责智能体、置信度、候选与最大跳数。"""
    result = router_agent.route(req.question, multi_agent=req.multiAgent)
    result["role"] = user.get("role", "")
    return result


class ConsultReq(BaseModel):
    question: str = Field(min_length=2, max_length=2000)
    agentId: str | None = Field(default=None, max_length=32)
    skillCodes: list[str] | None = Field(default=None, max_length=8)
    history: list[dict] | None = Field(default=None, max_length=6)


@router.post("/consult")
async def agent_consult(req: ConsultReq, user: dict = Depends(require_user)):
    """统一问答：规则路由 + 已发布技能卡生效 + 合规模型；只读、仅建议。"""
    started = time.perf_counter()
    try:
        result = await consult.run(req.question, user.get("role", ""),
                                   req.agentId, req.skillCodes, req.history)
    except consult.ConsultForbidden as e:
        raise HTTPException(403, str(e))
    except consult.ConsultInvalid as e:
        raise HTTPException(422, str(e))
    except consult.ConsultUnavailable as e:
        raise HTTPException(503, str(e))
    await _audit_agent(user, "agent_consult", {
        "agentId": result["agent"]["id"],
        "skills": [s["code"] for s in result["appliedSkills"]],
        "questionChars": len(req.question.strip()),
        "answerChars": len(result.get("answer") or ""),
        "turns": len(req.history or []),
        "hitl": result.get("needsHumanReview", True),
        "ms": int((time.perf_counter() - started) * 1000),
    })
    return result


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/consult/stream")
async def agent_consult_stream(req: ConsultReq, user: dict = Depends(require_user)):
    """统一问答（SSE 流式）：meta → delta* → done；错误以 error 事件返回。"""
    async def gen():
        started = time.perf_counter()
        try:
            async for ev in consult.stream(req.question, user.get("role", ""),
                                           req.agentId, req.skillCodes, req.history):
                if ev.get("type") == "done":
                    await _audit_agent(user, "agent_consult_stream", {
                        "agentId": ev["agent"]["id"],
                        "skills": [s["code"] for s in ev.get("appliedSkills") or []],
                        "questionChars": len(req.question.strip()),
                        "answerChars": len(ev.get("answer") or ""),
                        "turns": len(req.history or []),
                        "hitl": ev.get("needsHumanReview", True),
                        "ms": int((time.perf_counter() - started) * 1000),
                    })
                yield _sse(ev["type"], ev)
        except consult.ConsultForbidden as e:
            # 评审 M-53：失败路径同样留痕（只记原因与长度，不记问题原文）
            await _audit_agent(user, "agent_consult_stream", {
                "status": "error", "reason": "forbidden",
                "questionChars": len(req.question.strip()),
                "ms": int((time.perf_counter() - started) * 1000)})
            yield _sse("error", {"status": 403, "detail": str(e)})
        except consult.ConsultInvalid as e:
            await _audit_agent(user, "agent_consult_stream", {
                "status": "error", "reason": "invalid",
                "questionChars": len(req.question.strip()),
                "ms": int((time.perf_counter() - started) * 1000)})
            yield _sse("error", {"status": 422, "detail": str(e)})
        except consult.ConsultUnavailable as e:
            await _audit_agent(user, "agent_consult_stream", {
                "status": "error", "reason": "unavailable",
                "questionChars": len(req.question.strip()),
                "ms": int((time.perf_counter() - started) * 1000)})
            yield _sse("error", {"status": 503, "detail": str(e)})

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache",
                                      "X-Accel-Buffering": "no"})


@router.post("/tools/{name}/invoke")
async def invoke_tool(name: str, req: ToolInvokeReq,
                      user: dict = Depends(require_user)):
    """调用注册表中的工具（参数按工具的 JSON Schema 校验，入参统一脱敏）。"""
    started = time.perf_counter()
    role = user.get("role", "")
    try:
        result = await agent_tools.execute_tool(name, req.arguments, role)
    except agent_tools.ToolNotFound as e:
        raise HTTPException(404, str(e))
    except agent_tools.ToolForbidden as e:
        raise HTTPException(403, str(e))
    except agent_tools.ToolDisabled as e:
        raise HTTPException(409, str(e))
    except agent_tools.ToolInvalidArguments as e:
        raise HTTPException(422, str(e))
    except agent_tools.ToolUnavailable as e:
        raise HTTPException(503, str(e))
    from backend.api.v1.medical import shared as _shared
    await asyncio.to_thread(
        _shared.get_audit_logger().write,
        event_type="agent", action="tool_invoke",
        actor=user.get("username", "anonymous"),
        payload={"tool": name, "ok": True,
                 "ms": int((time.perf_counter() - started) * 1000)},
    )
    return result


@router.get("/flags")
async def list_flags(user: dict = Depends(require_role("admin"))):
    return {"flags": agent_flags.all_flags()}


@router.put("/flags")
async def set_flag(req: FlagReq, user: dict = Depends(require_role("admin"))):
    try:
        value = agent_flags.set_flag(req.key, req.value)
    except ValueError as e:
        raise HTTPException(400, str(e))
    from backend.api.v1.medical import shared as _shared
    await asyncio.to_thread(
        _shared.get_audit_logger().write,
        event_type="agent", action="flag_update",
        actor=user.get("username", "admin"),
        payload={"key": req.key, "value": value},
    )
    return {"ok": True, "key": req.key, "value": value}


# ---------------------------------------------------------------- 临床技能卡（G87）

def _skill_http(e: skill_cards.SkillError) -> HTTPException:
    if isinstance(e, skill_cards.SkillNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, skill_cards.SkillForbidden):
        return HTTPException(403, str(e))
    if isinstance(e, skill_cards.SkillConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


async def _audit_agent(user: dict, action: str, payload: dict) -> None:
    from backend.api.v1.medical import shared as _shared
    await asyncio.to_thread(
        _shared.get_audit_logger().write,
        event_type="agent", action=action,
        actor=user.get("username", "anonymous"), payload=payload,
    )


@router.get("/skills")
async def list_skills(status: str | None = None, agentId: str | None = None,
                      user: dict = Depends(require_user)):
    """技能卡列表：普通角色仅见 PUBLISHED；admin/qc 可见全部并按状态/智能体过滤。"""
    return {"skills": skill_cards.list_skills(user.get("role", ""), status, agentId)}


@router.get("/skills/{code}")
async def get_skill(code: str, user: dict = Depends(require_user)):
    try:
        return skill_cards.get_skill(code, user.get("role", ""))
    except skill_cards.SkillError as e:
        raise _skill_http(e)


@router.post("/skills")
async def create_skill(req: SkillPayload, user: dict = Depends(require_role("admin"))):
    """新建技能卡（DRAFT）；须由另一名 admin/qc 审核后才生效。"""
    try:
        item = skill_cards.create_skill(req.model_dump(), user.get("username", "admin"))
    except skill_cards.SkillError as e:
        raise _skill_http(e)
    await _audit_agent(user, "skill_create",
                       {"code": item["code"], "agentId": item["agentId"]})
    return item


@router.put("/skills/{code}")
async def update_skill(code: str, req: SkillUpdateReq,
                       user: dict = Depends(require_role("admin"))):
    payload = req.model_dump()
    row_version = payload.pop("rowVersion")
    try:
        item = skill_cards.update_skill(code, payload, row_version,
                                        user.get("username", "admin"))
    except skill_cards.SkillError as e:
        raise _skill_http(e)
    await _audit_agent(user, "skill_update", {"code": code, "rowVersion": item["rowVersion"]})
    return item


@router.post("/skills/{code}/submit")
async def submit_skill(code: str, req: SkillVersionReq,
                       user: dict = Depends(require_role("admin"))):
    try:
        item = skill_cards.submit_skill(code, req.rowVersion, user.get("username", "admin"))
    except skill_cards.SkillError as e:
        raise _skill_http(e)
    await _audit_agent(user, "skill_submit", {"code": code})
    return item


@router.post("/skills/{code}/review")
async def review_skill(code: str, req: SkillReviewReq,
                       user: dict = Depends(require_role("admin", "qc"))):
    """审核：通过 → PUBLISHED（version+1）；驳回必须写原因。四眼由服务层强制。"""
    try:
        item = skill_cards.review_skill(code, req.approve, req.note, req.rowVersion,
                                        user.get("username", "qc"))
    except skill_cards.SkillError as e:
        raise _skill_http(e)
    await _audit_agent(user, "skill_review",
                       {"code": code, "approve": req.approve, "version": item["version"]})
    return item


@router.post("/skills/{code}/disable")
async def disable_skill(code: str, req: SkillVersionReq,
                        user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = skill_cards.disable_skill(code, req.rowVersion, user.get("username", "qc"))
    except skill_cards.SkillError as e:
        raise _skill_http(e)
    await _audit_agent(user, "skill_disable", {"code": code})
    return item


# ---------------------------------------------------------------- Bot / 团队 / 记忆（G88）

def _bot_http(e: bot_registry.BotError) -> HTTPException:
    if isinstance(e, bot_registry.BotNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, bot_registry.BotForbidden):
        return HTTPException(403, str(e))
    if isinstance(e, bot_registry.BotConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


def _team_http(e: bot_teams.TeamError) -> HTTPException:
    if isinstance(e, bot_teams.TeamNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, bot_teams.TeamConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


def _mem_http(e: bot_memory.MemoryError_) -> HTTPException:
    if isinstance(e, bot_memory.MemoryNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, bot_memory.MemoryForbidden):
        return HTTPException(403, str(e))
    if isinstance(e, bot_memory.MemoryConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


@router.get("/bots")
async def list_bots(status: str | None = None, user: dict = Depends(require_user)):
    return {"bots": bot_registry.list_bots(user.get("role", ""), status)}


@router.get("/bots/{code}")
async def get_bot(code: str, user: dict = Depends(require_user)):
    try:
        return bot_registry.get_bot(code, user.get("role", ""))
    except bot_registry.BotError as e:
        raise _bot_http(e)


@router.post("/bots")
async def create_bot(req: BotPayload, user: dict = Depends(require_role("admin"))):
    """新建 Bot（DRAFT）：身份 + 模型绑定 + 技能/工具白名单；须另一人审核后生效。"""
    try:
        item = bot_registry.create_bot(req.model_dump(), user.get("username", "admin"))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "bot_create",
                       {"code": item["code"], "agentId": item["agentId"],
                        "modelProvider": item["modelProvider"]})
    return item


@router.put("/bots/{code}")
async def update_bot(code: str, req: BotUpdateReq,
                     user: dict = Depends(require_role("admin"))):
    payload = req.model_dump()
    row_version = payload.pop("rowVersion")
    try:
        item = bot_registry.update_bot(code, payload, row_version,
                                       user.get("username", "admin"))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "bot_update", {"code": code, "rowVersion": item["rowVersion"]})
    return item


@router.post("/bots/{code}/submit")
async def submit_bot(code: str, req: SkillVersionReq,
                     user: dict = Depends(require_role("admin"))):
    try:
        item = bot_registry.submit_bot(code, req.rowVersion, user.get("username", "admin"))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "bot_submit", {"code": code})
    return item


@router.post("/bots/{code}/review")
async def review_bot(code: str, req: SkillReviewReq,
                     user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = bot_registry.review_bot(code, req.approve, req.note, req.rowVersion,
                                       user.get("username", "qc"))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "bot_review",
                       {"code": code, "approve": req.approve, "version": item["version"]})
    return item


@router.post("/bots/{code}/disable")
async def disable_bot(code: str, req: SkillVersionReq,
                      user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = bot_registry.disable_bot(code, req.rowVersion, user.get("username", "qc"))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "bot_disable", {"code": code})
    return item


@router.get("/bots/{code}/memories")
async def list_bot_memories(code: str, user: dict = Depends(require_user)):
    """Bot 长期记忆：普通角色只看已确认；admin/qc 可见候选。"""
    try:
        bot_registry.get_bot(code, user.get("role", ""))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    return {"memories": bot_memory.list_memories(code, user.get("role", ""))}


@router.post("/bots/{code}/memories")
async def create_bot_memory(code: str, req: MemoryPayload,
                            user: dict = Depends(require_role("admin", "qc"))):
    """录入 Bot 长期记忆候选（仅质控/管理员）。

    记忆会注入 Bot 的 system prompt（评审 M-01 整改）：写入面必须收敛到可审计的角色，
    且确认必须由非录入人完成，避免"任意登录用户自写自确认"的持久化提示注入通道。
    """
    payload = req.model_dump()
    payload["botCode"] = code
    try:
        item = bot_memory.create_memory(payload, user.get("username", "anonymous"))
    except bot_memory.MemoryError_ as e:
        raise _mem_http(e)
    await _audit_agent(user, "bot_memory_create", {"bot": code, "key": item["key"]})
    return item


@router.post("/bot-memories/{mem_id}/confirm")
async def confirm_bot_memory(mem_id: str, user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = bot_memory.confirm_memory(mem_id, user.get("username", "anonymous"),
                                         user.get("role", ""))
    except bot_memory.MemoryError_ as e:
        raise _mem_http(e)
    await _audit_agent(user, "bot_memory_confirm",
                       {"bot": item["botCode"], "key": item["key"]})
    return item


@router.delete("/bot-memories/{mem_id}")
async def delete_bot_memory(mem_id: str, user: dict = Depends(require_user)):
    try:
        item = bot_memory.delete_memory(mem_id, user.get("username", "anonymous"),
                                        user.get("role", ""))
    except bot_memory.MemoryError_ as e:
        raise _mem_http(e)
    await _audit_agent(user, "bot_memory_delete",
                       {"bot": item["botCode"], "key": item["key"]})
    return item


@router.get("/bot-teams")
async def list_bot_teams(status: str | None = None, user: dict = Depends(require_user)):
    return {"teams": bot_teams.list_teams(user.get("role", ""), status)}


@router.get("/bot-teams/{code}")
async def get_bot_team(code: str, user: dict = Depends(require_user)):
    try:
        return bot_teams.get_team(code, user.get("role", ""))
    except bot_teams.TeamError as e:
        raise _team_http(e)


@router.post("/bot-teams")
async def create_bot_team(req: TeamPayload, user: dict = Depends(require_role("admin"))):
    try:
        item = bot_teams.create_team(req.model_dump(), user.get("username", "admin"))
    except bot_teams.TeamError as e:
        raise _team_http(e)
    await _audit_agent(user, "bot_team_create",
                       {"code": item["code"], "bots": item["botCodes"]})
    return item


@router.put("/bot-teams/{code}")
async def update_bot_team(code: str, req: TeamUpdateReq,
                          user: dict = Depends(require_role("admin"))):
    payload = req.model_dump()
    row_version = payload.pop("rowVersion")
    try:
        item = bot_teams.update_team(code, payload, row_version,
                                     user.get("username", "admin"))
    except bot_teams.TeamError as e:
        raise _team_http(e)
    await _audit_agent(user, "bot_team_update", {"code": code})
    return item


@router.post("/bot-teams/{code}/disable")
async def disable_bot_team(code: str, req: SkillVersionReq,
                           user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = bot_teams.disable_team(code, req.rowVersion, user.get("username", "admin"))
    except bot_teams.TeamError as e:
        raise _team_http(e)
    await _audit_agent(user, "bot_team_disable", {"code": code})
    return item


@router.post("/bot-teams/{code}/collaborate")
async def collaborate(code: str, req: CollaborateReq, user: dict = Depends(require_user)):
    """团队协作：各 Bot 并行作答、失败隔离；结果并列展示，需人工复核。

    评审 M-03 整改：必须校验调用者角色在**每个成员 Bot 所属智能体**的 roles 白名单内，
    否则药师可通过团队间接驱动影像/会诊等越权能力域（与 tool-loop 端点同一纪律）。
    """
    role = user.get("role", "")
    try:
        team = bot_teams.get_team(code, role)
        for bot in bot_teams.team_bots(team):
            spec = catalog.get_agent(bot.get("agentId") or "")
            if spec is None or role not in spec.roles:
                raise HTTPException(
                    403, f"当前角色无权使用团队中的 Bot「{bot.get('name')}」")
        result = await bot_runtime.collaborate(team, req.question)
    except bot_runtime.BotUnavailable as e:
        raise HTTPException(503, str(e))
    except bot_teams.TeamError as e:
        raise _team_http(e)
    await _audit_agent(user, "bot_collaborate",
                       {"team": code, "opinions": len(result["opinions"]),
                        "failed": result["failed"]})
    return result


# ---------------------------------------------------------------- 模型合规 / 白名单联网 / 第二意见（G89）

@router.get("/model-policy")
async def model_policy_snapshot(user: dict = Depends(require_user)):
    """模型合规快照：只展示 provider 分类与主机，**不含 api_key**。"""
    return model_policy.policy_snapshot()


@router.get("/web-policy")
async def web_policy(user: dict = Depends(require_user)):
    """联网来源白名单 + 当前开关（开关仍由 admin 控制，默认关闭）。"""
    return {"enabled": agent_flags.get_flag("agent_web_search"), **web_sources.describe()}


@router.post("/second-opinion")
async def run_second_opinion(req: SecondOpinionReq, user: dict = Depends(require_user)):
    """双模型第二意见：仅国产/本地模型；意见并列 + 启发式差异信号。"""
    try:
        result = await second_opinion.run(req.question, req.providerIds)
    except model_policy.ModelPolicyViolation as e:
        raise HTTPException(422, str(e))
    except second_opinion.SecondOpinionUnavailable as e:
        raise HTTPException(503, str(e))
    await _audit_agent(user, "second_opinion", {
        "providers": [o.get("id") for o in result["opinions"]],
        "failed": result["failed"],
        "divergence": (result.get("divergence") or {}).get("level"),
    })
    return result


# ---------------------------------------------------------------- 技能库：经验 → 候选（G92）

def _lib_http(e: skill_library.LibraryError) -> HTTPException:
    if isinstance(e, skill_library.LibraryNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, skill_library.LibraryForbidden):
        return HTTPException(403, str(e))
    if isinstance(e, skill_library.LibraryConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


@router.post("/skill-experiences")
async def create_skill_experience(req: ExperiencePayload,
                                  user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = skill_library.create_experience(req.model_dump(),
                                               user.get("username", "qc"))
    except skill_library.LibraryError as e:
        raise _lib_http(e)
    await _audit_agent(user, "skill_experience_create",
                       {"agentId": item["agentId"], "pattern": item["pattern"]})
    return item


@router.get("/skill-experiences")
async def list_skill_experiences(agentId: str | None = None,
                                 user: dict = Depends(require_role("admin", "qc"))):
    try:
        return {"experiences": skill_library.list_experiences(user.get("role", ""), agentId)}
    except skill_library.LibraryError as e:
        raise _lib_http(e)


@router.post("/skill-proposals/distill")
async def distill_skill_proposals(user: dict = Depends(require_role("admin", "qc"))):
    """把同能力域+同模式的 ≥2 条经验提炼为候选技能（不发布、不调用模型）。"""
    proposals = skill_library.distill(user.get("username", "qc"))
    await _audit_agent(user, "skill_distill", {"proposals": len(proposals)})
    return {"proposals": proposals}


@router.get("/skill-proposals")
async def list_skill_proposals(status: str | None = None,
                               user: dict = Depends(require_role("admin", "qc"))):
    try:
        return {"proposals": skill_library.list_proposals(user.get("role", ""), status)}
    except skill_library.LibraryError as e:
        raise _lib_http(e)


@router.post("/skill-proposals/{proposal_id}/adopt")
async def adopt_skill_proposal(proposal_id: str, user: dict = Depends(require_role("admin"))):
    """回放通过才生成 DRAFT 技能卡；仍须 G87 四眼审核后发布。"""
    try:
        result = skill_library.adopt(proposal_id, user.get("username", "admin"))
    except skill_library.LibraryError as e:
        raise _lib_http(e)
    await _audit_agent(user, "skill_adopt", {
        "proposalId": proposal_id,
        "status": result["proposal"]["status"],
        "skillCode": result["proposal"].get("skillCode"),
        "replay": (result["proposal"].get("replay") or {}).get("passed"),
    })
    return result


@router.post("/skill-proposals/{proposal_id}/reject")
async def reject_skill_proposal(proposal_id: str, req: ProposalRejectReq,
                                user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = skill_library.reject(proposal_id, req.note, user.get("username", "qc"))
    except skill_library.LibraryError as e:
        raise _lib_http(e)
    # 评审 M-13：审计只记长度与结构化结论，自由文本原文留在业务记录
    await _audit_agent(user, "skill_proposal_reject",
                       {"proposalId": proposal_id, "noteChars": len(req.note)})
    return item


# ---------------------------------------------------------------- 技能库运营（G93）

@router.get("/skill-library/metrics")
async def skill_library_metrics(user: dict = Depends(require_role("admin", "qc"))):
    """只读运营指标：经验覆盖、候选转化、回放通过率、技能审核通过率、Bot 引用。"""
    return skill_ops.metrics()


@router.get("/skill-library/usage")
async def skill_library_usage(user: dict = Depends(require_role("admin", "qc"))):
    """技能使用率看板（只读）：问答成功率/耗时、已发布技能使用率与最近使用时间。"""
    return skill_usage.report()


@router.get("/skill-library/metrics/prometheus", response_class=PlainTextResponse)
async def skill_library_prometheus(user: dict = Depends(require_role("admin", "qc"))):
    """Prometheus 文本导出（聚合指标，不含业务标识/PHI）；供监控采集。"""
    # 评审 M-45：聚合会触发多次域加载，放线程池避免阻塞事件循环
    text = await asyncio.to_thread(
        lambda: skill_trends.prometheus_text() + consult_metrics.prometheus_text())
    return PlainTextResponse(text,
                             media_type="text/plain; version=0.0.4; charset=utf-8")


@router.post("/skill-library/snapshot")
async def skill_library_snapshot(req: SnapshotReq,
                                 user: dict = Depends(require_role("admin", "qc"))):
    """记录某业务日的指标快照（同一天幂等覆盖）。"""
    day = None
    if req.date:
        try:
            day = date.fromisoformat(req.date)
        except ValueError:
            raise HTTPException(422, "date 需为 YYYY-MM-DD")
    item = await asyncio.to_thread(skill_trends.snapshot,
                                   user.get("username", "qc"), day)
    await _audit_agent(user, "skill_metrics_snapshot", {"date": item["date"]})
    return item


@router.get("/skill-library/trends")
async def skill_library_trends(days: int = 30,
                               user: dict = Depends(require_role("admin", "qc"))):
    """趋势：历史不足 2 天时显式 insufficient，不插值、不推测。"""
    return skill_trends.trends(days)


@router.get("/skill-library/alerts")
async def skill_library_alerts(user: dict = Depends(require_role("admin", "qc"))):
    """阈值告警：仅提示，不自动处置。"""
    return skill_trends.alerts()


# ---------------------------------------------------------------- 运营建议（G95）

def _advice_http(e: skill_advice.AdviceError) -> HTTPException:
    if isinstance(e, skill_advice.AdviceNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, skill_advice.AdviceConflict):
        return HTTPException(409, str(e))
    return HTTPException(422, str(e))


@router.post("/skill-advices/generate")
async def generate_skill_advices(user: dict = Depends(require_role("admin", "qc"))):
    """把告警翻译成待办建议；不修改任何技能/Bot/候选。"""
    try:
        items = skill_advice.generate(user.get("username", "qc"))
    except skill_advice.AdviceError as e:
        raise _advice_http(e)
    await _audit_agent(user, "skill_advice_generate", {"advices": len(items)})
    return {"advices": items}


@router.get("/skill-advices")
async def list_skill_advices(status: str | None = None,
                             user: dict = Depends(require_role("admin", "qc"))):
    try:
        return {"advices": skill_advice.list_advices(user.get("role", ""), status)}
    except skill_advice.AdviceError as e:
        raise _advice_http(e)


@router.post("/skill-advices/{advice_id}/dismiss")
async def dismiss_skill_advice(advice_id: str, req: AdviceNoteReq,
                               user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = skill_advice.dismiss(advice_id, req.note or "", user.get("username", "qc"))
    except skill_advice.AdviceError as e:
        raise _advice_http(e)
    # 评审 M-13：审计只记长度，自由文本原文留在业务记录
    await _audit_agent(user, "skill_advice_dismiss",
                       {"adviceId": advice_id,
                        "noteChars": len(item.get("note") or "")})
    return item


@router.post("/skill-advices/{advice_id}/complete")
async def complete_skill_advice(advice_id: str, req: AdviceNoteReq,
                                user: dict = Depends(require_role("admin", "qc"))):
    try:
        item = skill_advice.complete(advice_id, req.note, user.get("username", "qc"))
    except skill_advice.AdviceError as e:
        raise _advice_http(e)
    await _audit_agent(user, "skill_advice_complete",
                       {"adviceId": advice_id,
                        "noteChars": len(item.get("note") or "")})
    return item


@router.get("/skills/{code}/impact")
async def skill_impact(code: str, user: dict = Depends(require_role("admin", "qc"))):
    """技能卡下线影响面：引用它的 Bot、这些 Bot 所在团队、库内候选来源。"""
    try:
        return skill_ops.skill_impact(code)
    except skill_cards.SkillError as e:
        raise _skill_http(e)


@router.get("/bots/{code}/impact")
async def bot_impact(code: str, user: dict = Depends(require_role("admin", "qc"))):
    """Bot 下线影响面：所在团队、长期记忆数量、绑定技能卡状态。"""
    try:
        return skill_ops.bot_impact(code)
    except bot_registry.BotError as e:
        raise _bot_http(e)


# ---------------------------------------------------------------- 有界工具循环（G91）

@router.post("/bots/{code}/tool-loop")
async def bot_tool_loop(code: str, req: ToolLoopReq, user: dict = Depends(require_user)):
    """Bot 在硬边界内多步调用工具：≤3 步、≤3 次调用、≤60s，每步审计。"""
    role = user.get("role", "")
    try:
        bot = bot_registry.get_bot(code, role)
        agent = catalog.get_agent(bot.get("agentId") or "")
        if agent is None or role not in agent.roles:
            raise HTTPException(403, "当前角色无权使用该 Bot")
        async def _audit_step(ev: dict) -> None:
            await _audit_agent(user, "tool_loop_step",
                               {"bot": code, **ev})
        result = await tool_loop.run(req.question, bot, role, audit=_audit_step)
    except tool_loop.ToolLoopUnavailable as e:
        raise HTTPException(503, str(e))
    except bot_registry.BotError as e:
        raise _bot_http(e)
    await _audit_agent(user, "tool_loop", {
        "bot": code, "toolCalls": result["toolCalls"],
        "truncated": result["truncated"], "steps": len(result["steps"]),
    })
    return result


# ---------------------------------------------------------------- 睡眠记忆整理（G90）

@router.post("/sleep-memory/scan")
async def sleep_memory_scan(req: SleepScanReq, user: dict = Depends(require_role("admin", "qc"))):
    """只读体检：生成候选建议（过期/重复/近似/跨 Bot 冲突），不自动修改记忆。"""
    report = await asyncio.to_thread(
        sleep_memory.scan, req.botCodes, None, user.get("username", "admin"))
    await _audit_agent(user, "sleep_memory_scan", {
        "findings": report["summary"]["findings"],
        "bySeverity": report["summary"]["bySeverity"],
    })
    return report


@router.get("/sleep-memory/last")
async def sleep_memory_last(user: dict = Depends(require_role("admin", "qc"))):
    return {"report": sleep_memory.last_report()}


# ---------------------------------------------------------------- A2A（最小互操作）

def _jsonrpc_error(msg_id: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def _message_text(params: dict) -> str:
    message = (params or {}).get("message") or {}
    parts = message.get("parts") or []
    texts = [p.get("text", "") for p in parts
             if isinstance(p, dict) and p.get("kind", "text") == "text"]
    return "\n".join(t for t in texts if t).strip()


@router.post("/a2a")
async def a2a(body: dict, user: dict = Depends(require_user)):
    """A2A JSON-RPC 最小实现：``message/send`` 返回路由任务结果。

    仅完成"发现 + 路由"：不生成临床结论、不写复核队列；调用方拿到 agent 后
    仍须走对应业务接口并自行安排执业人员复核。
    """
    msg_id = body.get("id")
    # 评审 M-12：A2A 与能力卡同受一个开关约束，关闭时连路由入口一并下线
    if not agent_flags.get_flag("agent_a2a"):
        return _jsonrpc_error(msg_id, -32601, "A2A 已关闭")
    if (body.get("jsonrpc") or "") != "2.0":
        return _jsonrpc_error(msg_id, -32600, "Invalid Request")
    method = body.get("method")
    if method != "message/send":
        return _jsonrpc_error(msg_id, -32601, f"Method not found: {method}")
    text = _message_text(body.get("params") or {})
    if not text:
        return _jsonrpc_error(msg_id, -32602, "message.parts 缺少 text")
    if len(text) > 2000:
        # 评审 M-12：显式拒绝超长输入，避免把路由端点当作无界计算入口
        return _jsonrpc_error(msg_id, -32602, "message 不得超过 2000 字")
    routed = router_agent.route(text)
    task_id = uuid.uuid4().hex
    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "result": {
            "kind": "task",
            "id": task_id,
            "contextId": uuid.uuid4().hex,
            "status": {"state": "completed"},
            "artifacts": [{
                "artifactId": uuid.uuid4().hex,
                "name": "routing-decision",
                "parts": [{
                    "kind": "text",
                    "text": json.dumps(routed, ensure_ascii=False),
                    "mimeType": "application/json",
                }],
            }],
            "metadata": {"handler": "router", "hitlRequired": True},
        },
    }
