"""零 Token 意图前置分类（编排层，借鉴 MedAgent 蓝图）。

便宜在先、贵的兜底：寒暄/感谢/道别 用 frozenset 整句匹配、身份/能力用正则，完全不花 LLM Token；
其余判为「医学」，交下游 Agent（温度0路由/检索/生成）。非对称代价：宁让边缘句多走检索，
也不把真医学问题误判成寒暄——故只收录高置信闲聊/身份类，医学判定保守默认放行。
"""
from __future__ import annotations

import re

# 归一化：去空白与常见标点、转小写（对中英混排稳健）
_STRIP = re.compile(r"[\s。！~，,.!、~？?；;：:]+")

SMALLTALK = frozenset({
    "你好", "您好", "hi", "hello", "在吗", "在么", "早上好", "下午好", "晚上好",
    "谢谢", "多谢", "感谢", "thanks", "thankyou", "好的", "好", "收到", "明白了", "了解",
    "再见", "拜拜", "bye", "88", "辛苦了", "麻烦了", "不用谢", "不客气",
})
# 身份询问统一放行走 Agent（保留寒暄免 LLM 的能力，身份这种有意义的问题交给模型直接回答）
META_CAP = re.compile(r"(?!)")  # 永远不匹配（保留模块接口）

MEDICAL_HINT = re.compile(
    r"(疼|痛|症状|诊断|药|治疗|血压|血糖|指标|化验|检查|报告|CT|核磁|超声|剂量|"
    r"疾病|癌|炎|综合征|急性|慢性|复发|转诊|随访|相互作用|禁忌|HbA1c|LDL|eGFR)"
)


def _norm(text: str) -> str:
    return _STRIP.sub("", (text or "").strip().lower())


def triage(text: str) -> str:
    """返回 'smalltalk' | 'meta' | 'medical'。含医学线索一律 medical（保守放行）。"""
    if MEDICAL_HINT.search(text or ""):
        return "medical"
    n = _norm(text)
    if not n:
        return "smalltalk"
    if n in SMALLTALK:
        return "smalltalk"
    if META_CAP.search(text or ""):
        return "meta"
    # 未命中闲聊/身份且无医学词 → 保守按 medical 交给下游（非对称代价：不误拦真问题）
    return "medical"


GREETING = ("你好，我是 MedAssist 临床决策支持助手（仅供医师参考，不替代诊断/处方）。"
            "可帮你做：循证文献问答、药物相互作用核对、影像/报告初步所见、MDT 会诊。请提出临床问题。")
CAPABILITY = ("我能：① 循证文献助手（检索权威要点并溯源引用）② 药物相互作用/禁忌核对 ③ 影像与报告的"
              "多模态初步所见 ④ 多学科会诊(MDT)。所有输出为辅助建议，须执业医师/药师复核，不提供处方。")


def prefilter_reply(text: str) -> str | None:
    """命中闲聊/身份 → 返回免 LLM 的固定回复；否则 None（继续走 Agent）。"""
    kind = triage(text)
    if kind == "smalltalk":
        return GREETING
    if kind == "meta":
        return CAPABILITY
    return None
