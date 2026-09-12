"""PHI 脱敏 + 追加式审计日志（M1 共享核心，权威版）。

设计：
- PHIRedactor：正则替换身份证/手机/邮箱为占位符（确定性、无状态）。
- AuditLog：追加式 JSONL 落盘，无 delete/update 方法（满足审计不可篡改）。
  write/append/record 三种调用形状均兼容（内部归一化），杜绝跨模块签名漂移。
- B2 轮转归档：主文件超 5MB 或跨天 → 重命名 audit-YYYYMMDD-HHMMSS.jsonl 并重建主文件；
  超 30 天的历史轮转文件移入 data/audit/archive/ 并 gzip 压缩（启动/轮转时清理）。
  轮转与追加在同一把锁内完成，任何轮转/归档异常仅告警，绝不阻断审计写入。
"""
from __future__ import annotations

import contextvars
import gzip
import json
import os
import re
import shutil
import threading
import time
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
ARCHIVE_DIR = os.path.join(AUDIT_DIR, "archive")  # B2：gzip 归档目录

# B2 轮转/归档参数（模块级常量，测试可 monkeypatch）：主文件 5MB 或跨天轮转；历史保留 30 天
_ROTATE_BYTES = 5 * 1024 * 1024
_ARCHIVE_DAYS = 30

# 旧 2-str 调用的用户名启发式：本系统用户名（doctor01/admin01 等）为「字母开头 + 含数字」；动作词（query 等）不含数字
_USERNAMEISH = re.compile(r"[A-Za-z][A-Za-z0-9_\-.]*\d")

# ---- 入口渠道标记（MCP 接入）----
# 请求级 contextvar：main.py 的 AuditChannelMiddleware 读请求头 X-MedAssist-Channel
# （如 "mcp"）后 set；本请求内产生的审计事件 payload 自动带 channel 字段，区分入口。
# asyncio.to_thread 会复制 context，线程池内的审计写入同样能取到渠道值。
_channel: contextvars.ContextVar = contextvars.ContextVar("audit_channel", default="")


def set_channel(value: str):
    """标记当前请求的入口渠道（如 "mcp"）；返回 token 供 reset_channel 恢复（中间件 finally 用）。"""
    return _channel.set((value or "").strip().lower()[:32])


def reset_channel(token) -> None:
    """恢复渠道 contextvar（请求结束时调用，防上下文泄漏到连接复用场景）。"""
    _channel.reset(token)


def current_channel() -> str:
    """当前请求的入口渠道；未标记（普通 HTTP 前端请求）返回空串。"""
    return _channel.get()


# ---- 调用工具名标记（MCP 接入，批2 任务1）----
# MCP server 每个工具调用经 X-MedAssist-Tool 头携带工具名（如 "literature_query"），
# 中间件 set 后本请求审计事件 payload 自动带 tool 字段——审计可回溯「哪条外部 AI 工具触发」。
_tool: contextvars.ContextVar = contextvars.ContextVar("audit_tool", default="")


def set_tool(value: str):
    """标记当前请求的调用工具名；返回 token 供 reset_tool 恢复（中间件 finally 用）。"""
    return _tool.set((value or "").strip().lower()[:64])


def reset_tool(token) -> None:
    """恢复工具 contextvar（请求结束时调用，防上下文泄漏到连接复用场景）。"""
    _tool.reset(token)


def current_tool() -> str:
    """当前请求的调用工具名；未标记（普通 HTTP 前端请求）返回空串。"""
    return _tool.get()


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
        self._lock = threading.Lock()  # B2：追加+轮转临界区（轮转必须在锁内做，防并发重命名竞态）
        self._bootstrap()

    def _bootstrap(self) -> None:
        """启动时从 JSONL 尾部回填有界缓存（只读文件末尾 256KB，避免大文件全量载入 OOM）；
        并清理超保留期的历史轮转文件（B2：移入 archive/ 并 gzip）。"""
        try:
            self._archive_expired()
        except Exception:  # noqa: BLE001 —— 启动清理尽力而为
            pass
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

    # ---- B2：轮转归档 ----

    def _rotate_if_needed(self) -> None:
        """主文件超 _ROTATE_BYTES 或跨天（主文件 mtime 日期 != 今天）→ 重命名为
        audit-YYYYMMDD-HHMMSS.jsonl 并顺带清理过期历史；随后追加写自动重建主文件。
        仅在 _emit 的锁内调用；任何异常仅告警（轮转失败时主文件继续追加，不丢审计）。"""
        try:
            if not os.path.isfile(self.path):
                return
            need = os.path.getsize(self.path) >= _ROTATE_BYTES
            if not need:  # 跨天判定：主文件最后写入日期早于今天（本地日期）
                last_day = datetime.fromtimestamp(os.path.getmtime(self.path)).date()
                need = last_day != datetime.now().date()
            if not need:
                return
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            rotated = os.path.join(os.path.dirname(self.path), f"audit-{stamp}.jsonl")
            n = 1
            while os.path.exists(rotated):  # 同秒多次轮转防覆盖（文件名递增后缀，仍命中 audit-* 归档模式）
                rotated = os.path.join(os.path.dirname(self.path), f"audit-{stamp}-{n}.jsonl")
                n += 1
            os.replace(self.path, rotated)
            get_logger(__name__).info("audit.rotated", file=os.path.basename(rotated))
            self._archive_expired()  # 写入路径内顺带清理（>30 天历史 → archive/ + gzip）
        except Exception as e:  # noqa: BLE001
            get_logger(__name__).warning("audit.rotate_failed", error=str(e)[:120])

    def _archive_expired(self) -> None:
        """B2：审计目录中超 _ARCHIVE_DAYS 的历史轮转文件（audit-*.jsonl，不含主文件
        audit.jsonl）移入 <审计目录>/archive/ 并 gzip 压缩。幂等（原文件删除后不再命中）；
        逐文件容错，单个失败不影响其余归档。归档目录由 self.path 派生（测试隔离友好）。"""
        d = os.path.dirname(self.path)
        if not os.path.isdir(d):
            return
        archive_dir = os.path.join(d, "archive")
        cutoff = time.time() - _ARCHIVE_DAYS * 86400
        for name in os.listdir(d):
            if not (name.startswith("audit-") and name.endswith(".jsonl")):
                continue
            src = os.path.join(d, name)
            try:
                if not os.path.isfile(src) or os.path.getmtime(src) > cutoff:
                    continue
                os.makedirs(archive_dir, exist_ok=True)
                dst = os.path.join(archive_dir, name + ".gz")
                with open(src, "rb") as fin, gzip.open(dst, "wb") as fout:
                    shutil.copyfileobj(fin, fout)  # 压缩成功后再删原文件（防中途失败丢数据）
                os.remove(src)
                get_logger(__name__).info("audit.archived", file=name)
            except Exception as e:  # noqa: BLE001 —— 单文件归档失败不阻断其余文件
                get_logger(__name__).warning("audit.archive_failed",
                                             file=name, error=str(e)[:120])

    def _emit(self, event_type: str, actor: str, payload: dict) -> None:
        ch, tool = current_channel(), current_tool()
        if ch or tool:  # MCP 等外部入口标记：payload 带 channel/tool（不覆盖调用方显式传入值）
            payload = dict(payload or {})
            if ch:
                payload.setdefault("channel", ch)
            if tool:
                payload.setdefault("tool", tool)
        entry = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "event_type": event_type,
            "actor": actor,
            "payload": payload,
        }
        self.entries.append(entry)
        with self._lock:  # B2：轮转与追加在同一临界区内，防并发写轮转竞态
            self._rotate_if_needed()
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

    def recent(self, n: int = 8, offset: int = 0) -> list:
        """最近 n 条（新→旧），供概览活动流/审计流。

        offset 翻页（任务A）：0=最新一页，50=跳过最近 50 条取上一页；越界返回空列表。
        """
        items = list(self.entries)
        if offset < 0:
            offset = 0
        end = max(0, len(items) - offset)
        return items[max(0, end - n):end][::-1]


_log = AuditLog()


def get_audit_logger() -> AuditLog:
    return _log
