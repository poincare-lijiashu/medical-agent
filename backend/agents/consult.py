"""G99 统一问答入口：让已发布技能卡在 Agent 主链路真正生效。

G87 的技能卡此前只被 Bot 运行时装配（G88）使用；普通问答仍散落在各专科接口，
出现"技能卡发布了却用不上"的断点。本模块提供统一的 Agent 问答入口：

1. **路由**：未指定 ``agentId`` 时复用确定性规则路由（可解释、可复现、零 Token）；
   显式指定时跳过路由，但同样校验角色权限；
2. **技能生效**：默认应用该智能体全部 PUBLISHED 技能卡，也可显式指定；
   未发布/不属于该智能体的技能**显式拒绝**，不静默忽略；
3. **护栏**：输入上限取所有生效技能中最严格的一档；恒返回 ``needsHumanReview=True``；
4. **模型合规**：只允许 ``model_policy`` 认可的 local/domestic provider；
   未配置或分类不合规时显式失败（不返回伪造答案）；
5. **不做**：不自动调用工具（有界工具循环仍由 Bot 运行时承担）、不写记忆、不改技能、
   不把原始问题写进审计（只记长度与耗时）。

前置应答（闲聊/元问题）直接返回确定性文案，不消耗模型 Token。
"""
from __future__ import annotations

import asyncio
import time

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from backend.agents import catalog, model_policy, skills
from backend.agents import router as rule_router
from backend.core import llm_config
from backend.core.llm_factory import LLMFactory

MAX_QUESTION = 2000
PROMPT_BUDGET = 12000
AGENT_TIMEOUT_S = 45.0
STREAM_IDLE_TIMEOUT_S = 15.0
MAX_HISTORY_TURNS = 6
MAX_HISTORY_ITEM = 2000
MAX_HISTORY_CHARS = 6000

_BOUNDARY = (
    "【边界】你是临床辅助工具，不是医疗器械；输出仅供执业医师/药师参考，"
    "不得替代诊断、处方或治疗决策；不得编造文献/指南/证据；"
    "证据不足时必须明确说明不确定性，并提示人工复核。"
)


class ConsultError(RuntimeError):
    code = "CONSULT_ERROR"


class ConsultInvalid(ConsultError):
    code = "CONSULT_INVALID"


class ConsultForbidden(ConsultError):
    code = "CONSULT_FORBIDDEN"


class ConsultUnavailable(ConsultError):
    code = "CONSULT_UNAVAILABLE"


def select_skills(agent: catalog.AgentSpec, codes: list[str] | None) -> list[dict]:
    """解析本次生效技能：默认全部 PUBLISHED；显式指定时必须已发布且属于该智能体。"""
    published = sorted(skills.published_for_agent(agent.id), key=lambda s: s["code"])
    if codes is None:
        chosen = published
    else:
        if not codes:
            raise ConsultInvalid("skillCodes 不能为空数组；不传表示应用全部已发布技能")
        by_code = {s["code"]: s for s in published}
        missing = [c for c in codes if c not in by_code]
        if missing:
            raise ConsultInvalid(
                "技能卡未发布或不属于该智能体：" + "、".join(missing))
        chosen = [by_code[c] for c in codes]

    total = sum(len(s.get("promptTemplate") or "") for s in chosen)
    if total > PROMPT_BUDGET:
        raise ConsultInvalid(
            f"生效技能卡提示合计 {total} 字，超过 {PROMPT_BUDGET} 字上限；"
            "请收敛已发布技能或显式指定 skillCodes")
    return chosen


def _max_input(chosen: list[dict]) -> int:
    caps = [MAX_QUESTION]
    for card in chosen:
        try:
            caps.append(int((card.get("guardrails") or {}).get("maxInputChars")
                            or MAX_QUESTION))
        except (TypeError, ValueError):
            caps.append(MAX_QUESTION)
    return max(200, min(caps))


def resolve_provider() -> dict:
    """取默认 chat provider 并强制合规；不可用时显式失败。"""
    provider = llm_config.active_provider("chat")
    if not provider:
        raise ConsultUnavailable(
            "未配置可用的 chat provider：请在管理端配置本地/国产模型后再试")
    try:
        model_policy.assert_allowed(provider)
    except model_policy.ModelPolicyViolation as e:
        raise ConsultUnavailable(str(e)) from e
    return provider


def clean_history(history) -> list[dict]:
    """校验多轮上下文：仅 user/assistant，条数/单条/总量三重上限；不做服务端持久化。"""
    if not history:
        return []
    if not isinstance(history, list):
        raise ConsultInvalid("history 必须是数组")
    if len(history) > MAX_HISTORY_TURNS:
        raise ConsultInvalid(f"history 最多 {MAX_HISTORY_TURNS} 条")
    out: list[dict] = []
    total = 0
    for item in history:
        if not isinstance(item, dict):
            raise ConsultInvalid("history 每项必须是对象")
        role = str(item.get("role") or "").strip()
        if role not in ("user", "assistant"):
            raise ConsultInvalid("history.role 只能是 user 或 assistant")
        text = str(item.get("content") or "").strip()
        if not text:
            raise ConsultInvalid("history.content 不能为空")
        if len(text) > MAX_HISTORY_ITEM:
            raise ConsultInvalid(f"history 单条不得超过 {MAX_HISTORY_ITEM} 字")
        total += len(text)
        out.append({"role": role, "content": text})
    if total > MAX_HISTORY_CHARS:
        raise ConsultInvalid(f"history 合计不得超过 {MAX_HISTORY_CHARS} 字")
    return out


def build_messages(agent: catalog.AgentSpec, chosen: list[dict],
                   question: str, history: list[dict] | None = None) -> list:
    lines = [
        f"你是「{agent.name}」，职责：{agent.description}",
        f"能力域：{agent.id}。",
    ]
    if chosen:
        lines.append("【生效技能卡】（已通过非创建人审核，必须遵循）")
        for card in chosen:
            lines.append(
                f"- {card['name']}（{card['code']} v{int(card.get('version', 0))}）："
                f"{card['promptTemplate']}")
            if card.get("evidenceRefs"):
                lines.append("  证据引用：" + "；".join(card["evidenceRefs"]))
        lines.append(
            "【证据边界】只可引用上述证据或输入中明确提供的信息；"
            "不得补充未提供的指南条文、文献结论或数值。")
    else:
        lines.append("【提示】该能力域暂无生效技能卡，按职责与证据边界保守作答。")
    lines.append(_BOUNDARY)
    messages = [SystemMessage(content="\n".join(lines))]
    for turn in history or []:
        cls = HumanMessage if turn["role"] == "user" else AIMessage
        messages.append(cls(content=turn["content"]))
    messages.append(HumanMessage(content=question))
    return messages


async def _default_runner(provider: dict, messages: list) -> str:
    llm = LLMFactory.get_llm_for_provider(provider)
    resp = await llm.ainvoke(messages)
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip()


async def _default_streamer(provider: dict, messages: list):
    llm = LLMFactory.get_llm_for_provider(provider)
    async for chunk in llm.astream(messages):
        content = getattr(chunk, "content", chunk)
        if isinstance(content, list):
            content = "".join(str(c) for c in content)
        if content:
            yield str(content)


def _explicit_decision(agent: catalog.AgentSpec) -> dict:
    return {
        "agent": agent.id,
        "name": agent.name,
        "confidence": 1.0,
        "reason": "调用方显式指定能力域",
        "strategy": "explicit",
        "candidates": [],
        "maxHops": 0,
        "fallbackAgent": rule_router.FALLBACK_AGENT,
        "multiAgentEnabled": False,
        "hitl": agent.hitl,
        "prefilterReply": None,
    }


def _prefilter_payload(decision: dict) -> dict:
    return {
        "agent": {"id": "prefilter", "name": decision["name"]},
        "routing": decision,
        "appliedSkills": [],
        "declaredTools": [],
        "answer": decision.get("prefilterReply") or "",
        "elapsedMs": 0,
        "needsHumanReview": False,
        "note": "前置应答：未检索、未调用模型、未使用技能卡。",
    }


def _skill_views(chosen: list[dict]) -> list[dict]:
    return [
        {"code": c["code"], "name": c["name"], "version": int(c.get("version", 0)),
         "tools": list(c.get("toolAllowlist") or [])}
        for c in chosen
    ]


def _route_with_history(question: str, turns: list[dict]) -> dict:
    """当前问题未命中专科时，沿用上一轮用户问题的能力域（仅兜底，显式指定优先）。"""
    decision = rule_router.route(question)
    if not turns or decision["agent"] != rule_router.FALLBACK_AGENT:
        return decision
    last_user = next((t["content"] for t in reversed(turns) if t["role"] == "user"), None)
    if not last_user:
        return decision
    prev = rule_router.route(last_user)
    if prev["agent"] in ("prefilter", rule_router.FALLBACK_AGENT):
        return decision
    return {
        **prev,
        "reason": f"当前问题未命中专科，延续上一轮能力域（{prev['reason']}）",
        "strategy": "single",
        "candidates": [{"agent": prev["agent"], "score": prev["confidence"],
                        "reason": "延续上一轮能力域"}],
        "maxHops": 1,
    }


def _prepare(question: str, role: str, agent_id: str | None,
             skill_codes: list[str] | None, history) -> dict:
    """校验并装配一次问答（不含模型调用）；前置应答直接返回 payload。"""
    q = (question or "").strip()
    if not (2 <= len(q) <= MAX_QUESTION):
        raise ConsultInvalid(f"问题需 2-{MAX_QUESTION} 字")
    turns = clean_history(history)

    if agent_id:
        agent = catalog.get_agent(agent_id)
        if agent is None:
            raise ConsultInvalid(f"未找到智能体：{agent_id}")
        decision = _explicit_decision(agent)
    else:
        decision = _route_with_history(q, turns)
        if decision["agent"] == "prefilter":
            return {"prefilter": _prefilter_payload(decision)}
        agent = catalog.get_agent(decision["agent"])
        if agent is None:
            raise ConsultUnavailable(f"路由到的智能体未注册：{decision['agent']}")

    if role not in agent.roles:
        raise ConsultForbidden(f"当前角色 {role} 无权使用智能体 {agent.id}")

    chosen = select_skills(agent, skill_codes)
    cap = _max_input(chosen)
    if len(q) > cap:
        raise ConsultInvalid(f"问题长度 {len(q)} 超过生效技能护栏上限 {cap} 字")

    return {
        "agent": agent,
        "decision": decision,
        "chosen": chosen,
        "history": turns,
        "provider": resolve_provider(),
        "messages": build_messages(agent, chosen, q, turns),
    }


def _done_payload(ctx: dict, answer: str, started: float) -> dict:
    agent = ctx["agent"]
    chosen = ctx["chosen"]
    return {
        "agent": {"id": agent.id, "name": agent.name},
        "routing": ctx["decision"],
        "appliedSkills": _skill_views(chosen),
        "declaredTools": sorted({t for c in chosen for t in (c.get("toolAllowlist") or [])}),
        "answer": answer[:4000],
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "needsHumanReview": True,
        "note": "技能卡执行口径 + 模型输出，仅供复核；不构成诊断或处方；未自动调用工具。",
    }


async def run(question: str, role: str, agent_id: str | None = None,
              skill_codes: list[str] | None = None, history=None,
              runner=None) -> dict:
    """执行一次统一问答；``runner`` 可注入，用于测试/离线评测。"""
    ctx = _prepare(question, role, agent_id, skill_codes, history)
    if "prefilter" in ctx:
        return ctx["prefilter"]

    started = time.perf_counter()
    try:
        answer = await asyncio.wait_for(
            (runner or _default_runner)(ctx["provider"], ctx["messages"]),
            timeout=AGENT_TIMEOUT_S)
    except asyncio.TimeoutError:
        raise ConsultUnavailable(
            f"智能体 {ctx['agent'].id} 执行超时（>{int(AGENT_TIMEOUT_S)}s）") from None

    text = (answer or "").strip()
    if not text:
        raise ConsultUnavailable("模型未返回内容；请检查 provider 状态后重试")
    return _done_payload(ctx, text, started)


async def stream(question: str, role: str, agent_id: str | None = None,
                 skill_codes: list[str] | None = None, history=None,
                 streamer=None):
    """流式问答：先发 meta（路由/技能），再逐段 delta，最后 done。

    ``streamer(provider, messages)`` 为异步生成器，可注入用于测试。
    """
    ctx = _prepare(question, role, agent_id, skill_codes, history)
    if "prefilter" in ctx:
        payload = ctx["prefilter"]
        yield {"type": "meta", **{k: payload[k] for k in (
            "agent", "routing", "appliedSkills", "declaredTools", "needsHumanReview")},
            "historyTurns": 0}
        yield {"type": "done", **payload}
        return

    started = time.perf_counter()
    yield {
        "type": "meta",
        "agent": {"id": ctx["agent"].id, "name": ctx["agent"].name},
        "routing": ctx["decision"],
        "appliedSkills": _skill_views(ctx["chosen"]),
        "declaredTools": sorted({t for c in ctx["chosen"]
                                 for t in (c.get("toolAllowlist") or [])}),
        "needsHumanReview": True,
        "historyTurns": len(ctx["history"]),
    }

    parts: list[str] = []
    agen = (streamer or _default_streamer)(ctx["provider"], ctx["messages"])
    it = agen.__aiter__()
    while True:
        try:
            chunk = await asyncio.wait_for(it.__anext__(), timeout=STREAM_IDLE_TIMEOUT_S)
        except StopAsyncIteration:
            break
        except asyncio.TimeoutError:
            raise ConsultUnavailable(
                f"模型流式响应超时（>{int(STREAM_IDLE_TIMEOUT_S)}s 无输出）") from None
        except Exception as e:  # noqa: BLE001 —— 上游异常转为可读错误，不外抛堆栈
            raise ConsultUnavailable(f"模型流式输出失败：{type(e).__name__}") from None
        if time.perf_counter() - started > AGENT_TIMEOUT_S:
            raise ConsultUnavailable(f"智能体 {ctx['agent'].id} 执行超时")
        text = str(chunk or "")
        if not text:
            continue
        parts.append(text)
        yield {"type": "delta", "text": text}

    answer = "".join(parts).strip()
    if not answer:
        raise ConsultUnavailable("模型未返回内容；请检查 provider 状态后重试")
    yield {"type": "done", **_done_payload(ctx, answer, started)}
