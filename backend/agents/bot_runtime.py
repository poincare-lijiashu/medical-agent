"""G88 Bot 运行时：提示装配、单 Bot 执行、团队并行协作。

与"直接调模型"的差别：
1. **身份**：每次调用都带 Bot 的名字/职责（persona），不是裸 prompt；
2. **能力边界**：只注入该 Bot 绑定的已发布技能卡与工具白名单；
3. **长期记忆**：只注入已确认且未过期的记忆（``bot_memory.context_memories``）；
4. **模型绑定**：按 Bot 的 ``modelProvider`` 取对应 provider，DeepSeek Bot 与 Grok Bot
   可同时存在，互不覆盖；
5. **协作**：团队内并行执行、单 Bot 超时/失败互不影响；结果**不做"共识"包装**，
   统一标记 ``needsHumanReview=True``，由执业人员判断。

安全边界：
- provider/密钥只在服务端使用，返回体只给 provider id 与 model id；
- 单个 Bot 超时 30s、问题 ≤2000 字；失败只回错误类型，不回堆栈/密钥；
- 无可用模型时显式 ``BotUnavailable``，不返回伪造答案。
"""
from __future__ import annotations

import asyncio
import time

from langchain_core.messages import HumanMessage, SystemMessage

from backend.agents import bot_memory, bot_teams, bots, guardrails, model_policy
from backend.core import agent_tools, llm_config
from backend.core.llm_factory import LLMFactory

MAX_QUESTION = 2000
BOT_TIMEOUT_S = 30.0

_BOUNDARY = guardrails.BOUNDARY  # 评审 M-06：与咨询链路同一份边界文案


class BotUnavailable(RuntimeError):
    """Bot 无法执行（未绑定模型/provider 不可用/团队为空）。"""


def resolve_provider(bot: dict) -> dict:
    """解析 Bot 的模型绑定；未配置时显式失败（不回落伪造响应）。"""
    pid = (bot.get("modelProvider") or "default").strip()
    provider = (llm_config.active_provider("chat") if pid == "default"
                else llm_config.find_provider("chat", pid))
    if not provider:
        raise BotUnavailable(
            "Bot 未绑定可用模型：请在管理端配置 chat provider 或为 Bot 指定 modelProvider")
    try:
        model_policy.assert_allowed(provider)
    except model_policy.ModelPolicyViolation as e:
        raise BotUnavailable(str(e)) from e
    if bot.get("modelId"):
        provider = {**provider, "model_id": bot["modelId"]}
    return provider


def build_messages(bot: dict, question: str, cards: list | None = None,
                   memories: list | None = None) -> list:
    """装配 system/user 消息：身份 + 技能卡 + 工具白名单 + 已确认记忆 + 边界。

    评审 M-44：``cards`` / ``memories`` 可由调用方预先加载并复用，
    避免同一次执行里重复整表读（run_bot 会一次取好同时用于计数）。
    """
    q = (question or "").strip()
    if not (2 <= len(q) <= MAX_QUESTION):
        raise BotUnavailable(f"问题需 2-{MAX_QUESTION} 字")

    if cards is None:
        cards = bots.bot_skill_cards(bot)
    if memories is None:
        memories = bot_memory.context_memories(bot["code"])
    lines = [
        f"你是「{bot['name']}」，职责与人格：{bot['persona']}",
        f"所属能力域：{bot['agentId']}。",
    ]
    if cards:
        lines.append("【已发布技能卡】")
        for card in cards:
            lines.append(f"- {card['name']}（{card['code']}）：{card['promptTemplate']}")
            if card.get("evidenceRefs"):
                lines.append(f"  证据引用：{'；'.join(card['evidenceRefs'])}")
        # 评审 M-06 / M-07：补齐「证据边界」并真正消费 requireEvidence
        lines.extend(guardrails.prompt_clauses(cards))
    else:
        # 没有生效技能卡时同样要有证据边界（无证据 → 禁止给具体条文/剂量）
        lines.extend(guardrails.prompt_clauses([]))
    if bot.get("toolAllowlist"):
        lines.append("【可用工具】（只能调用下列工具，不得越权）")
        for name in bot["toolAllowlist"]:
            spec = agent_tools.TOOLS.get(name)
            if spec:
                lines.append(f"- {name}：{spec.title} —— {spec.description}")
    if memories:
        lines.append("【长期记忆】（已确认，可直接参考）")
        for mem in memories:
            lines.append(f"- {mem['key']}: {mem['value']}（来源：{mem['source']}）")
    lines.append(_BOUNDARY)
    return [SystemMessage(content="\n".join(lines)), HumanMessage(content=q)]


async def _default_runner(provider: dict, messages: list) -> str:
    llm = LLMFactory.get_llm_for_provider(provider)
    resp = await llm.ainvoke(messages)
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip()


async def run_bot(bot: dict, question: str, runner=None) -> dict:
    """执行单个 Bot（runner 可注入，用于测试/离线评测）。"""
    provider = resolve_provider(bot)
    # 评审 M-44：技能卡与记忆各只加载一次，提示装配与计数复用同一份结果
    cards = bots.bot_skill_cards(bot)
    memories = bot_memory.context_memories(bot["code"])
    messages = build_messages(bot, question, cards=cards, memories=memories)
    started = time.perf_counter()
    try:
        answer = await asyncio.wait_for((runner or _default_runner)(provider, messages),
                                        timeout=BOT_TIMEOUT_S)
    except asyncio.TimeoutError:
        raise BotUnavailable(f"Bot {bot['code']} 执行超时（>{int(BOT_TIMEOUT_S)}s）")
    return {
        "botCode": bot["code"],
        "name": bot["name"],
        "agentId": bot["agentId"],
        "modelProvider": bot.get("modelProvider") or "default",
        "modelId": provider.get("model_id"),
        "status": "OK",
        "answer": (answer or "").strip()[:4000],
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "memoryCount": len(memories),
        "skillCount": len(cards),
        "skillGuardrails": guardrails.effective(cards),
        "hitl": True,
    }


async def collaborate(team: dict, question: str, runner=None) -> dict:
    """团队并行协作：各 Bot 独立作答，失败隔离；不合成"共识结论"。"""
    members = bot_teams.team_bots(team)
    if len(members) < 2:
        raise BotUnavailable("团队成员不足 2 个（可能已被停用），无法协作")
    results = await asyncio.gather(
        *(run_bot(bot, question, runner) for bot in members),
        return_exceptions=True,
    )
    opinions = []
    failed = 0
    for bot, result in zip(members, results):
        if isinstance(result, Exception):
            failed += 1
            opinions.append({
                "botCode": bot["code"], "name": bot["name"],
                "modelProvider": bot.get("modelProvider") or "default",
                "status": "FAILED",
                # 评审 M-10：对外只给稳定错误码，异常类型名不进出响应体
                "error": "BOT_FAILED",
                "hitl": True,
            })
        else:
            opinions.append(result)
    providers = sorted({o.get("modelProvider") for o in opinions if o.get("status") == "OK"})
    return {
        "team": team["code"],
        "question": question,
        "opinions": opinions,
        "failed": failed,
        "modelDiversity": providers,
        "needsHumanReview": True,
        "note": "多 Bot 意见并列展示，不构成共识或最终诊断；请执业人员复核后使用。",
    }
