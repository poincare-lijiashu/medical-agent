"""G91 有界工具循环（bounded tool-calling loop）。

医疗场景不做"无限自治"。本模块只允许 Bot 在一个**硬边界**内多步调用工具：

- 最多 ``MAX_STEPS`` 轮模型推理、``MAX_TOOL_CALLS`` 次工具调用、总时长 ``TOTAL_TIMEOUT``；
- 工具必须同时满足：Bot 白名单 ∩ 工具注册表 ∩ 运行时开关（如联网搜索默认关闭）；
- 越权工具**不执行**，把 ``tool_not_allowed`` 回给模型，允许其自我纠正；
- 工具失败只把结构化错误回给模型，不冒泡 500；每一步都写审计（工具名/状态/耗时，
  不写原始问题或参数，避免 PHI 进审计）；
- 达到边界仍未收敛 → ``truncated=true``，不伪造最终答案；
- 结果恒为 ``needsHumanReview=true``。

模型绑定仍走 ``bot_runtime.resolve_provider``，因此境外/未知 provider 同样被拒绝。
"""
from __future__ import annotations

import asyncio
import json
import time

from langchain_core.messages import ToolMessage

from backend.agents import bot_runtime, catalog, model_policy
from backend.agents.limits import MAX_QUESTION
from backend.core import agent_tools
from backend.core.llm_factory import LLMFactory

MAX_STEPS = 3
MAX_TOOL_CALLS = 3
STEP_TIMEOUT_S = 30.0
TOTAL_TIMEOUT_S = 60.0


class ToolLoopUnavailable(RuntimeError):
    """Bot 无法执行工具循环（模型/权限/参数问题）。"""


class ToolLoopForbidden(ToolLoopUnavailable):
    """角色无权使用该 Bot（评审 01-S15：权限不足 ≠ 服务不可用，应映射 403）。

    继承 ``ToolLoopUnavailable`` 以保持既有调用方的捕获语义不变。
    """


class ToolLoopInvalid(ToolLoopUnavailable):
    """客户端入参不合法（问题长度）——应映射 422。"""


def _tool_def(name: str) -> dict:
    spec = agent_tools.TOOLS[name]
    return {"type": "function", "function": {
        "name": spec.name,
        "description": spec.description,
        "parameters": spec.input_schema,
    }}


def _allowed_tools(bot: dict) -> list[str]:
    """Bot 白名单 ∩ 注册表 ∩ 开关；不满足的不暴露给模型。"""
    out = []
    for name in bot.get("toolAllowlist") or []:
        spec = agent_tools.TOOLS.get(name)
        if spec is not None and agent_tools.tool_enabled(spec):
            out.append(name)
    return out


def _content_text(resp) -> str:
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip()


async def run(question: str, bot: dict, role: str, llm_factory=None,
              audit=None) -> dict:
    """执行一次有界工具循环；``llm_factory`` 可注入用于测试/离线评测。"""
    q = (question or "").strip()
    if not (2 <= len(q) <= MAX_QUESTION):
        raise ToolLoopInvalid(f"问题需 2-{MAX_QUESTION} 字")

    agent = catalog.get_agent(bot.get("agentId") or "")
    if agent is None:
        raise ToolLoopUnavailable(f"未找到 Bot 的能力域：{bot.get('agentId')}")
    if role not in agent.roles:
        raise ToolLoopForbidden(f"当前角色无权使用该 Bot：{role}")

    provider = bot_runtime.resolve_provider(bot)
    allowed = _allowed_tools(bot)
    tools = [_tool_def(name) for name in allowed]

    factory = llm_factory or LLMFactory.get_llm_for_provider
    llm = factory(provider)
    if tools:
        llm = llm.bind_tools(tools)

    messages = bot_runtime.build_messages(bot, q)
    trace: list[dict] = []
    tool_calls = 0
    truncated = False
    started = time.monotonic()

    async def _audit_step(event: dict) -> None:
        if audit is not None:
            await audit(event)

    for step in range(1, MAX_STEPS + 1):
        if time.monotonic() - started > TOTAL_TIMEOUT_S:
            truncated = True
            break
        try:
            resp = await asyncio.wait_for(llm.ainvoke(messages), timeout=STEP_TIMEOUT_S)
        except asyncio.TimeoutError:
            trace.append({"step": step, "type": "timeout", "status": "TIMEOUT"})
            await _audit_step({"step": step, "type": "timeout", "status": "TIMEOUT"})
            truncated = True
            break
        except Exception:  # noqa: BLE001 —— 上游异常不冒泡，转为可复核结果
            # 评审 M-10：对外只给稳定错误码，异常类型名不进结果/审计
            trace.append({"step": step, "type": "error", "status": "ERROR",
                          "error": "MODEL_FAILED"})
            await _audit_step({"step": step, "type": "error", "status": "ERROR"})
            truncated = True
            break

        messages.append(resp)
        calls = list(getattr(resp, "tool_calls", None) or [])
        if not calls:
            trace.append({"step": step, "type": "final", "status": "OK"})
            await _audit_step({"step": step, "type": "final", "status": "OK"})
            return {
                "botCode": bot["code"],
                "provider": model_policy.provider_view(provider),
                "question": q,
                "answer": _content_text(resp)[:4000],
                "steps": trace,
                "toolCalls": tool_calls,
                "truncated": False,
                "elapsedMs": int((time.monotonic() - started) * 1000),
                "needsHumanReview": True,
                "note": "工具循环结果仅供复核；不构成诊断或处方。",
            }

        for call in calls:
            if tool_calls >= MAX_TOOL_CALLS:
                truncated = True
                break
            name = str((call or {}).get("name") or "")
            args = (call or {}).get("args") or {}
            call_id = str((call or {}).get("id") or f"call-{step}-{tool_calls + 1}")
            tool_calls += 1
            t0 = time.monotonic()

            if name not in allowed:
                status = "BLOCKED"
                payload = {"error": "tool_not_allowed",
                           "message": f"工具 {name} 不在该 Bot 的可用白名单内"}
            else:
                try:
                    result = await agent_tools.execute_tool(name, args, role)
                    status = "OK"
                    payload = result
                except agent_tools.ToolError as e:
                    status = "ERROR"
                    payload = {"error": e.code, "message": str(e)}
                except Exception:  # noqa: BLE001 —— 单工具异常不影响后续
                    status = "ERROR"
                    payload = {"error": "TOOL_FAILED", "message": "工具执行失败"}

            messages.append(ToolMessage(
                content=json.dumps(payload, ensure_ascii=False, default=str)[:6000],
                tool_call_id=call_id,
            ))
            event = {
                "step": step, "type": "tool", "tool": name, "status": status,
                "ms": int((time.monotonic() - t0) * 1000),
                "argKeys": sorted(args.keys()) if isinstance(args, dict) else [],
            }
            trace.append(event)
            await _audit_step(event)
        if truncated:
            break

    return {
        "botCode": bot["code"],
        "provider": model_policy.provider_view(provider),
        "question": q,
        "answer": "",
        "steps": trace,
        "toolCalls": tool_calls,
        "truncated": True,
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "needsHumanReview": True,
        "note": "已达到工具循环边界（步数/调用次数/时长），未生成最终答案；请人工复核。",
    }
