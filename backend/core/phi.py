"""PHI（可识别个人信息）识别与脱敏的**单一实现**（评审 01-S14）。

此前手机号/身份证/邮箱正则分别复制在四处：
``core/medical_audit.py``、``agents/bot_memory.py``、``agents/skill_library.py``、
``core/web_search.py``。只改一处就会产生"某条链路漏脱敏"的静默漂移，因此把
**模式与判定**收敛到这里；占位符文案仍由调用方决定（审计用 ``[REDACTED-PHONE]``、
出网用 ``[PHONE]``，二者语义不同：前者表明"已脱敏"，后者尽量不破坏查询语法）。

设计约束：
- 只做确定性正则，不引入模型/词典，保证幂等、可复现、无外部依赖；
- 中文姓名/住址仅**上下文锚定**匹配（见 ``NAME``/``ADDR`` 的注释），不误伤症状词；
- 本模块不写审计、不持状态，可被任意链路安全复用。
"""
from __future__ import annotations

import re

# 手机号（中国大陆，避免被更长数字串误匹配）
PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
# 18 位身份证（用数字环视，避开中文字符导致 \b 失效）
ID_CARD = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# 住院号/病案号/门诊号/就诊号（标签引导）
MRN = re.compile(r"(?:住院号|病案号|门诊号|就诊号|MRN)\s*[:：]?\s*[A-Za-z0-9\-]{4,}")
# 社保卡式（需连字符，保守）
SOCIAL = re.compile(r"\b\d{3}-\d{4}-\d{4}-\d{1,2}\b")
# 中文姓名：仅上下文锚定（姓名标签引导，或"患者/病人 + 人名 + 性别/年龄"形态特征），
# 孤立两字词（如症状"胃疼"）绝不误伤。
NAME = re.compile(
    r"(?:患者姓名|病人姓名|医师签名|医师|签名|联系人|姓名)\s*[:：]\s*[\u4e00-\u9fa5·]{2,4}"
    r"|(?:患者|病人)\s*[\u4e00-\u9fa5·]{2,4}(?=\s*[，,、]\s*(?:男|女|年龄|岁))"
)
# 中文地址：住址标签引导，或"行政区 + 道路 + 门牌"形态（省/市/区县 + 路/街/巷/镇等 + 号/栋/室）。
ADDR = re.compile(
    r"(?:家庭住址|住址|地址|现居)\s*[:：]?\s*[\u4e00-\u9fa50-9A-Za-z]{4,40}"
    r"|[\u4e00-\u9fa5]{2,8}(?:省|自治区)[\u4e00-\u9fa5]{1,8}(?:市|区|县|州)"
    r"|[\u4e00-\u9fa5]{2,6}(?:市|区|县|旗)[\u4e00-\u9fa5]{1,8}(?:路|街道|街|巷|道|村|镇|小区|大厦|大道)[\u4e00-\u9fa50-9A-Za-z]{0,12}(?:号|栋|幢|单元|室|楼)?"
)

# 占位符（审计/日志口径：明确"已脱敏"）
REDACTED_PHONE = "[REDACTED-PHONE]"
REDACTED_ID = "[REDACTED-ID]"
REDACTED_EMAIL = "[REDACTED-EMAIL]"
REDACTED_MRN = "[REDACTED-MRN]"
REDACTED_NAME = "[REDACTED-NAME]"
REDACTED_ADDR = "[REDACTED-ADDR]"


def redact(text: str) -> str:
    """全量脱敏（审计/入模型前口径），替换为 ``[REDACTED-*]``。"""
    t = ID_CARD.sub(REDACTED_ID, text or "")
    t = MRN.sub(REDACTED_MRN, t)
    t = EMAIL.sub(REDACTED_EMAIL, t)   # 先邮箱，避免手机正则吃掉号码型邮箱前缀
    t = PHONE.sub(REDACTED_PHONE, t)
    t = SOCIAL.sub(REDACTED_ID, t)
    t = NAME.sub(REDACTED_NAME, t)     # 上下文锚定的中文姓名（防误伤症状词）
    t = ADDR.sub(REDACTED_ADDR, t)     # 住址标签 / 行政区+道路形态
    return t


def has_phi(text: str) -> bool:
    """是否含任意 PHI（含姓名/住址/病案号等间接标识）。"""
    value = text or ""
    return redact(value) != value


def has_direct_identifier(text: str) -> bool:
    """是否含**直接**可识别标识（手机号/身份证/邮箱）。

    长期记忆与出网检索用这一档：只拦"能直接定位到个人"的字段，
    不对姓名/住址做拒绝（会大量误伤临床文本）。
    """
    value = text or ""
    return bool(PHONE.search(value) or ID_CARD.search(value) or EMAIL.search(value))


def redact_direct(text: str, phone: str = "[PHONE]", id_card: str = "[ID]",
                  email: str = "[EMAIL]") -> str:
    """出网前脱敏：只替换直接标识，占位符尽量不破坏检索语法。

    邮箱必须**先**替换：``13800138000@example.com`` 若先跑手机正则会把前缀吃掉。
    """
    s = EMAIL.sub(email, text or "")
    s = ID_CARD.sub(id_card, s)
    s = PHONE.sub(phone, s)
    return s
