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

from backend.agents import catalog, router as router_agent
from backend.api.deps import rate_limit, require_role, require_user
from backend.core import agent_flags, agent_tools

router = APIRouter(dependencies=[Depends(rate_limit)])


class RouteReq(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    multiAgent: bool | None = None


class FlagReq(BaseModel):
    key: str = Field(max_length=64)
    value: bool


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
