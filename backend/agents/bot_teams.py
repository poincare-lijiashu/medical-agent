"""G88 Bot 团队：把多个已发布 Bot 组织成一个可协作的小组。

对应 2026 "DeepSeek Bot / Grok Bot" 的产品形态：主管 Bot 建团队、拉不同模型/职责的
Bot 进群，各 Bot 保留自己的身份与记忆，需要时在同一问题上并行给出意见。

本模块只做**团队编制**（谁在组里、是否启用），不做执行；执行在 ``bot_runtime``：
- 成员必须是已发布 Bot，避免把草稿/停用 Bot 拉进协作；
- 2–4 个成员：少于 2 个没有协作意义，多于 4 个成本与噪音不可控；
- 团队本身不做四眼审核（它是编排配置，不改变任何 Bot 的能力与权限），但写操作记审计。
"""
from __future__ import annotations

import json
import os
import re
import threading
from datetime import datetime, timezone

from backend.agents import bots
from backend.core import pg_store
from backend.core.logger import get_logger

logger = get_logger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEAMS_FILE = os.path.join(BASE_DIR, "data", "bot_teams.json")

STATUSES = ("ACTIVE", "DISABLED")
MIN_BOTS = 2
MAX_BOTS = 4
_CODE = re.compile(r"^[a-z][a-z0-9-]{2,63}$")

_lock = threading.RLock()


class TeamError(RuntimeError):
    code = "BOT_TEAM_ERROR"


class TeamNotFound(TeamError):
    code = "BOT_TEAM_NOT_FOUND"


class TeamInvalid(TeamError):
    code = "BOT_TEAM_INVALID"


class TeamConflict(TeamError):
    code = "BOT_TEAM_CONFLICT"


class TeamForbidden(TeamError):
    """四眼不满足（评审 M-37）。"""

    code = "BOT_TEAM_FORBIDDEN"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _load_json() -> list:
    if not os.path.isfile(TEAMS_FILE):
        return []
    try:
        with open(TEAMS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception as e:  # noqa: BLE001
        logger.warning("bot_teams.json_corrupt", error=type(e).__name__)
        return []


def _load() -> list:
    try:
        return pg_store.load_json_list("agent_bot_teams", TEAMS_FILE, _load_json)
    except Exception as e:  # noqa: BLE001
        # 评审 M-33：读失败留痕，避免与"空库"混淆
        logger.warning("bot_teams.load_failed", error=type(e).__name__)
        return []


def _save(items: list) -> None:
    """整表覆盖写：仅保留给迁移/测试构造使用，**业务写路径不再调用**（评审 M-32）。"""
    pg_store.save_json_list("agent_bot_teams", TEAMS_FILE, items)


def _insert(item: dict) -> bool:
    """只在不存在时插入（评审 M-32：跨实例同 code 只能有一个成功）。"""
    return pg_store.insert_json_item("agent_bot_teams", TEAMS_FILE, item)


def _cas(item: dict, expected: int) -> None:
    """按 code + rowVersion 原子更新；版本已变抛冲突（评审 M-32）。"""
    if not pg_store.cas_update_json_item("agent_bot_teams", TEAMS_FILE, item, expected):
        raise TeamConflict("团队已被他人修改，请刷新后重试")


def _find(items: list, code: str) -> dict | None:
    for item in items:
        if item.get("code") == code:
            return item
    return None


def _validate(payload: dict, current: dict | None = None) -> dict:
    code = str(payload.get("code") or (current or {}).get("code") or "").strip()
    if not _CODE.match(code):
        raise TeamInvalid("团队编码需为 3-64 位小写字母/数字/短横线，且以字母开头")
    name = str(payload.get("name") or "").strip()
    if not (2 <= len(name) <= 80):
        raise TeamInvalid("团队名称需 2-80 字")
    description = str(payload.get("description") or "").strip()
    if len(description) > 300:
        raise TeamInvalid("团队说明不得超过 300 字")
    raw = payload.get("botCodes")
    if not isinstance(raw, list):
        raise TeamInvalid("botCodes 必须是数组")
    codes = []
    for c in raw:
        text = str(c or "").strip()
        if text and text not in codes:
            codes.append(text)
    if not (MIN_BOTS <= len(codes) <= MAX_BOTS):
        raise TeamInvalid(f"团队需 {MIN_BOTS}-{MAX_BOTS} 个 Bot")
    for bc in codes:
        bot = bots.get_bot(bc, "admin")
        if bot.get("status") != "PUBLISHED":
            raise TeamInvalid(f"Bot 未生效：{bc}")
    return {"code": code, "name": name, "description": description, "botCodes": codes}


def list_teams(role: str, status: str | None = None) -> list[dict]:
    items = _load()
    if role not in ("admin", "qc"):
        status = "ACTIVE"
    out = [i for i in items if not status or i.get("status") == status]
    out.sort(key=lambda i: i.get("code") or "")
    return out


def get_team(code: str, role: str) -> dict:
    item = _find(_load(), (code or "").strip())
    if item is None:
        raise TeamNotFound(f"未找到团队：{code}")
    if role not in ("admin", "qc") and item.get("status") != "ACTIVE":
        raise TeamNotFound(f"未找到团队：{code}")
    return item


def create_team(payload: dict, actor: str) -> dict:
    data = _validate(payload)
    with _lock:
        items = _load()
        if _find(items, data["code"]) is not None:
            raise TeamConflict(f"团队编码已存在：{data['code']}")
        now = _now()
        item = {**data, "status": "ACTIVE", "rowVersion": 0,
                "createdBy": actor, "createdAt": now, "updatedBy": actor, "updatedAt": now}
        if not _insert(item):
            raise TeamConflict(f"团队编码已存在：{data['code']}")
        return item


def update_team(code: str, payload: dict, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise TeamNotFound(f"未找到团队：{code}")
        if item.get("status") != "ACTIVE":
            raise TeamConflict(f"当前状态不可修改：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise TeamConflict("团队已被他人修改，请刷新后重试")
        data = _validate(payload, current=item)
        if data["code"] != item["code"]:
            raise TeamInvalid("团队编码不可修改")
        # 评审 M-37：生效中团队的编制变更不能由 admin 单人直接改；
        # 先落为待审核变更，成员列表在通过前保持原样（不影响正在进行的协作）。
        item["pendingChanges"] = data
        item.update({"submittedBy": actor, "submittedAt": _now(),
                     "updatedBy": actor, "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def review_team(code: str, approve: bool, note: str | None, row_version: int,
                actor: str) -> dict:
    """审核团队的待变更编制（评审 M-37）：提交人不得自审。"""
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise TeamNotFound(f"未找到团队：{code}")
        pending = item.get("pendingChanges")
        if not pending:
            raise TeamConflict("该团队没有待审核的编制变更")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise TeamConflict("团队已被他人修改，请刷新后重试")
        if actor == item.get("submittedBy"):
            raise TeamForbidden("不能审核自己提交的团队变更（四眼原则）")
        text = (note or "").strip()
        if not approve and not text:
            raise TeamInvalid("驳回必须填写原因")
        now = _now()
        if approve:
            # 审批时再校验一次：提交后 Bot 可能已被停用
            _validate(pending, current=item)
            item.update(pending)
            item.pop("pendingChanges", None)
        else:
            item.pop("pendingChanges", None)
        item.update({"status": "ACTIVE",
                     "reviewedBy": actor, "reviewedAt": now,
                     "reviewNote": text or None,
                     "updatedBy": actor, "updatedAt": now,
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def disable_team(code: str, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise TeamNotFound(f"未找到团队：{code}")
        if item.get("status") != "ACTIVE":
            raise TeamConflict(f"仅启用中的团队可停用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise TeamConflict("团队已被他人修改，请刷新后重试")
        item.update({"status": "DISABLED", "updatedBy": actor, "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def enable_team(code: str, row_version: int, actor: str) -> dict:
    """重新启用已停用的团队（评审 M-36：DISABLED 不再是终态）。"""
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise TeamNotFound(f"未找到团队：{code}")
        if item.get("status") != "DISABLED":
            raise TeamConflict(f"仅已停用的团队可重新启用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise TeamConflict("团队已被他人修改，请刷新后重试")
        item.update({"status": "ACTIVE", "updatedBy": actor,
                     "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def team_bots(team: dict) -> list[dict]:
    """运行时展开团队成员（跳过已停用/被下线的 Bot）。"""
    out = []
    for code in team.get("botCodes") or []:
        try:
            bot = bots.get_bot(code, "admin")
        except bots.BotError:
            continue
        if bot.get("status") == "PUBLISHED":
            out.append(bot)
    return out
