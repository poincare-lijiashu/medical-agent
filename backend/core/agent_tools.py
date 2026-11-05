"""G86 工具注册表：把智能体可调用的院内能力做成**声明式、可审计、可开关**的清单。

与 ``scripts/mcp_server.py`` 的关系：
- MCP Server 是**对外**协议边界（供 Claude Desktop 等外部客户端调用）；
- 本模块是**对内**运行时边界：路由/子智能体在进程内按注册表取工具，字段更严格
  （角色、是否出网、PHI 级别、输入上限），两者复用同一批底层能力，不重复实现业务。

当前已接入（真实可执行，不是占位）：
- ``kb_search``：院内知识库混合检索（``medical_kb.search_hybrid``）；
- ``drug_check``：药物相互作用/禁忌规则核对（``medical_drug.check``）；
- ``web_search``：外部检索（``web_search.search``，默认关闭、出网前脱敏）。

设计约束：
- 工具名/参数/上限集中声明，执行前统一校验，避免各调用方各写一套；
- 未通过开关/角色校验时**显式拒绝**，不做"悄悄降级成空结果"；
- 工具只返回数据，不生成临床结论；生成与 HITL 判定仍由上层 Agent 负责。
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any, Callable

from backend.core import agent_flags, medical_drug, medical_kb
from backend.core import web_search as web_search_mod

# 输入上限：与 MCP 工具口径一致，防止单次调用把超大文本塞进检索/规则引擎
_MAX_QUERY = 2000
_MAX_TOP_K = 10


class ToolError(RuntimeError):
    """工具调用失败基类；``code`` 供 API 层映射 HTTP 状态。"""

    code = "TOOL_ERROR"


class ToolNotFound(ToolError):
    code = "TOOL_NOT_FOUND"


class ToolForbidden(ToolError):
    code = "TOOL_FORBIDDEN"


class ToolDisabled(ToolError):
    code = "TOOL_DISABLED"


class ToolInvalidArguments(ToolError):
    code = "TOOL_INVALID_ARGUMENTS"


class ToolUnavailable(ToolError):
    code = "TOOL_UNAVAILABLE"


@dataclass(frozen=True)
class ToolSpec:
    """一个可调用工具的静态定义（handler 必须是纯同步函数，异步包装在 execute 内）。"""

    name: str
    title: str
    description: str
    input_schema: dict
    roles: tuple[str, ...]
    phi: str  # none | patient
    handler: Callable[[dict], dict]
    feature_flag: str | None = None
    external_egress: bool = False


def _kb_search(args: dict) -> dict:
    try:
        rows = medical_kb.search_hybrid(args["query"], int(args.get("top_k") or 3))
    except Exception as e:  # noqa: BLE001 —— 向量库/模型故障统一转可读错误
        raise ToolUnavailable(f"知识库检索暂不可用：{type(e).__name__}") from e
    return {"total": len(rows), "chunks": rows}


def _drug_check(args: dict) -> dict:
    try:
        return medical_drug.check(args["question"])
    except Exception as e:  # noqa: BLE001 —— 规则库损坏同样降级，不冒泡 500
        raise ToolUnavailable(f"药物规则核对暂不可用：{type(e).__name__}") from e


def _web_search(args: dict) -> dict:
    try:
        return web_search_mod.search(args["query"], int(args.get("top_k") or 5))
    except web_search_mod.WebSearchUnavailable as e:
        raise ToolUnavailable(str(e)) from e


TOOLS: dict[str, ToolSpec] = {
    "kb_search": ToolSpec(
        name="kb_search",
        title="院内知识库检索",
        description="检索院内指南/共识片段（混合检索+精排），返回可溯源的原文片段。",
        input_schema={
            "type": "object",
            "properties": {
                "query": {"type": "string", "maxLength": _MAX_QUERY},
                "top_k": {"type": "integer", "minimum": 1, "maximum": _MAX_TOP_K},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        roles=("doctor", "pharmacist", "qc", "admin"),
        phi="none",
        handler=_kb_search,
    ),
    "drug_check": ToolSpec(
        name="drug_check",
        title="药物相互作用/禁忌核对",
        description="按院内规则库核对药物相互作用、禁忌与重复用药，返回命中条目与严重度。",
        input_schema={
            "type": "object",
            "properties": {"question": {"type": "string", "maxLength": _MAX_QUERY}},
            "required": ["question"],
            "additionalProperties": False,
        },
        roles=("doctor", "pharmacist", "qc", "admin"),
        phi="none",
        handler=_drug_check,
    ),
    "web_search": ToolSpec(
        name="web_search",
        title="联网检索（外部）",
        description="检索外部公开证据（指南/期刊/监管），查询串出网前脱敏；默认关闭。",
        input_schema={
            "type": "object",
            "properties": {
                "query": {"type": "string", "maxLength": _MAX_QUERY},
                "top_k": {"type": "integer", "minimum": 1, "maximum": _MAX_TOP_K},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        roles=("doctor", "pharmacist", "qc", "admin"),
        phi="none",
        handler=_web_search,
        feature_flag="agent_web_search",
        external_egress=True,
    ),
}


def tool_enabled(spec: ToolSpec) -> bool:
    """开关层面是否启用（角色另由 get_tool 校验）。"""
    return spec.feature_flag is None or agent_flags.get_flag(spec.feature_flag)


def list_tools(role: str) -> list[dict]:
    """列出全部工具及其当前可用性（含被开关/角色禁用的，便于管理端解释原因）。"""
    out = []
    for spec in TOOLS.values():
        role_ok = role in spec.roles
        flag_ok = tool_enabled(spec)
        reason = None
        if not role_ok:
            reason = "当前角色不可用"
        elif not flag_ok:
            reason = f"已被开关 {spec.feature_flag} 关闭"
        out.append({
            "name": spec.name,
            "title": spec.title,
            "description": spec.description,
            "inputSchema": spec.input_schema,
            "roles": list(spec.roles),
            "phi": spec.phi,
            "externalEgress": spec.external_egress,
            "featureFlag": spec.feature_flag,
            "enabled": role_ok and flag_ok,
            "reason": reason,
        })
    return out


def get_tool(name: str, role: str) -> ToolSpec:
    """按名称取工具并校验角色/开关；任一不满足显式拒绝。"""
    spec = TOOLS.get((name or "").strip())
    if spec is None:
        raise ToolNotFound(f"未注册的工具：{name}")
    if role not in spec.roles:
        raise ToolForbidden(f"当前角色无权调用工具：{name}")
    if not tool_enabled(spec):
        raise ToolDisabled(f"工具 {name} 已被运行时开关关闭")
    return spec


def _invalid(message: str) -> ToolInvalidArguments:
    return ToolInvalidArguments(message)


def validate_arguments(spec: ToolSpec, arguments: Any) -> dict:
    """按 ``input_schema`` 做最小但严格的校验（必填/未知键/类型/长度/范围）。"""
    if arguments is None:
        arguments = {}
    if not isinstance(arguments, dict):
        raise _invalid("工具参数必须是 JSON 对象")
    props: dict = spec.input_schema.get("properties") or {}
    required: list = spec.input_schema.get("required") or []
    unknown = [k for k in arguments if k not in props]
    if unknown:
        raise _invalid(f"不支持的参数：{','.join(sorted(unknown))}")
    cleaned: dict = {}
    for key in required:
        value = arguments.get(key)
        if value is None or (isinstance(value, str) and not value.strip()):
            raise _invalid(f"缺少必填参数：{key}")
    for key, value in arguments.items():
        rule = props.get(key) or {}
        if value is None:
            continue
        if rule.get("type") == "string":
            if not isinstance(value, str):
                raise _invalid(f"参数 {key} 必须是字符串")
            text = value.strip()
            if not text:
                raise _invalid(f"参数 {key} 不能为空")
            if len(text) > int(rule.get("maxLength") or _MAX_QUERY):
                raise _invalid(f"参数 {key} 超过长度上限")
            cleaned[key] = text
        elif rule.get("type") == "integer":
            if isinstance(value, bool) or not isinstance(value, int):
                raise _invalid(f"参数 {key} 必须是整数")
            low = rule.get("minimum")
            high = rule.get("maximum")
            if low is not None and value < int(low):
                raise _invalid(f"参数 {key} 不能小于 {low}")
            if high is not None and value > int(high):
                raise _invalid(f"参数 {key} 不能大于 {high}")
            cleaned[key] = value
        else:
            cleaned[key] = value
    return cleaned


async def execute_tool(name: str, arguments: Any, role: str) -> dict:
    """异步执行工具（同步 handler 放线程池，避免阻塞事件循环）。"""
    spec = get_tool(name, role)
    cleaned = validate_arguments(spec, arguments)
    result = await asyncio.to_thread(spec.handler, cleaned)
    return {"tool": spec.name, "result": result}
