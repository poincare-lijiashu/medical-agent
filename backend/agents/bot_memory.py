"""G88 Bot 长期记忆（受控写入 + 人工确认 + 可删除）。

为什么 Bot 需要"自己的记忆"：Bot 是持续存在的身份，不是一次性问答；科室偏好、
常用模板、审核口径等稳定事实应跨会话保留。但医疗场景不能"模型自动记住一切"：

- **默认候选**：新增记忆一律落 CANDIDATE，必须显式确认后才进入提示上下文；
- **最小必要**：value ≤500 字，且拒绝包含手机号/身份证/邮箱等可直接识别个人的标识，
  避免把 PHI 原文沉淀进长期记忆；
- **可追溯/可删除**：记录来源、创建人、确认人、时间；删除为软删除（保留审计语义）；
- **可过期**：支持 ``expiresAt``（YYYY-MM-DD），过期记忆不再注入提示；
- **按 Bot 隔离**：记忆属于某个 Bot，不跨 Bot 混用；团队协作只读取各自 Bot 的记忆。
"""
from __future__ import annotations

import json
import os
import re
import threading
import uuid
from datetime import date, datetime, timezone

from backend.agents import bots
from backend.core import pg_store
from backend.core.logger import get_logger

logger = get_logger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MEMORY_FILE = os.path.join(BASE_DIR, "data", "bot_memories.json")

MAX_ITEMS = 200
MAX_VALUE = 500
# 评审 M-11：软删除必须清空正文，避免 PHI 在存储中长期残留
REDACTED_VALUE = "[已删除]"
_KEY = re.compile(r"^[a-z][a-z0-9_.-]{1,63}$")

# 与出网脱敏同口径：这些标识不允许进入长期记忆
_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_ID_CARD = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")

_lock = threading.RLock()


class MemoryError_(RuntimeError):
    code = "BOT_MEMORY_ERROR"


class MemoryNotFound(MemoryError_):
    code = "BOT_MEMORY_NOT_FOUND"


class MemoryInvalid(MemoryError_):
    code = "BOT_MEMORY_INVALID"


class MemoryConflict(MemoryError_):
    code = "BOT_MEMORY_CONFLICT"


class MemoryForbidden(MemoryError_):
    code = "BOT_MEMORY_FORBIDDEN"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _load_json() -> list:
    if not os.path.isfile(MEMORY_FILE):
        return []
    try:
        with open(MEMORY_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception as e:  # noqa: BLE001
        logger.warning("bot_memory.json_corrupt", error=type(e).__name__)
        return []


def _load() -> list:
    try:
        return pg_store.load_json_list("agent_bot_memories", MEMORY_FILE, _load_json)
    except Exception as e:  # noqa: BLE001
        # 评审 M-33：读失败留痕，避免与"空库"混淆
        logger.warning("bot_memory.load_failed", error=type(e).__name__)
        return []


def _save(items: list) -> None:
    """整表覆盖写：仅保留给迁移/测试构造使用，**业务写路径不再调用**（评审 M-32）。"""
    pg_store.save_json_list("agent_bot_memories", MEMORY_FILE, items)


def _upsert(item: dict) -> None:
    """按 id 行级 upsert：多实例并发不删除他方新增（评审 M-32）。"""
    pg_store.upsert_json_item("agent_bot_memories", MEMORY_FILE, item)


def _find(items: list, mem_id: str) -> dict | None:
    for item in items:
        if item.get("id") == mem_id:
            return item
    return None


def _expired(item: dict) -> bool:
    raw = (item.get("expiresAt") or "").strip()
    if not raw:
        return False
    try:
        return date.fromisoformat(raw) < datetime.now().date()
    except ValueError:
        return False  # 非法日期在写入时已拒绝；存量脏值按"不过期"保守处理


def is_expired(item: dict, today: date | None = None) -> bool:
    """公开的过期判定（供睡眠整理复用；today 可注入便于测试）。"""
    raw = (item.get("expiresAt") or "").strip()
    if not raw:
        return False
    try:
        return date.fromisoformat(raw) < (today or datetime.now().date())
    except ValueError:
        return False


def all_memories() -> list[dict]:
    """全部未删除记忆（含候选），供治理/整理扫描；不对外暴露给普通角色。"""
    return [i for i in _load() if i.get("status") != "DELETED"]


def _validate(payload: dict) -> dict:
    bot_code = str(payload.get("botCode") or "").strip()
    try:
        bots.get_bot(bot_code, "admin")
    except bots.BotError as e:
        raise MemoryInvalid(str(e))

    key = str(payload.get("key") or "").strip().lower()
    if not _KEY.match(key):
        raise MemoryInvalid("记忆键需为 2-64 位小写字母/数字/._-，且以字母开头")

    value = str(payload.get("value") or "").strip()
    if not (2 <= len(value) <= MAX_VALUE):
        raise MemoryInvalid(f"记忆内容需 2-{MAX_VALUE} 字")
    if _PHONE.search(value) or _ID_CARD.search(value) or _EMAIL.search(value):
        raise MemoryInvalid("长期记忆不得包含手机号/身份证/邮箱等可识别个人信息")

    source = str(payload.get("source") or "").strip()
    if not (2 <= len(source) <= 200):
        raise MemoryInvalid("来源需 2-200 字（便于审核追溯）")

    expires = str(payload.get("expiresAt") or "").strip()
    if expires:
        try:
            date.fromisoformat(expires)
        except ValueError:
            raise MemoryInvalid("expiresAt 需为 YYYY-MM-DD")

    return {"botCode": bot_code, "key": key, "value": value,
            "source": source, "expiresAt": expires or None}


def list_memories(bot_code: str, role: str, include_deleted: bool = False) -> list[dict]:
    """列表：普通角色仅见 CONFIRMED；admin/qc 可见候选（便于审核）。"""
    items = [i for i in _load() if i.get("botCode") == bot_code]
    if role not in ("admin", "qc"):
        items = [i for i in items if i.get("status") == "CONFIRMED"]
    if not include_deleted:
        items = [i for i in items if i.get("status") != "DELETED"]
    items.sort(key=lambda i: i.get("createdAt") or "", reverse=True)
    return items


def create_memory(payload: dict, actor: str) -> dict:
    data = _validate(payload)
    with _lock:
        items = _load()
        if len([i for i in items if i.get("botCode") == data["botCode"]
                and i.get("status") != "DELETED"]) >= MAX_ITEMS:
            raise MemoryConflict("该 Bot 的记忆条目已达上限，请清理后再添加")
        for i in items:
            if (i.get("botCode") == data["botCode"] and i.get("key") == data["key"]
                    and i.get("status") != "DELETED"):
                raise MemoryConflict(f"记忆键已存在：{data['key']}")
        item = {
            "id": uuid.uuid4().hex,
            **data,
            "status": "CANDIDATE",
            "createdBy": actor,
            "createdAt": _now(),
            "confirmedBy": None,
            "confirmedAt": None,
        }
        items.append(item)
        _upsert(item)
        return item


def confirm_memory(mem_id: str, actor: str, role: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (mem_id or "").strip())
        if item is None or item.get("status") == "DELETED":
            raise MemoryNotFound(f"未找到记忆：{mem_id}")
        if item.get("status") != "CANDIDATE":
            raise MemoryConflict(f"当前状态不可确认：{item.get('status')}")
        # 评审 M-01 整改：记忆会注入 Bot 的 system prompt，属"持久化提示内容"，
        # 必须由质控/管理员确认，且确认人不得是录入人（四眼），避免自写自确认的注入通道。
        if role not in ("admin", "qc"):
            raise MemoryForbidden("仅质控/管理员可确认 Bot 长期记忆（四眼原则）")
        if item.get("createdBy") == actor:
            raise MemoryForbidden("不能确认自己录入的记忆（四眼原则）")
        item.update({"status": "CONFIRMED", "confirmedBy": actor,
                     "confirmedAt": _now()})
        _upsert(item)
        return item


def delete_memory(mem_id: str, actor: str, role: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (mem_id or "").strip())
        if item is None or item.get("status") == "DELETED":
            raise MemoryNotFound(f"未找到记忆：{mem_id}")
        if role not in ("admin", "qc") and item.get("createdBy") != actor:
            raise MemoryForbidden("只能删除自己创建的记忆")
        item.update({"status": "DELETED", "deletedBy": actor,
                     "deletedAt": _now(),
                     # 评审 M-11：正文即 PHI 载体，软删除同时清空
                     "value": REDACTED_VALUE, "valueRedacted": True})
        _upsert(item)
        return item


def purge_deleted_bodies() -> int:
    """清理历史软删除记录中的正文（评审 M-11）。

    改造前的删除只置 ``DELETED``，正文仍留在存储里；启动时调用一次，幂等。
    返回本次清理的条数（0 表示无需处理）。
    """
    with _lock:
        changed = 0
        for item in _load():
            if item.get("status") != "DELETED":
                continue
            if item.get("value") == REDACTED_VALUE:
                continue
            item["value"] = REDACTED_VALUE
            item["valueRedacted"] = True
            _upsert(item)
            changed += 1
        return changed


def context_memories(bot_code: str, limit: int = 20) -> list[dict]:
    """运行时注入：只取 CONFIRMED 且未过期的记忆，按时间倒序。"""
    items = [i for i in _load()
             if i.get("botCode") == bot_code and i.get("status") == "CONFIRMED"
             and not _expired(i)]
    items.sort(key=lambda i: i.get("confirmedAt") or i.get("createdAt") or "", reverse=True)
    return items[:max(1, min(limit, 50))]
