"""PHI 脱敏 + 追加式审计日志（M1 共享核心，权威版）。

设计：
- PHIRedactor：正则替换身份证/手机/邮箱为占位符（确定性、无状态）。
- AuditLog：追加式 JSONL 落盘，无 delete/update 方法（满足审计不可篡改）。
  write/append/record 三种调用形状均兼容（内部归一化），杜绝跨模块签名漂移。
"""
from __future__ import annotations

import json
import os
import re
from collections import deque
from datetime import datetime, timezone

from backend.core.logger import get_logger

_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_IDCARD = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")  # 用数字环视，避开中文字符导致 \b 失效
_MRN = re.compile(r"(?:住院号|病案号|门诊号|就诊号|MRN)\s*[:：]?\s*[A-Za-z0-9\-]{4,}")
_SOCIAL = re.compile(r"\b\d{3}-\d{4}-\d{4}-\d{1,2}\b")  # 社保卡式(需连字符，保守)
# 中文姓名：仅上下文锚定（姓名标签引导，或“患者/病人 + 人名 + 性别/年龄”形态特征），
# 孤立两字词（如症状“胃疼”）绝不误伤。
_NAME = re.compile(
    r"(?:患者姓名|病人姓名|医师签名|医师|签名|联系人|姓名)\s*[:：]\s*[\u4e00-\u9fa5·]{2,4}"
    r"|(?:患者|病人)\s*[\u4e00-\u9fa5·]{2,4}(?=\s*[，,、]\s*(?:男|女|年龄|岁))"
)
# 中文地址：住址标签引导，或“行政区 + 道路 + 门牌”形态（省/市/区县 + 路/街/巷/镇等 + 号/栋/室）。
_ADDR = re.compile(
    r"(?:家庭住址|住址|地址|现居)\s*[:：]?\s*[\u4e00-\u9fa50-9A-Za-z]{4,40}"
    r"|[\u4e00-\u9fa5]{2,8}(?:省|自治区)[\u4e00-\u9fa5]{1,8}(?:市|区|县|州)"
    r"|[\u4e00-\u9fa5]{2,6}(?:市|区|县|旗)[\u4e00-\u9fa5]{1,8}(?:路|街道|街|巷|道|村|镇|小区|大厦|大道)[\u4e00-\u9fa50-9A-Za-z]{0,12}(?:号|栋|幢|单元|室|楼)?"
)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AUDIT_DIR = os.path.join(BASE_DIR, "data", "audit")
AUDIT_FILE = os.path.join(AUDIT_DIR, "audit.jsonl")

# 旧 2-str 调用的用户名启发式：本系统用户名（doctor01/admin01 等）为「字母开头 + 含数字」；动作词（query 等）不含数字
_USERNAMEISH = re.compile(r"[A-Za-z][A-Za-z0-9_\-.]*\d")


class PHIRedactor:
    def redact(self, text: str) -> str:
        t = _IDCARD.sub("[REDACTED-ID]", text)
        t = _MRN.sub("[REDACTED-MRN]", t)
        t = _EMAIL.sub("[REDACTED-EMAIL]", t)   # 先邮箱，避免手机正则吃掉号码型邮箱前缀
        t = _PHONE.sub("[REDACTED-PHONE]", t)
        t = _SOCIAL.sub("[REDACTED-ID]", t)
        t = _NAME.sub("[REDACTED-NAME]", t)     # 上下文锚定的中文姓名（防误伤症状词）
        t = _ADDR.sub("[REDACTED-ADDR]", t)     # 住址标签 / 行政区+道路形态
        return t


class AuditLog:
    """追加式审计。禁止删除/更新。"""

    def __init__(self, path: str = AUDIT_FILE):
        self.path = path
        self.entries = deque(maxlen=500)  # 有界缓存；权威记录落 JSONL
        self._bootstrap()

    def _bootstrap(self) -> None:
        """启动时从 JSONL 尾部回填有界缓存（只读文件末尾 256KB，避免大文件全量载入 OOM）。"""
        try:
            if not os.path.isfile(self.path):
                return
            size = os.path.getsize(self.path)
            window = min(size, 256 * 1024)
            with open(self.path, encoding="utf-8", errors="replace") as f:
                if size > window:
                    f.seek(size - window)
                    f.readline()  # 丢弃可能被截断的首个残行
                tail = f.readlines()[-500:]
            for line in tail:
                line = line.strip()
                if not line:
                    continue
                try:
                    self.entries.append(json.loads(line))
                except Exception:  # noqa: BLE001
                    continue
        except Exception:  # noqa: BLE001
            pass

    def _emit(self, event_type: str, actor: str, payload: dict) -> None:
        entry = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "event_type": event_type,
            "actor": actor,
            "payload": payload,
        }
        self.entries.append(entry)
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def write(self, event_type: str = "event", action: str = "", actor: str = "system",
              payload: dict | None = None, **kw) -> None:
        """审计写入（显式签名）：write(event_type=..., action=..., actor=..., payload=...)。

        旧宽容调用兜底归一化（新代码一律 kwargs；兜底防外部遗漏调用点再次错位/炸掉）：
        - write("mod", "act", "user")          → 旧 3-str (event_type, action, actor)，直映射
        - write("literature", "query", {...})  → 旧 (event_type, action, payload)：dict 落到 actor 位，
          归一回 payload、actor=system（历史审计里 actor="query"/"answered"/"scaffold" 即此形态所致）
        - write("literature", "doctor01")      → 旧 2-str (event_type, actor)：第二参像用户名（含数字）
          时归一为 actor
        - write({...})                         → 首参 dict 视为 payload
        """
        # 防御：疑似 (actor, action 文案) 反向旧调用（历史错位记录 actor="query"/"scaffold" 的来源形态）。
        # 启发式：首参像用户名（字母开头含数字）而次参像动作文案（含空格或非用户名形态）→ 记 warning，不阻断。
        if (isinstance(event_type, str) and isinstance(action, str) and action
                and _USERNAMEISH.fullmatch(event_type)
                and (" " in action or not _USERNAMEISH.fullmatch(action))):
            get_logger(__name__).warning("audit.write.suspicious_args",
                                         event_type=event_type, action=action[:60])
        if isinstance(event_type, dict):  # 首参 dict → payload
            if payload is None:
                payload = event_type
            event_type = "event"
        if isinstance(actor, dict):  # 旧 (event_type, action, payload) 形态
            if payload is None:
                payload = actor
            actor = "system"
        if (action and actor == "system" and payload is None and not kw
                and _USERNAMEISH.fullmatch(action)):
            event_type, actor, action = event_type, action, ""  # 旧 2-str (event_type, actor)
        payload = dict(payload) if payload else {}
        if action:
            payload.setdefault("action", action)
        for k, v in kw.items():
            if k in ("event_type", "actor", "payload"):
                continue
            payload[k] = v
        self._emit(event_type, actor, payload)

    # 别名，兼容既有调用
    def append(self, event_type: str = "event", actor: str = "system", payload: dict | None = None) -> None:
        self._emit(event_type, actor, payload or {})

    def record(self, actor: str = "system", action: str = "event", payload: dict | None = None) -> None:
        self._emit(action, actor, payload or {})

    def recent(self, n: int = 8) -> list:
        """最近 n 条（新→旧），供概览活动流。"""
        return list(self.entries)[-n:][::-1]


_log = AuditLog()


def get_audit_logger() -> AuditLog:
    return _log
