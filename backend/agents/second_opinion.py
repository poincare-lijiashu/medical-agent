"""G89 双模型第二意见（仅国产/本地模型，可切换）。

设计立场：
- **不是投票**：两个模型的意见并列展示，绝不做"少数服从多数"；
- **不引入第三个模型做裁判**（裁判本身也会偏），只给出**可复核的启发式差异信号**：
  词面重合度、数字/剂量冲突；明确标注为"信号"而非临床结论；
- **模型合规**：provider 必须通过 ``model_policy``（本地/国产），境外与未知域名拒绝；
- **故障隔离**：单模型超时/失败不影响另一个；两者都失败才整体不可用；
- 结果恒为 ``needsHumanReview=True``。

前端可选择两个 provider（DeepSeek / Qwen / 本地 vLLM 等），也可留空由后端取前两个可用项。
"""
from __future__ import annotations

import asyncio
import re
import time

from langchain_core.messages import HumanMessage, SystemMessage

from backend.agents import guardrails, model_policy
from backend.core.llm_factory import LLMFactory

MAX_QUESTION = 2000
TIMEOUT_S = 45.0

# 评审 M-06：与咨询 / Bot 链路共用同一份边界与证据边界，不再各写一套措辞
_SYSTEM = "\n".join([
    "你是临床辅助决策支持助手。",
    guardrails.EVIDENCE_BOUNDARY,
    guardrails.CITE_CLAUSE,
    guardrails.BOUNDARY,
])

# 数字/剂量（含小数与百分号前的数字）；仅用于"数字冲突"提示，不做语义判断
_NUM = re.compile(r"\d+(?:\.\d+)?")
_PUNCT = re.compile(r"[\s\u3000。，、；：！？,.!?;:\"'“”‘’（）()\[\]【】<>《》/\\|—\-_=+*#`~]+")


class SecondOpinionUnavailable(RuntimeError):
    """可用模型不足/全部失败。"""


def _normalize(text: str) -> str:
    return _PUNCT.sub("", (text or "").lower())


def _bigrams(text: str) -> set[str]:
    s = _normalize(text)
    if len(s) < 2:
        return {s} if s else set()
    return {s[i:i + 2] for i in range(len(s) - 1)}


def divergence(a: str, b: str) -> dict:
    """启发式差异信号（不是临床一致性结论）。"""
    set_a, set_b = _bigrams(a), _bigrams(b)
    union = set_a | set_b
    overlap = round(len(set_a & set_b) / len(union), 3) if union else 0.0
    nums_a, nums_b = set(_NUM.findall(a or "")), set(_NUM.findall(b or ""))
    conflicts = sorted(nums_a ^ nums_b)
    level = "high" if overlap >= 0.6 else ("medium" if overlap >= 0.3 else "low")
    return {
        "level": level,
        "lexicalOverlap": overlap,
        "numericConflicts": conflicts[:20],
        "note": "词面重合度与数字差异仅为复核提示，不代表临床一致性；请人工核对证据。",
    }


async def _default_runner(provider: dict, messages: list) -> str:
    llm = LLMFactory.get_llm_for_provider(provider)
    resp = await llm.ainvoke(messages)
    content = getattr(resp, "content", resp)
    if isinstance(content, list):
        content = "".join(str(c) for c in content)
    return str(content or "").strip()


async def run(question: str, provider_ids: list[str] | None = None, runner=None) -> dict:
    q = (question or "").strip()
    if not (2 <= len(q) <= MAX_QUESTION):
        raise SecondOpinionUnavailable(f"问题需 2-{MAX_QUESTION} 字")

    if provider_ids:
        ids = [str(p).strip() for p in provider_ids if str(p).strip()]
        if len(ids) != 2:
            raise SecondOpinionUnavailable("第二意见需要恰好选择 2 个模型提供方")
        providers = [model_policy.resolve_allowed(pid) for pid in ids]
    else:
        providers = model_policy.allowed_chat_providers()[:2]
    if len(providers) < 2:
        raise SecondOpinionUnavailable(
            "可用的本地/国产模型不足 2 个：请在管理端配置至少两个合规 provider")

    messages = [SystemMessage(content=_SYSTEM), HumanMessage(content=q)]
    started = time.perf_counter()
    results = await asyncio.gather(
        *(asyncio.wait_for((runner or _default_runner)(p, messages), timeout=TIMEOUT_S)
          for p in providers),
        return_exceptions=True,
    )

    opinions = []
    for provider, result in zip(providers, results):
        view = model_policy.provider_view(provider)
        if isinstance(result, Exception):
            opinions.append({
                # 评审 M-10：对外只给稳定错误码，异常类型名不进响应体
                **view, "status": "FAILED", "error": "MODEL_FAILED",
                "answer": None, "hitl": True,
            })
        else:
            opinions.append({
                **view, "status": "OK", "answer": (result or "").strip()[:4000],
                "hitl": True,
            })

    ok = [o for o in opinions if o["status"] == "OK"]
    if not ok:
        raise SecondOpinionUnavailable("两个模型均未返回结果，请稍后重试")
    diff = divergence(ok[0]["answer"], ok[1]["answer"]) if len(ok) >= 2 else None
    return {
        "question": q,
        "opinions": opinions,
        "failed": len(opinions) - len(ok),
        "divergence": diff,
        "needsHumanReview": True,
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "note": "两个模型意见并列展示，不构成共识；差异信号仅供复核提示。",
    }
