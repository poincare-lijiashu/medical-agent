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
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from backend.agents import bot_memory, bot_runtime, bot_teams, bots as bot_registry
from backend.agents import catalog, model_policy, second_opinion, skills as skill_cards
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


@router.post("/tools/{name}/invoke")
async def invoke_tool(name: str, payload: dict, request: Request,
                      user: dict = Depends(require_user)):
    """调用注册表中的工具（参数按工具的 JSON Schema 校验）。"""
    started = time.perf_counter()
    role = user.get("role", "")
    try:
        result = await agent_tools.execute_tool(name, payload.get("arguments"), role)
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
async def create_bot_memory(code: str, req: MemoryPayload, user: dict = Depends(require_user)):
    payload = req.model_dump()
    payload["botCode"] = code
    try:
        item = bot_memory.create_memory(payload, user.get("username", "anonymous"))
    except bot_memory.MemoryError_ as e:
        raise _mem_http(e)
    await _audit_agent(user, "bot_memory_create", {"bot": code, "key": item["key"]})
    return item


@router.post("/bot-memories/{mem_id}/confirm")
async def confirm_bot_memory(mem_id: str, user: dict = Depends(require_user)):
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
    """团队协作：各 Bot 并行作答、失败隔离；结果并列展示，需人工复核。"""
    try:
        team = bot_teams.get_team(code, user.get("role", ""))
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
    if (body.get("jsonrpc") or "") != "2.0":
        return _jsonrpc_error(msg_id, -32600, "Invalid Request")
    method = body.get("method")
    if method != "message/send":
        return _jsonrpc_error(msg_id, -32601, f"Method not found: {method}")
    text = _message_text(body.get("params") or {})
    if not text:
        return _jsonrpc_error(msg_id, -32602, "message.parts 缺少 text")
    routed = router_agent.route(text[:2000])
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
