"""G88 Bot 实体（Bot as a first-class identity，而不是"模型别名"）。

2026 的 "Bot" 与"调用某个模型"不是一回事：Bot 有**自己的身份**（名字/职责/人格）、
**自己的模型绑定**（DeepSeek Bot 与 Grok Bot 可同时存在）、**自己的技能与工具**、
**自己的长期记忆**，还能被拉进团队协作。本模块只负责 Bot 的身份与发布治理：

- 模型绑定：``modelProvider`` 指向管理端配置的 chat provider（或 ``default``=当前激活），
  这样不同 Bot 可以跑在不同厂商上，而无需切换全局 active；
- 技能绑定：只能引用**已发布**且属于同一智能体的技能卡（G87），避免"技能张冠李戴"；
- 工具白名单：必须是该智能体已声明的工具子集；
- 发布治理：DRAFT → IN_REVIEW → PUBLISHED / REJECTED → DISABLED；四眼审核、CAS、
  发布后不可改（与技能卡同纪律）。

运行时（提示词拼装、团队协作、记忆注入）在 ``bot_runtime``；长期记忆在 ``bot_memory``。
"""
from __future__ import annotations

import os
import re
import threading
from datetime import datetime, timezone

from backend.agents import catalog, model_policy, skills
from backend.core import agent_tools, llm_config, pg_store
from backend.core.logger import get_logger

logger = get_logger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BOTS_FILE = os.path.join(BASE_DIR, "data", "agent_bots.json")

STATUSES = ("DRAFT", "IN_REVIEW", "PUBLISHED", "REJECTED", "DISABLED")
EDITABLE = ("DRAFT", "REJECTED")
MAX_SKILLS = 6
MAX_TOOLS = 8
MAX_PERSONA = 2000
MIN_PERSONA = 20
MEMORY_SCOPES = ("bot", "team")

_CODE = re.compile(r"^[a-z][a-z0-9-]{2,63}$")

_lock = threading.RLock()


class BotError(RuntimeError):
    code = "BOT_ERROR"


class BotNotFound(BotError):
    code = "BOT_NOT_FOUND"


class BotInvalid(BotError):
    code = "BOT_INVALID"


class BotConflict(BotError):
    code = "BOT_CONFLICT"


class BotForbidden(BotError):
    code = "BOT_FORBIDDEN"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _load_json() -> list:
    import json
    if not os.path.isfile(BOTS_FILE):
        return []
    try:
        with open(BOTS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception as e:  # noqa: BLE001
        logger.warning("agent_bots.json_corrupt", error=type(e).__name__)
        return []


def _load() -> list:
    try:
        return pg_store.load_json_list("agent_bots", BOTS_FILE, _load_json)
    except Exception as e:  # noqa: BLE001
        # 评审 M-33：读失败留痕，避免与"空库"混淆
        logger.warning("agent_bots.load_failed", error=type(e).__name__)
        return []


def _save(items: list) -> None:
    """整表覆盖写：仅保留给迁移/测试构造使用，**业务写路径不再调用**（评审 M-32）。"""
    pg_store.save_json_list("agent_bots", BOTS_FILE, items)


def _insert(item: dict) -> bool:
    """只在不存在时插入（评审 M-32：跨实例同 code 只能有一个成功）。"""
    return pg_store.insert_json_item("agent_bots", BOTS_FILE, item)


def _cas(item: dict, expected: int) -> None:
    """按 code + rowVersion 原子更新；版本已变抛冲突（评审 M-32）。"""
    if not pg_store.cas_update_json_item("agent_bots", BOTS_FILE, item, expected):
        raise BotConflict("Bot 已被他人修改，请刷新后重试")


def _find(items: list, code: str) -> dict | None:
    for item in items:
        if item.get("code") == code:
            return item
    return None


def _clean(values, limit: int, field: str) -> list[str]:
    if values is None:
        return []
    if not isinstance(values, list):
        raise BotInvalid(f"{field} 必须是数组")
    out = []
    for v in values:
        text = str(v or "").strip()
        if not text:
            continue
        if len(text) > 200:
            raise BotInvalid(f"{field} 单项不得超过 200 字")
        if text not in out:
            out.append(text)
    if len(out) > limit:
        raise BotInvalid(f"{field} 最多 {limit} 项")
    return out


def _validate(payload: dict, current: dict | None = None) -> dict:
    code = str(payload.get("code") or (current or {}).get("code") or "").strip()
    if not _CODE.match(code):
        raise BotInvalid("Bot 编码需为 3-64 位小写字母/数字/短横线，且以字母开头")

    agent_id = str(payload.get("agentId") or (current or {}).get("agentId") or "").strip()
    agent = catalog.get_agent(agent_id)
    if agent is None:
        raise BotInvalid(f"未找到智能体：{agent_id}")
    # 评审 01-S2：主路由智能体只做分派，不能作为 Bot 的作答能力域
    if agent.id == "router":
        raise BotInvalid("主路由智能体只做分派，不能绑定为 Bot 的能力域")

    name = str(payload.get("name") or "").strip()
    if not (2 <= len(name) <= 80):
        raise BotInvalid("Bot 名称需 2-80 字")

    persona = str(payload.get("persona") or "").strip()
    if not (MIN_PERSONA <= len(persona) <= MAX_PERSONA):
        raise BotInvalid(f"人格/职责说明需 {MIN_PERSONA}-{MAX_PERSONA} 字")

    provider = str(payload.get("modelProvider") or (current or {}).get("modelProvider")
                   or "default").strip()
    if provider != "default":
        found = llm_config.find_provider("chat", provider)
        if found is None:
            raise BotInvalid(f"未找到模型提供方：{provider}")
        if not model_policy.is_allowed(found):
            raise BotInvalid(
                f"模型提供方 {provider} 不允许用于临床辅助"
                f"（分类={model_policy.classify(found)}；仅允许本地部署或国产模型）")
    model_id = str(payload.get("modelId") or "").strip()
    if len(model_id) > 128:
        raise BotInvalid("模型 ID 不得超过 128 字")

    skill_codes = _clean(payload.get("skillCodes"), MAX_SKILLS, "技能卡")
    for sc in skill_codes:
        card = skills.get_skill(sc, "admin")
        if card.get("status") != "PUBLISHED":
            raise BotInvalid(f"技能卡未生效：{sc}")
        if card.get("agentId") != agent_id:
            raise BotInvalid(f"技能卡 {sc} 属于智能体 {card.get('agentId')}，与 Bot 不匹配")

    tools = _clean(payload.get("toolAllowlist"), MAX_TOOLS, "工具白名单")
    for tool in tools:
        if tool not in agent_tools.TOOLS:
            raise BotInvalid(f"未注册的工具：{tool}")
        if tool not in agent.tools:
            raise BotInvalid(f"智能体 {agent_id} 未声明工具 {tool}")

    scope = str(payload.get("memoryScope") or (current or {}).get("memoryScope") or "bot")
    if scope not in MEMORY_SCOPES:
        raise BotInvalid(f"记忆范围只支持 {MEMORY_SCOPES}")

    return {
        "code": code,
        "name": name,
        "agentId": agent_id,
        "persona": persona,
        "modelProvider": provider,
        "modelId": model_id or None,
        "skillCodes": skill_codes,
        "toolAllowlist": tools,
        "memoryScope": scope,
    }


def list_bots(role: str, status: str | None = None) -> list[dict]:
    """列表：普通角色只看 PUBLISHED 且**能力域角色允许**的 Bot（评审 03-S10）。

    与 `catalog_payload` 同一口径：能看到 = 能选进团队；只按发布状态过滤会让
    药师看到影像/会诊域的 Bot，为越权组合提供前置条件。
    """
    items = _load()
    privileged = role in ("admin", "qc")
    if not privileged:
        status = "PUBLISHED"
    out = []
    for item in items:
        if status and item.get("status") != status:
            continue
        if not privileged:
            spec = catalog.get_agent(item.get("agentId") or "")
            if spec is None or role not in spec.roles:
                continue
        out.append(item)
    out.sort(key=lambda i: (i.get("status") != "PUBLISHED", i.get("code")))
    return out


def get_bot(code: str, role: str) -> dict:
    item = _find(_load(), (code or "").strip())
    if item is None:
        raise BotNotFound(f"未找到 Bot：{code}")
    if role not in ("admin", "qc") and item.get("status") != "PUBLISHED":
        raise BotNotFound(f"未找到 Bot：{code}")
    return item


def create_bot(payload: dict, actor: str) -> dict:
    data = _validate(payload)
    with _lock:
        items = _load()
        if _find(items, data["code"]) is not None:
            raise BotConflict(f"Bot 编码已存在：{data['code']}")
        now = _now()
        item = {
            **data,
            "status": "DRAFT",
            "version": 0,
            "createdBy": actor,
            "createdAt": now,
            "updatedBy": actor,
            "updatedAt": now,
            "submittedBy": None,
            "submittedAt": None,
            "reviewedBy": None,
            "reviewedAt": None,
            "reviewNote": None,
            "rowVersion": 0,
        }
        if not _insert(item):
            raise BotConflict(f"Bot 编码已存在：{data['code']}")
        return item


def update_bot(code: str, payload: dict, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise BotNotFound(f"未找到 Bot：{code}")
        if item.get("status") not in EDITABLE:
            raise BotConflict(f"当前状态不可修改：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise BotConflict("Bot 已被他人修改，请刷新后重试")
        data = _validate(payload, current=item)
        if data["code"] != item["code"]:
            raise BotInvalid("Bot 编码不可修改")
        item.update(data)
        item.update({"updatedBy": actor, "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def submit_bot(code: str, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise BotNotFound(f"未找到 Bot：{code}")
        if item.get("status") not in EDITABLE:
            raise BotConflict(f"当前状态不可提交审核：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise BotConflict("Bot 已被他人修改，请刷新后重试")
        _validate(item)
        item.update({"status": "IN_REVIEW", "submittedBy": actor, "submittedAt": _now(),
                     "updatedBy": actor, "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def review_bot(code: str, approve: bool, note: str | None, row_version: int,
               actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise BotNotFound(f"未找到 Bot：{code}")
        if item.get("status") != "IN_REVIEW":
            raise BotConflict(f"当前状态不可审核：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise BotConflict("Bot 已被他人修改，请刷新后重试")
        if actor == item.get("createdBy"):
            raise BotForbidden("不能审核自己创建的 Bot（四眼原则）")
        # 评审 M-02 整改：同时比提交人，防"他人创建→自己修改并提交→自审自批"。
        if actor == item.get("submittedBy"):
            raise BotForbidden("不能审核自己提交的 Bot（四眼原则）")
        text = (note or "").strip()
        if not approve and not text:
            raise BotInvalid("驳回必须填写原因")
        now = _now()
        if approve:
            _validate(item)
            # 评审 01-S17：绑 default provider 的 Bot 在创建期跳过了合规检查；
            # 发布是"承诺可运行"的时刻，这里对当前默认 provider 再校验一次。
            # 未配置任何 provider 时不拦（运行时 fail-closed 会给 503），避免离线环境无法发布。
            if item.get("modelProvider") == "default":
                current_provider = llm_config.active_provider("chat")
                if current_provider and not model_policy.is_allowed(current_provider):
                    raise BotInvalid(
                        "当前默认模型不允许用于临床辅助（仅本地/国产），无法发布该 Bot；"
                        "请先改用合规 provider 或为 Bot 绑定具体 provider")
            item.update({"status": "PUBLISHED",
                         "version": int(item.get("version", 0)) + 1})
        else:
            item["status"] = "REJECTED"
        item.update({"reviewedBy": actor, "reviewedAt": now,
                     "reviewNote": text or None, "updatedBy": actor,
                     "updatedAt": now, "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def disable_bot(code: str, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise BotNotFound(f"未找到 Bot：{code}")
        if item.get("status") != "PUBLISHED":
            raise BotConflict(f"仅生效中的 Bot 可停用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise BotConflict("Bot 已被他人修改，请刷新后重试")
        item.update({"status": "DISABLED", "updatedBy": actor, "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def enable_bot(code: str, row_version: int, actor: str) -> dict:
    """重新启用已停用的 Bot（评审 M-36：DISABLED 不再是终态）。

    Bot 生效同样影响问答口径，因此回审核队列（IN_REVIEW），由另一人四眼通过。
    """
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise BotNotFound(f"未找到 Bot：{code}")
        if item.get("status") != "DISABLED":
            raise BotConflict(f"仅已停用的 Bot 可重新启用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise BotConflict("Bot 已被他人修改，请刷新后重试")
        item.update({"status": "IN_REVIEW", "submittedBy": actor,
                     "submittedAt": _now(), "updatedBy": actor,
                     "updatedAt": _now(),
                     "rowVersion": int(item["rowVersion"]) + 1})
        _cas(item, row_version)
        return item


def published_bots() -> list[dict]:
    return [i for i in _load() if i.get("status") == "PUBLISHED"]


def bot_skill_cards(bot: dict) -> list[dict]:
    """运行时取 Bot 绑定的、当前仍生效的技能卡（发布后技能被停用则自动失效）。

    评审 M-44：此前逐个 ``get_skill`` 会触发 N 次整表加载；现改为**一次**取
    已发布集合再按绑定顺序挑选（N 次文件/PG 读 → 1 次）。
    评审 01-S1：装配顺序统一按 ``code`` 排序，与 ``/consult`` 的 ``select_skills``
    一致——提示词顺序影响输出，两链路顺序不同会让同一组技能"不可复现"。
    """
    codes = [c for c in (bot.get("skillCodes") or []) if c]
    if not codes:
        return []
    published = {s.get("code"): s for s in skills.list_skills("admin", "PUBLISHED")}
    return [published[c] for c in sorted(codes) if c in published]
