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

from backend.agents import catalog, consult_metrics, guardrails, model_policy, skills
from backend.agents import router as rule_router
from backend.core import llm_config
from backend.core.llm_factory import LLMFactory
from backend.core.logger import get_logger
from backend.core.medical_audit import PHIRedactor

logger = get_logger(__name__)

MAX_QUESTION = 2000
PROMPT_BUDGET = guardrails.MAX_PROMPT_BUDGET  # 发布准入与运行时共用同一口径（M-35）
AGENT_TIMEOUT_S = 45.0
STREAM_IDLE_TIMEOUT_S = 15.0
MAX_HISTORY_TURNS = 6
MAX_HISTORY_ITEM = 2000
MAX_HISTORY_CHARS = 6000
# 与 ``_done_payload`` 的 answer 截断保持一致：流式累积也按此上限截止，
# 避免慢客户端场景下 parts 随 delta 无限增长（评审 S3）
MAX_ANSWER_CHARS = 4000

# 与医疗域其它问答入口同一套 PHI 脱敏（评审 M-04 整改）：Agent 控制面此前整域未接脱敏，
# 患者身份证/手机/邮箱/病案号会原样进入模型提示。此处只脱敏**入站输入**，不改回答。
_redactor = PHIRedactor()

# 保留段落标记（评审 M-05 整改）：客户端回传的 history 若含这些标记，会被模型误认为
# 我们写入的系统级约束（伪造「【证据边界】」即可绕过证据限制）。回传历史只允许纯对话文本。
_RESERVED_MARKERS = ("【证据边界】", "【边界】", "【生效技能卡】", "【可用工具】",
                     "【长期记忆】", "【已发布技能卡】", "【提示】")


def _neutralize(text: str) -> str:
    """把客户端可控文本里的保留段落标记转义，避免伪造系统级约束。"""
    out = text
    for marker in _RESERVED_MARKERS:
        out = out.replace(marker, marker.replace("【", "[[").replace("】", "]]"))
    return out

_BOUNDARY = guardrails.BOUNDARY  # 评审 M-06：边界文案唯一来源


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
    """输入上限取所有生效技能中最严一档（护栏口径统一到 guardrails 模块）。"""
    return min(MAX_QUESTION, guardrails.effective(chosen)["maxInputChars"])


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
    """校验多轮上下文：仅 user/assistant，条数/单条/总量三重上限；不做服务端持久化。

    入站即脱敏并中和保留段落标记（评审 M-04 / M-05）。
    """
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
        text = _neutralize(_redactor.redact(str(item.get("content") or "").strip()))
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
        # 评审 M-06 / M-07：证据边界与 requireEvidence 的强制标注要求统一由 guardrails 给出
        lines.extend(guardrails.prompt_clauses(chosen))
    else:
        lines.append("【提示】该能力域暂无生效技能卡，按职责与证据边界保守作答。")
    lines.append(_BOUNDARY)
    messages = [SystemMessage(content="\n".join(lines))]
    for turn in history or []:
        cls = HumanMessage if turn["role"] == "user" else AIMessage
        messages.append(cls(content=turn["content"]))
    messages.append(HumanMessage(content=question))
    return messages


def _usage_from(obj) -> dict | None:
    """从模型响应/流式片段提取 provider 真实返回的 token 用量；缺项不估算。"""
    if obj is None:
        return None
    if isinstance(obj, dict):
        if any(k in obj for k in ("input_tokens", "output_tokens",
                                  "prompt_tokens", "completion_tokens")):
            usage = obj
        else:
            usage = obj.get("usage_metadata") or obj.get("token_usage")
    else:
        usage = getattr(obj, "usage_metadata", None)
        if not usage:
            usage = (getattr(obj, "response_metadata", None) or {}).get("token_usage")
    if not isinstance(usage, dict):
        return None
    inp = usage.get("input_tokens", usage.get("prompt_tokens"))
    out = usage.get("output_tokens", usage.get("completion_tokens"))
    if inp is None or out is None:
        return None
    try:
        inp, out = int(inp), int(out)
    except (TypeError, ValueError):
        return None
    if inp < 0 or out < 0:
        return None
    return {"input_tokens": inp, "output_tokens": out}


async def _default_runner(provider: dict, messages: list):
    llm = LLMFactory.get_llm_for_provider(provider)
    resp = await llm.ainvoke(messages)
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip(), _usage_from(resp)


async def _default_streamer(provider: dict, messages: list):
    llm = LLMFactory.get_llm_for_provider(provider)
    async for chunk in llm.astream(messages):
        content = getattr(chunk, "content", chunk)
        if isinstance(content, list):
            content = "".join(str(c) for c in content)
        if content:
            yield {"text": str(content)}
        usage = _usage_from(chunk)
        if usage:
            yield {"usage": usage}


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
    """校验并装配一次问答（不含模型调用）；前置应答直接返回 payload。

    入站问题先做 PHI 脱敏与保留标记中和（评审 M-04 / M-05），再进入路由与提示装配。
    """
    q = _neutralize(_redactor.redact((question or "").strip()))
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


def prepare(question: str, role: str, agent_id: str | None = None,
            skill_codes: list[str] | None = None, history=None) -> dict:
    """前置校验与提示装配（评审 S2）：供路由层在打开 SSE 之前完成。

    角色权限、输入长度、护栏上限、技能卡选择、模型合规都在这里失败，
    这样流式端点可以返回真正的 HTTP 403/422/503，而不是 200 + error 事件。
    失败同样计入 ``error`` 计量（channel=stream），前置校验不留计量盲区。
    """
    started = time.perf_counter()
    try:
        return _prepare(question, role, agent_id, skill_codes, history)
    except ConsultError as exc:
        consult_metrics.record(agent_id or "unknown", "stream", "error",
                               (time.perf_counter() - started) * 1000,
                               reason=_reason(exc))
        raise


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
        # 评审 M-07：回填卡片护栏，说明本次到底按哪一档执行（hitl 恒 True 是安全不变量）
        "skillGuardrails": guardrails.effective(chosen),
        "note": "技能卡执行口径 + 模型输出，仅供复核；不构成诊断或处方；未自动调用工具。",
    }


def _reason(exc: BaseException) -> str:
    """把业务异常映射为计量的 reason 标签（评审 M-25：区分输入非法与模型不可用）。"""
    if isinstance(exc, ConsultForbidden):
        return "forbidden"
    if isinstance(exc, ConsultInvalid):
        return "invalid"
    if isinstance(exc, ConsultUnavailable):
        return "unavailable"
    return "other"


async def run(question: str, role: str, agent_id: str | None = None,
              skill_codes: list[str] | None = None, history=None,
              runner=None) -> dict:
    """执行一次统一问答；``runner`` 可注入，用于测试/离线评测。"""
    started = time.perf_counter()
    try:
        ctx = _prepare(question, role, agent_id, skill_codes, history)
    except ConsultError as exc:
        consult_metrics.record(agent_id or "unknown", "sync", "error",
                               (time.perf_counter() - started) * 1000,
                               reason=_reason(exc))
        raise

    if "prefilter" in ctx:
        consult_metrics.record("prefilter", "sync", "ok",
                               (time.perf_counter() - started) * 1000)
        return ctx["prefilter"]

    agent_key = ctx["agent"].id
    codes = [c["code"] for c in ctx["chosen"]]
    try:
        result = await asyncio.wait_for(
            (runner or _default_runner)(ctx["provider"], ctx["messages"]),
            timeout=AGENT_TIMEOUT_S)
        if isinstance(result, tuple) and len(result) == 2:
            answer, usage = result
        else:
            answer, usage = result, None
        usage = _usage_from(usage) if usage else None
        text = str(answer or "").strip()
        if not text:
            raise ConsultUnavailable("模型未返回内容；请检查 provider 状态后重试")
    except asyncio.TimeoutError:
        consult_metrics.record(agent_key, "sync", "error",
                               (time.perf_counter() - started) * 1000,
                               reason="timeout")
        raise ConsultUnavailable(
            f"智能体 {agent_key} 执行超时（>{int(AGENT_TIMEOUT_S)}s）") from None
    except ConsultError as exc:
        consult_metrics.record(agent_key, "sync", "error",
                               (time.perf_counter() - started) * 1000,
                               reason=_reason(exc))
        raise
    except Exception as e:  # noqa: BLE001 —— 上游异常转为可读失败，不冒泡堆栈
        consult_metrics.record(agent_key, "sync", "error",
                               (time.perf_counter() - started) * 1000,
                               reason="unavailable")
        logger.warning("consult.run.upstream_error", error=type(e).__name__)
        raise ConsultUnavailable("模型服务暂不可用，请稍后重试") from None

    consult_metrics.record(agent_key, "sync", "ok",
                           (time.perf_counter() - started) * 1000, codes, usage)
    return _done_payload(ctx, text, started)


async def stream(question: str, role: str, agent_id: str | None = None,
                 skill_codes: list[str] | None = None, history=None,
                 streamer=None, ctx: dict | None = None):
    """流式问答：先发 meta（路由/技能），再逐段 delta，最后 done。

    ``streamer(provider, messages)`` 为异步生成器，可注入用于测试。
    ``ctx`` 为 ``prepare()`` 的结果：路由层已预装配时直接复用，避免重复读技能卡。
    计量：正常完成计 ok；业务失败计 error；客户端中途断开计 aborted。
    """
    started = time.perf_counter()
    if ctx is None:
        ctx = prepare(question, role, agent_id, skill_codes, history)

    if "prefilter" in ctx:
        payload = ctx["prefilter"]
        consult_metrics.record("prefilter", "stream", "ok",
                               (time.perf_counter() - started) * 1000)
        yield {"type": "meta", **{k: payload[k] for k in (
            "agent", "routing", "appliedSkills", "declaredTools", "needsHumanReview")},
            "historyTurns": 0}
        yield {"type": "done", **payload}
        return

    agent_key = ctx["agent"].id
    codes = [c["code"] for c in ctx["chosen"]]
    finished = False
    failed = False
    try:
        yield {
            "type": "meta",
            "agent": {"id": agent_key, "name": ctx["agent"].name},
            "routing": ctx["decision"],
            "appliedSkills": _skill_views(ctx["chosen"]),
            "declaredTools": sorted({t for c in ctx["chosen"]
                                     for t in (c.get("toolAllowlist") or [])}),
            "needsHumanReview": True,
            "historyTurns": len(ctx["history"]),
        }

        parts: list[str] = []
        total_chars = 0
        last_usage = None
        agen = (streamer or _default_streamer)(ctx["provider"], ctx["messages"])
        it = agen.__aiter__()
        while True:
            try:
                chunk = await asyncio.wait_for(it.__anext__(),
                                               timeout=STREAM_IDLE_TIMEOUT_S)
            except StopAsyncIteration:
                break
            except asyncio.TimeoutError:
                raise ConsultUnavailable(
                    f"模型流式响应超时（>{int(STREAM_IDLE_TIMEOUT_S)}s 无输出）") from None
            except Exception as e:  # noqa: BLE001 —— 上游异常转为可读错误
                logger.warning("consult.stream.upstream_error", error=type(e).__name__)
                raise ConsultUnavailable("模型服务暂不可用，请稍后重试") from None
            if time.perf_counter() - started > AGENT_TIMEOUT_S:
                raise ConsultUnavailable(f"智能体 {agent_key} 执行超时")
            if isinstance(chunk, dict):
                text = str(chunk.get("text") or "")
                usage = _usage_from(chunk.get("usage"))
            else:
                text = str(chunk or "")
                usage = _usage_from(chunk)
            if usage:
                last_usage = usage
            if not text:
                continue
            # 评审 S3：累积缓冲按最终答复上限截断（慢客户端下不再随 delta 线性增长）
            if total_chars < MAX_ANSWER_CHARS:
                parts.append(text)
                total_chars += len(text)
            yield {"type": "delta", "text": text}

        answer = "".join(parts).strip()
        if not answer:
            raise ConsultUnavailable("模型未返回内容；请检查 provider 状态后重试")
        # 先计量再发 done：客户端收到 done 后立即断开也不会被误记为 aborted
        consult_metrics.record(agent_key, "stream", "ok",
                               (time.perf_counter() - started) * 1000, codes, last_usage)
        finished = True
        yield {"type": "done", **_done_payload(ctx, answer, started)}
    except ConsultError as exc:
        failed = True
        consult_metrics.record(agent_key, "stream", "error",
                               (time.perf_counter() - started) * 1000,
                               reason=_reason(exc))
        raise
    except Exception:  # noqa: BLE001 —— 非业务异常同样按失败计量后上抛
        failed = True
        consult_metrics.record(agent_key, "stream", "error",
                               (time.perf_counter() - started) * 1000,
                               reason="other")
        raise
    finally:
        if not finished and not failed:
            consult_metrics.record(agent_key, "stream", "aborted",
                                   (time.perf_counter() - started) * 1000)
