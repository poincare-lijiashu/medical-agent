"""Agent 域护栏的唯一来源（2026-11-06 评审 M-06 / M-07 整改）。

整改前的问题：
1. **边界文案四处各写一份**（``consult._BOUNDARY`` / ``bot_runtime._BOUNDARY`` /
   ``second_opinion._SYSTEM`` / ``skill_library._proposal_payload``），措辞不一致，
   且 Bot 链路**没有**「证据边界」条款——模型可以补出未提供的指南条文/剂量；
2. 技能卡的 ``guardrails.requireEvidence`` / ``guardrails.hitl`` 落库后
   **全仓无人读取**，审核人勾的"必须引用证据"对运行时没有任何约束力。

本模块提供：
- ``BOUNDARY``：统一边界文案（含"提示人工复核"）；
- ``evidence_clause(cards)``：「证据边界」条款——有证据引用时约束"只能引用这些"，
  无证据引用时更强：禁止给具体条文/剂量数值；
- ``CITE_CLAUSE``：``requireEvidence=True`` 时追加的逐条标注要求；
- ``effective(cards)``：汇总生效技能卡的护栏（任一卡要求即整体要求，只收紧不放宽）；
- ``prompt_clauses(cards)``：装配提示时应追加的条款列表。

注意：医疗域 ``needsHumanReview`` 恒为 True，这是安全不变量；卡上的 ``hitl``
通过 ``effective()["hitl"]`` 回填到响应体的 ``skillGuardrails``，供前端解释
"这张卡本身要求人工复核"，而不是拿它去放宽全局不变量。
"""
from __future__ import annotations

BOUNDARY = (
    "【边界】你是临床辅助工具，不是医疗器械；输出仅供执业医师/药师参考，"
    "不得替代诊断、处方或治疗决策；不得编造文献/指南/证据；"
    "证据不足时必须明确说明不确定性，并提示人工复核。"
)

EVIDENCE_BOUNDARY = (
    "【证据边界】只可引用上述证据或输入中明确提供的信息；"
    "不得补充未提供的指南条文、文献结论或数值。"
)

NO_EVIDENCE_BOUNDARY = (
    "【证据边界】本次未提供任何证据引用：不得给出具体指南条文、文献结论或剂量数值；"
    "须明确说明证据不足，并建议人工查阅原始来源。"
)

CITE_CLAUSE = (
    "【证据要求】每条结论须标注依据（证据引用名称或输入中的原始信息）；"
    "无依据的结论必须标注「无证据支持」，不得省略。"
)

DEFAULT_MAX_INPUT = 2000
MIN_MAX_INPUT = 200

# 单次问答可注入的技能卡提示总量上限（与 consult/发布准入共用同一口径，评审 M-35）
MAX_PROMPT_BUDGET = 12000


def effective(cards: list[dict] | None) -> dict:
    """汇总技能卡护栏：``requireEvidence`` / ``hitl`` 取或，输入上限取最严一档。"""
    require = False
    hitl = False
    caps: list[int] = []
    for card in cards or []:
        g = card.get("guardrails") or {}
        require = require or bool(g.get("requireEvidence"))
        hitl = hitl or bool(g.get("hitl"))
        try:
            caps.append(int(g.get("maxInputChars") or DEFAULT_MAX_INPUT))
        except (TypeError, ValueError):
            caps.append(DEFAULT_MAX_INPUT)
    return {
        "requireEvidence": require,
        "hitl": hitl,
        "maxInputChars": max(MIN_MAX_INPUT, min(caps)) if caps else DEFAULT_MAX_INPUT,
    }


def evidence_clause(cards: list[dict] | None) -> str:
    """有任一证据引用 → 限定引用面；完全没有 → 禁止给具体条文/剂量。"""
    has_refs = any((c.get("evidenceRefs") or []) for c in (cards or []))
    return EVIDENCE_BOUNDARY if has_refs else NO_EVIDENCE_BOUNDARY


def prompt_clauses(cards: list[dict] | None) -> list[str]:
    """返回应追加到 system 提示的护栏条款（真正消费 requireEvidence）。"""
    clauses = [evidence_clause(cards)]
    if effective(cards)["requireEvidence"]:
        clauses.append(CITE_CLAUSE)
    return clauses
