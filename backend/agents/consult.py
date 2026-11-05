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

from langchain_core.messages import HumanMessage, SystemMessage

from backend.agents import catalog, model_policy, skills
from backend.agents import router as rule_router
from backend.core import llm_config
from backend.core.llm_factory import LLMFactory

MAX_QUESTION = 2000
PROMPT_BUDGET = 12000
AGENT_TIMEOUT_S = 45.0

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


def build_messages(agent: catalog.AgentSpec, chosen: list[dict],
                   question: str) -> list:
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
    return [SystemMessage(content="\n".join(lines)), HumanMessage(content=question)]


async def _default_runner(provider: dict, messages: list) -> str:
    llm = LLMFactory.get_llm_for_provider(provider)
    resp = await llm.ainvoke(messages)
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip()


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


async def run(question: str, role: str, agent_id: str | None = None,
              skill_codes: list[str] | None = None, runner=None) -> dict:
    """执行一次统一问答；``runner`` 可注入，用于测试/离线评测。"""
    q = (question or "").strip()
    if not (2 <= len(q) <= MAX_QUESTION):
        raise ConsultInvalid(f"问题需 2-{MAX_QUESTION} 字")

    if agent_id:
        agent = catalog.get_agent(agent_id)
        if agent is None:
            raise ConsultInvalid(f"未找到智能体：{agent_id}")
        decision = _explicit_decision(agent)
    else:
        decision = rule_router.route(q)
        if decision["agent"] == "prefilter":
            return {
                "agent": {"id": "prefilter", "name": decision["name"]},
                "routing": decision,
                "appliedSkills": [],
                "answer": decision.get("prefilterReply") or "",
                "elapsedMs": 0,
                "needsHumanReview": False,
                "note": "前置应答：未检索、未调用模型、未使用技能卡。",
            }
        agent = catalog.get_agent(decision["agent"])
        if agent is None:
            raise ConsultUnavailable(f"路由到的智能体未注册：{decision['agent']}")

    if role not in agent.roles:
        raise ConsultForbidden(f"当前角色 {role} 无权使用智能体 {agent.id}")

    chosen = select_skills(agent, skill_codes)
    cap = _max_input(chosen)
    if len(q) > cap:
        raise ConsultInvalid(f"问题长度 {len(q)} 超过生效技能护栏上限 {cap} 字")

    provider = resolve_provider()
    messages = build_messages(agent, chosen, q)
    started = time.perf_counter()
    try:
        answer = await asyncio.wait_for(
            (runner or _default_runner)(provider, messages), timeout=AGENT_TIMEOUT_S)
    except asyncio.TimeoutError:
        raise ConsultUnavailable(
            f"智能体 {agent.id} 执行超时（>{int(AGENT_TIMEOUT_S)}s）") from None

    text = (answer or "").strip()
    if not text:
        raise ConsultUnavailable("模型未返回内容；请检查 provider 状态后重试")

    return {
        "agent": {"id": agent.id, "name": agent.name},
        "routing": decision,
        "appliedSkills": [
            {"code": c["code"], "name": c["name"], "version": int(c.get("version", 0)),
             "tools": list(c.get("toolAllowlist") or [])}
            for c in chosen
        ],
        "declaredTools": sorted({t for c in chosen for t in (c.get("toolAllowlist") or [])}),
        "answer": text[:4000],
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "needsHumanReview": True,
        "note": "技能卡执行口径 + 模型输出，仅供复核；不构成诊断或处方；未自动调用工具。",
    }
