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
# 身份/能力询问（高置信模式，norm 后锚定整句）→ 秒答 CAPABILITY，不碰检索与 LLM。
# 历史教训双向记取：过宽曾误拦医学问题；过窄曾让"你是谁"空跑检索+LLM 后落 fallback。
# 医学线索（MEDICAL_HINT）优先级更高，"你能治胃疼吗"仍走 medical。
# 任务D 扩充：常识问句常见变体（你能干嘛/你会什么/你的功能/怎么用你…）+ 错字容错
# （干嘛/干麻/干吗、什么/神马/啥），句尾容忍"的/语气词"，句尾锚定防"你是谁的学生"之类误拦。
_META_TAIL = r"(呀|啊|呢|呗|么)?"
META_CAP = re.compile(
    # 你是谁 / 你叫什么 / 你是干什么的（含 干嘛/干麻 错字；句尾"的/语气词"容忍）
    r"^(你|您)(是|叫).{0,6}(谁|什么|干嘛|干麻|做什么|干什么)(的)?" + _META_TAIL + "$"
    r"|^你叫什么名字$|^你的名字(是什么)?$|^你是什么(东西|软件|系统|模型)$"
    # 介绍一下你自己 / 介绍一下你（既有语义不动）
    r"|^介绍一下?(你自己|你)?"
    # 你能做什么 / 你能帮我做什么 / 你会干什么 / 你能做啥（含 神马 错字）
    r"|^你(能|会|可以)(帮|做|干).{0,8}(什么|神马|啥)" + _META_TAIL + "$"
    # 你会什么 / 你能做啥（问句词直连句尾）
    r"|^你(能|会|可以)(做|干)?(什么|神马|啥)" + _META_TAIL + "$"
    # 你能干嘛 / 你会干麻 / 你能干吗（嘛/麻/吗 常见错字容错）/ 你能干啥
    r"|^你(能|会)干(嘛|麻|吗|啥)" + _META_TAIL + "$"
    # 你有什么功能 / 你都有什么能力 / 你有啥作用
    r"|^(你|您)(都有什么|有什么|有啥)(功能|作用|用|能力|本领)(吗|么)?$"
    # 你的功能 / 你的功能是什么 / 你的能力有哪些
    r"|^(你|您)的(功能|作用|用处|用途|能力)(是什么|有哪些|呢|吗|么)?$"
    # 怎么用你 / 如何使用你 / 你怎么用
    r"|^(怎么|如何)(使用|用)(你|您)$|^你怎么(使用|用)$"
)

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
CAPABILITY = ("我是 MedAssist 临床决策支持助手，面向医师/药师提供辅助参考，不具备执业资格，不替代诊断与处方。我能："
              "① 循证文献问答（检索权威要点并溯源引用）② 药物相互作用/禁忌核对 ③ 影像与报告的多模态初步所见 "
              "④ 多学科会诊(MDT)。所有输出均为辅助建议，须执业医师/药师复核后使用。")


def prefilter_reply(text: str) -> str | None:
    """命中闲聊/身份 → 返回免 LLM 的固定回复；否则 None（继续走 Agent）。"""
    kind = triage(text)
    if kind == "smalltalk":
        return GREETING
    if kind == "meta":
        return CAPABILITY
    return None


# ---------- 任务C：技术领域守门（仅 literature_ask 调用，极保守） ----------
# 明确技术/编程词表（命中才进入候选；IGNORECASE 覆盖 Python/Python3 等）。
_TECH_HINT = re.compile(
    r"(python|java|javascript|typescript|golang|c\+\+|c#|sql|mysql|redis|"
    r"git|github|docker|kubernetes|k8s|linux|nginx|html|css|json|xml|"
    r"代码|编程|程序|脚本|函数|算法|数据库|正则|编译|调试|bug|服务器|部署|运维)",
    re.IGNORECASE,
)
# 任务1：代码特征词表（片段级识别——用户直接贴代码提问、不含语言名字面也能拦住）。
# 同一保守纪律：命中才进入候选；只认结构特征（关键字+括号/大括号/shebang 等），
# 拒绝裸单词误伤；IGNORECASE 大小写不敏感；医学信号 (_MEDICAL_SIGNAL) 优先级不变。
_CODE_PATTERN = re.compile(
    r"(public\s+static\s+void|system\s*\.\s*out\s*\.\s*println|println\s*\("
    r"|import\s+\w|def\s+\w+\s*\(|class\s+\w+\s*\{|print\s*\("
    r"|console\s*\.\s*log|<html[\s>]|#!\s*/(usr/)?bin|\bnpm\b|pip\s+install)",
    re.IGNORECASE,
)
# 医学信号词（任一命中→绝不拒答，正常走流程；单字词「医/病/药/癌/炎/疼/痛/血/疗/诊/护」
# 覆盖派生词最广，mg 等拉丁词经 lower() 匹配；roc/生存分析/样本量/基因/测序/受试者覆盖
# 医学科研统计场景——增量排查发现的误杀面）。方向保守：宁可漏拒，不可误拒。
_MEDICAL_SIGNAL = re.compile(
    r"(疾病|症状|药|治疗|诊断|患者|病人|临床|剂量|mg|综合征|医|病|疼|痛|癌|炎|"
    r"肿瘤|手术|化验|检查|血|体检|健康|处方|禁忌|复发|感染|发烧|发热|咳嗽|护理|"
    r"病历|住院|门诊|随访|影像|拍片|心电图|过敏|遗传|疫苗|康复|麻醉|预后|疗|诊|护|"
    r"roc|生存分析|样本量|基因|测序|受试者)"
)
OFFTOPIC_REPLY = ("我是医学文献助手，只处理医学/药学/临床问题。"
                  "您的问题属于技术领域，请咨询对应专业渠道。")


def offtopic_tech_reply(text: str) -> str | None:
    """技术领域守门（极保守）：命中明确技术词或代码特征 且 不含任何医学信号 → 返回固定拒答文案；
    否则 None（拿不准就正常走检索流程）。调用方负责：不检索、不入审核队列、不调 LLM，
    并把拒答记为 offtopic_reject 审计事件。"""
    t = (text or "").lower()
    if not (_TECH_HINT.search(t) or _CODE_PATTERN.search(t)):
        return None
    if _MEDICAL_SIGNAL.search(t):
        return None  # 任何医学信号 → 不拦截（如「癌症的python教程」）
    return OFFTOPIC_REPLY
