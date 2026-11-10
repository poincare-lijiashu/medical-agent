"""PHI 脱敏 + 追加式审计日志（M1 共享核心，权威版）。

设计：
- PHIRedactor：正则替换身份证/手机/邮箱为占位符（确定性、无状态）。
- AuditLog：追加式 JSONL 落盘，无 delete/update 方法（满足审计不可篡改）。
  write/append/record 三种调用形状均兼容（内部归一化），杜绝跨模块签名漂移。
- B2 轮转归档：主文件超 5MB 或跨天 → 重命名 audit-YYYYMMDD-HHMMSS.jsonl 并重建主文件；
  超 30 天的历史轮转文件移入 data/audit/archive/ 并 gzip 压缩（启动/轮转时清理）。
  轮转与追加在同一把锁内完成，任何轮转/归档异常仅告警，绝不阻断审计写入。
- **第 184 批：哈希链（防篡改）**——每条记录带 `seq` / `prev` / `hash`：
  `hash = sha256(规范化(seq, ts, event_type, actor, payload, prev))`，
  轮转跨文件仍以 `prev` 首尾相接（头 hash 从最新文件末条恢复）。
  `verify_chain()` 顺序重算，能定位第一条被改/被删的记录；无 hash 的历史记录计入
  `legacy` 并从该处重新起链（升级平滑，不需要回溯改写旧日志）。
"""
from __future__ import annotations

import contextvars
import gzip
import hashlib
import json
import os
import re
import shutil
import threading
import time
from collections import deque
from datetime import datetime, timezone

from backend.core import phi
from backend.core.logger import get_logger

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AUDIT_DIR = os.path.join(BASE_DIR, "data", "audit")
AUDIT_FILE = os.path.join(AUDIT_DIR, "audit.jsonl")
ARCHIVE_DIR = os.path.join(AUDIT_DIR, "archive")  # B2：gzip 归档目录

# B2 轮转/归档参数（模块级常量，测试可 monkeypatch）：主文件 5MB 或跨天轮转；历史保留 30 天
_ROTATE_BYTES = 5 * 1024 * 1024
_ARCHIVE_DAYS = 30

# ---- 第 184 批：审计哈希链 ----
# 参与哈希的字段固定且排序（payload 是任意 JSON，必须 sort_keys 才能稳定重算）。
_HASH_FIELDS = ("seq", "ts", "event_type", "actor", "payload", "prev")


def _canonical_entry(entry: dict) -> str:
    body = {k: entry.get(k) for k in _HASH_FIELDS}
    return json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _entry_hash(entry: dict) -> str:
    return hashlib.sha256(_canonical_entry(entry).encode("utf-8")).hexdigest()


# 轮转文件名：audit-YYYYmmdd-HHMMSS[-n].jsonl（同秒多次轮转带递增后缀）
_AUDIT_NAME_RE = re.compile(r"^audit-(\d{8})-(\d{6})(?:-(\d+))?\.jsonl$")


def _rotation_sort_key(path: str) -> tuple[str, str, int]:
    """按文件名里的时间戳排序（**不用 mtime**：同秒轮转的 mtime 可能完全相同，
    依赖文件系统时间分辨率会导致链顺序不确定、校验误报）。"""
    m = _AUDIT_NAME_RE.match(os.path.basename(path))
    return (m.group(1), m.group(2), int(m.group(3) or 0)) if m else ("", "", 0)


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
        # 评审 01-S14：模式与替换顺序统一收敛到 backend.core.phi（单一实现，防漂移）
        return phi.redact(text)


class AuditLog:
    """追加式审计。禁止删除/更新。"""

    def __init__(self, path: str = AUDIT_FILE):
        self.path = path
        self.entries = deque(maxlen=500)  # 有界缓存；权威记录落 JSONL
        self._lock = threading.Lock()  # B2：追加+轮转临界区（轮转必须在锁内做，防并发重命名竞态）
        # 第 184 批：哈希链状态（seq 连续、prev 指向上一条 hash）；重启后从文件末条恢复
        self._seq = 0
        self._head = ""
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
            self._restore_chain_head(tail)
        except Exception:  # noqa: BLE001
            pass

    # ---- 第 184 批：哈希链维护与校验 ----

    def _restore_chain_head(self, tail_lines: list[str]) -> None:
        """重启后从最新审计文件末条恢复 (seq, head)，保证 prev 接得上、不重新起链。

        主文件可能刚被轮转成空（进程在轮转后、首条写入前重启），此时回退读最新的
        轮转文件尾部。恢复失败不阻断启动：链会重新起链，verify 会如实报告 legacy 段。
        """
        def _pick(lines) -> bool:
            for line in reversed(list(lines)):
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except Exception:  # noqa: BLE001
                    continue
                if isinstance(rec, dict) and isinstance(rec.get("seq"), int) and rec.get("hash"):
                    self._seq, self._head = rec["seq"], rec["hash"]
                    return True
            return False

        if _pick(tail_lines):
            return
        try:
            others = [p for p in self._chain_files()
                      if os.path.abspath(p) != os.path.abspath(self.path)]
            if not others:
                return
            newest = max(others, key=_rotation_sort_key)
            with open(newest, encoding="utf-8", errors="replace") as f:
                _pick(f.readlines()[-500:])
        except Exception:  # noqa: BLE001
            pass

    def _chain_files(self) -> list[str]:
        """按写入时间排序的链文件：轮转文件（旧→新）+ 非空主文件（最新）。"""
        d = os.path.dirname(self.path)
        if not os.path.isdir(d):
            return []
        rotated = [os.path.join(d, n) for n in os.listdir(d)
                   if n.startswith("audit-") and n.endswith(".jsonl")]
        rotated.sort(key=_rotation_sort_key)
        files = list(rotated)
        try:
            if os.path.isfile(self.path) and os.path.getsize(self.path) > 0:
                files.append(self.path)
        except OSError:
            pass
        return files

    def _seal(self, entry: dict) -> None:
        """在锁内补齐 seq/prev/hash（顺序与内容共同参与哈希）。"""
        self._seq += 1
        entry["seq"] = self._seq
        entry["prev"] = self._head
        entry["hash"] = _entry_hash(entry)
        self._head = entry["hash"]

    def verify_chain(self, max_records: int | None = None) -> dict:
        """顺序重算哈希链，定位第一条被篡改/删除的记录（只读，不修改任何文件）。

        返回：
        - `ok`：整条链可验证通过；
        - `checked`：参与校验的带 hash 记录数；
        - `legacy`：升级前的无 hash 历史记录数（这些段落无法回溯验证，但从该处重新起链）；
        - `firstBroken`：第一条异常 {file, line, seq, reason}（prev_mismatch / hash_mismatch / bad_json）；
        - `truncated`：`max_records` 限制是否提前结束（大文件巡检可分页核查）。
        """
        files = self._chain_files()
        prev_hash = ""
        checked = legacy = 0
        truncated = False
        for path in files:
            try:
                fh = open(path, encoding="utf-8", errors="replace")
            except OSError as e:
                return {"ok": False, "checked": checked, "legacy": legacy, "files": len(files),
                        "truncated": truncated,
                        "firstBroken": {"file": os.path.basename(path), "line": 0,
                                        "seq": None, "reason": f"open_failed: {str(e)[:80]}"}}
            with fh:
                for lineno, line in enumerate(fh, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except Exception:  # noqa: BLE001 —— 半行/坏行即断链
                        return {"ok": False, "checked": checked, "legacy": legacy,
                                "files": len(files), "truncated": truncated,
                                "firstBroken": {"file": os.path.basename(path), "line": lineno,
                                                "seq": None, "reason": "bad_json"}}
                    if not isinstance(rec, dict):
                        continue
                    if not rec.get("hash"):
                        # 升级前的历史记录：无法验证，视为断代点，后续从空 prev 重新起链
                        legacy += 1
                        prev_hash = ""
                        continue
                    if rec.get("prev", "") != prev_hash:
                        return {"ok": False, "checked": checked, "legacy": legacy,
                                "files": len(files), "truncated": truncated,
                                "firstBroken": {"file": os.path.basename(path), "line": lineno,
                                                "seq": rec.get("seq"), "reason": "prev_mismatch"}}
                    if _entry_hash(rec) != rec["hash"]:
                        return {"ok": False, "checked": checked, "legacy": legacy,
                                "files": len(files), "truncated": truncated,
                                "firstBroken": {"file": os.path.basename(path), "line": lineno,
                                                "seq": rec.get("seq"), "reason": "hash_mismatch"}}
                    prev_hash = rec["hash"]
                    checked += 1
                    if max_records is not None and checked >= max_records:
                        truncated = True
                        return {"ok": True, "checked": checked, "legacy": legacy,
                                "files": len(files), "truncated": truncated, "firstBroken": None}
        return {"ok": True, "checked": checked, "legacy": legacy,
                "files": len(files), "truncated": truncated, "firstBroken": None}

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
        with self._lock:  # B2：轮转与追加在同一临界区内，防并发写轮转竞态
            self._rotate_if_needed()
            self._seal(entry)  # 第 184 批：seq/prev/hash 必须在锁内生成（保证链顺序=落盘顺序）
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        self.entries.append(entry)
        # 评测 02 P0-3（A3）：PG 审计镜像的**唯一挂钩点**。JSONL 是权威记录，PG 同步
        # best-effort；没有池/离线时 mirror_audit_sync 直接 no-op，失败只告警。
        # 这样新增审计点无需各自记得 mirror_audit，PG/JSONL 覆盖率天然 1:1。
        try:
            from backend.core import pg_store
            pg_store.mirror_audit_sync(event_type, actor, payload)
        except Exception:  # noqa: BLE001 —— 镜像失败绝不影响审计主路径
            pass

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
