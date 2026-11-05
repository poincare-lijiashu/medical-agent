"""G86 主路由智能体（supervisor / router 模式，确定性优先）。

为什么先做规则路由而不是让 LLM 路由：
- 医疗场景要求**可解释、可复现、可回归**：同样的输入必须落到同一个子智能体，
  否则一次模型波动就可能把"药物相互作用"分派给文献助手；
- 规则路由零 Token、零外网依赖，院内离线可跑；LLM 路由作为后续增强（仅在规则
  低置信时兜底），不改变主链路；
- 路由结果携带命中原因与候选列表，便于人工核对与 A/B 对比。

多智能体边界：
- ``agent_multi_agent`` 关闭（默认）→ 单智能体直达，``strategy=single``；
- 开启 → 仍只有一个**主责**智能体，另附最多 2 个候选供 supervisor 汇总，
  ``strategy=supervisor``；不做无限广播，避免"每个问题都开三科会诊"的成本与噪音。
"""
from __future__ import annotations

import re
from collections import OrderedDict

from backend.agents.catalog import get_agent
from backend.core import agent_flags
from backend.core import intent

MAX_HOPS = 2
FALLBACK_AGENT = "literature"

# 顺序即优先级：会诊 > 药物 > 影像 > 病例 > 质控 > 文献
_RULES: "OrderedDict[str, tuple[str, ...]]" = OrderedDict([
    ("mdt", ("多学科", "会诊", "mdt", "联合门诊", "多科")),
    ("drug", ("药", "相互作用", "禁忌", "剂量", "处方", "不良反应", "重复用药", "配伍")),
    ("imaging", ("影像", "ct", "核磁", "磁共振", "超声", "x线", "胸片", "阅片", "放射", "摄片")),
    ("case", ("病例", "总结", "摘要", "出院小结", "病程", "现病史")),
    ("qc", ("质控", "病历质量", "病案", "首页", "缺陷", "编码", "归档")),
    ("literature", ("文献", "指南", "证据", "研究", "循证", "meta", "rct", "综述")),
])

_MDT_MULTI = re.compile(r"(会诊|多学科|多科|联合门诊|mdt)", re.IGNORECASE)


def _matches(question: str, words: tuple[str, ...]) -> list[str]:
    q = (question or "").lower()
    return [w for w in words if w.lower() in q]


def _candidates(question: str) -> list[dict]:
    scored = []
    for agent_id, words in _RULES.items():
        hits = _matches(question, words)
        if hits:
            scored.append({"agent": agent_id, "hits": hits, "score": len(hits)})
    scored.sort(key=lambda c: (-c["score"], list(_RULES).index(c["agent"])))
    return scored


def route(question: str, multi_agent: bool | None = None) -> dict:
    """计算路由结果（纯函数；不执行任何智能体、不访问外部服务）。

    :param multi_agent: 显式覆盖开关（None=读运行时开关），便于管理端预览两种策略。
    """
    q = (question or "").strip()
    kind = intent.triage(q)
    if kind in ("smalltalk", "meta"):
        reply = intent.prefilter_reply(q) or ""
        return {
            "agent": "prefilter",
            "name": "前置应答",
            "confidence": 1.0,
            "reason": f"意图分类为 {kind}，免检索/免模型直接应答",
            "strategy": "single",
            "candidates": [],
            "maxHops": 0,
            "fallbackAgent": FALLBACK_AGENT,
            "multiAgentEnabled": bool(agent_flags.get_flag("agent_multi_agent")
                                      if multi_agent is None else multi_agent),
            "hitl": False,
            "prefilterReply": reply,
        }

    candidates = _candidates(q)
    if not candidates:
        agent_id = FALLBACK_AGENT
        confidence = 0.4
        reason = "未命中专科关键词，回落文献助手（保守策略）"
        hits: list[str] = []
    else:
        top = candidates[0]
        agent_id = top["agent"]
        hits = top["hits"]
        confidence = min(0.95, 0.55 + 0.1 * len(hits))
        reason = f"命中 {agent_id} 关键词：{'、'.join(hits)}"

    spec = get_agent(agent_id)
    enabled = bool(agent_flags.get_flag("agent_multi_agent")
                   if multi_agent is None else multi_agent)
    # 多智能体只挂"第二意见"候选；关闭时保持单智能体直达（候选仅主责）
    second = [c for c in candidates[1:] if c["agent"] != agent_id][:1] if enabled else []
    if agent_id == "mdt":
        second = []
        enabled = enabled and bool(_MDT_MULTI.search(q))

    return {
        "agent": agent_id,
        "name": spec.name if spec else agent_id,
        "confidence": round(confidence, 2),
        "reason": reason,
        "strategy": "supervisor" if enabled and second else "single",
        "candidates": (
            [{"agent": agent_id, "score": confidence, "reason": reason}]
            + [{"agent": c["agent"], "score": round(0.5 + 0.1 * c["score"], 2),
                "reason": f"命中 {c['agent']} 关键词：{'、'.join(c['hits'])}"} for c in second]
        ),
        "maxHops": MAX_HOPS if enabled and second else 1,
        "fallbackAgent": FALLBACK_AGENT,
        "multiAgentEnabled": enabled,
        "hitl": bool(spec.hitl) if spec else True,
        "prefilterReply": None,
    }
