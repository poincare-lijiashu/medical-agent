"""G87 临床技能卡（Skill Card）：受控、可审核、可回滚的"技能"载体。

与 A2A ``Skill``（能力广告）的区别：
- A2A Skill 是**能力描述**（这个智能体能做什么），由代码目录派生，不可编辑；
- 临床技能卡是**院内可配置的执行口径**：绑定某个智能体，声明提示模板、可用工具、
  证据引用与护栏（是否强制复核/是否要求证据/输入上限），必须经**非创建人**审核后才生效。

为什么不做"可执行插件"：
- 任意代码/第三方插件在医疗内网意味着供应链与执行面失控；院内扩展应走受控适配器
  （``backend/integration``）或 MCP 网关，而不是让模型加载任意脚本；
- 技能卡只影响提示与工具白名单，不执行新代码，因此可审计、可回滚、可静态检查。

状态机：DRAFT →（提交）→ IN_REVIEW →（通过）→ PUBLISHED（version+1）
                        └──（驳回，必填原因）→ REJECTED →（修改后重新提交）→ IN_REVIEW
       PUBLISHED →（停用）→ DISABLED
规则：仅 DRAFT/REJECTED 可改；PUBLISHED 不可改（要改走停用 + 新建，保留历史口径）；
审核人不得是创建人（四眼）；所有写操作带 rowVersion CAS。
"""
from __future__ import annotations

import os
import re
import threading
from datetime import datetime, timezone

from backend.agents import catalog, guardrails, limits
from backend.core import agent_tools, pg_store
from backend.core.logger import get_logger

logger = get_logger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKILLS_FILE = os.path.join(BASE_DIR, "data", "agent_skills.json")

STATUSES = ("DRAFT", "IN_REVIEW", "PUBLISHED", "REJECTED", "DISABLED")
EDITABLE = ("DRAFT", "REJECTED")
MAX_TOOLS = 8
MAX_EVIDENCE = 10
MAX_PROMPT = 4000
MIN_PROMPT = 20
MAX_INPUT = limits.MAX_INPUT  # 评审 01-S03：与 guardrails / consult 同一口径

_CODE = re.compile(r"^[a-z][a-z0-9-]{2,63}$")

_lock = threading.RLock()


class SkillError(RuntimeError):
    code = "SKILL_ERROR"


class SkillNotFound(SkillError):
    code = "SKILL_NOT_FOUND"


class SkillInvalid(SkillError):
    code = "SKILL_INVALID"


class SkillConflict(SkillError):
    code = "SKILL_CONFLICT"


class SkillForbidden(SkillError):
    code = "SKILL_FORBIDDEN"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _load() -> list:
    try:
        return pg_store.load_skills(SKILLS_FILE, _load_json)
    except Exception as e:  # noqa: BLE001 —— 存储异常不拖垮主链路，但不静默
        # 评审 M-33：读失败必须留痕，避免"空库"与"读失败"无法区分
        logger.warning("agent_skills.load_failed", error=type(e).__name__)
        return []


def _load_json() -> list:
    import json
    if not os.path.isfile(SKILLS_FILE):
        return []
    try:
        with open(SKILLS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception as e:  # noqa: BLE001
        logger.warning("agent_skills.json_corrupt", error=type(e).__name__)
        return []


def _save(items: list) -> None:
    """整表覆盖写：仅保留给迁移/测试构造使用，**业务写路径不再调用**（评审 M-32）。"""
    pg_store.save_skills(items, SKILLS_FILE)


def _insert(item: dict) -> bool:
    """只在不存在时插入；False 表示跨实例已被同 code 抢占（评审 M-32）。"""
    return pg_store.insert_skill_item(item, SKILLS_FILE)


def _cas(item: dict, expected: int) -> None:
    """按 code + rowVersion 原子更新；版本已变则抛冲突（评审 M-32）。"""
    if not pg_store.cas_update_skill_item(item, expected, SKILLS_FILE):
        raise SkillConflict("技能卡已被他人修改，请刷新后重试")


def _find(items: list, code: str) -> dict | None:
    for item in items:
        if item.get("code") == code:
            return item
    return None


def _clean_list(values, limit: int, field: str) -> list[str]:
    if values is None:
        return []
    if not isinstance(values, list):
        raise SkillInvalid(f"{field} 必须是数组")
    out = []
    for v in values:
        text = str(v or "").strip()
        if not text:
            continue
        if len(text) > 200:
            raise SkillInvalid(f"{field} 单项不得超过 200 字")
        if text not in out:
            out.append(text)
    if len(out) > limit:
        raise SkillInvalid(f"{field} 最多 {limit} 项")
    return out


def _validate(payload: dict, current: dict | None = None) -> dict:
    """校验并归一化创建/修改载荷（agent 必须存在，工具必须是该 agent 声明能力的子集）。"""
    code = str(payload.get("code") or (current or {}).get("code") or "").strip()
    if not _CODE.match(code):
        raise SkillInvalid("技能编码需为 3-64 位小写字母/数字/短横线，且以字母开头")

    agent_id = str(payload.get("agentId") or (current or {}).get("agentId") or "").strip()
    agent = catalog.get_agent(agent_id)
    if agent is None:
        raise SkillInvalid(f"未找到智能体：{agent_id}")

    name = str(payload.get("name") or "").strip()
    if not (2 <= len(name) <= 80):
        raise SkillInvalid("技能名称需 2-80 字")

    description = str(payload.get("description") or "").strip()
    if not (10 <= len(description) <= 500):
        raise SkillInvalid("技能说明需 10-500 字")

    prompt = str(payload.get("promptTemplate") or "").strip()
    if not (MIN_PROMPT <= len(prompt) <= MAX_PROMPT):
        raise SkillInvalid(f"提示模板需 {MIN_PROMPT}-{MAX_PROMPT} 字")

    tools = _clean_list(payload.get("toolAllowlist"), MAX_TOOLS, "工具白名单")
    for tool in tools:
        if tool not in agent_tools.TOOLS:
            raise SkillInvalid(f"未注册的工具：{tool}")
        if tool not in agent.tools:
            raise SkillInvalid(f"智能体 {agent_id} 未声明工具 {tool}")

    evidence = _clean_list(payload.get("evidenceRefs"), MAX_EVIDENCE, "证据引用")
    if not evidence:
        raise SkillInvalid("至少填写 1 条证据引用（指南/共识/院内制度，便于审核溯源）")

    raw = payload.get("guardrails") or {}
    if not isinstance(raw, dict):
        raise SkillInvalid("guardrails 必须是对象")
    try:
        max_input = int(raw.get("maxInputChars", 2000))
    except (TypeError, ValueError):
        raise SkillInvalid("maxInputChars 必须是整数")
    if not (200 <= max_input <= MAX_INPUT):
        raise SkillInvalid(f"maxInputChars 需在 200-{MAX_INPUT} 之间")
    guardrails = {
        "hitl": bool(raw.get("hitl", True)),
        "requireEvidence": bool(raw.get("requireEvidence", True)),
        "maxInputChars": max_input,
    }
    # 不允许把需要复核的智能体通过技能卡"降级"为免复核
    if agent.hitl and not guardrails["hitl"]:
        raise SkillInvalid(f"智能体 {agent_id} 必须保留人工复核（hitl=true）")

    return {
        "code": code,
        "name": name,
        "description": description,
        "agentId": agent_id,
        "promptTemplate": prompt,
        "toolAllowlist": tools,
        "evidenceRefs": evidence,
        "guardrails": guardrails,
    }


def list_skills(role: str, status: str | None = None, agent_id: str | None = None) -> list[dict]:
    """列表：普通角色只看 PUBLISHED；admin/qc 可看全部并可按状态/智能体过滤。"""
    items = _load()
    privileged = role in ("admin", "qc")
    if not privileged:
        status = "PUBLISHED"
    out = []
    for item in items:
        if status and item.get("status") != status:
            continue
        if agent_id and item.get("agentId") != agent_id:
            continue
        out.append(item)
    out.sort(key=lambda i: (i.get("status") != "PUBLISHED", i.get("code")))
    return out


def get_skill(code: str, role: str) -> dict:
    item = _find(_load(), (code or "").strip())
    if item is None:
        raise SkillNotFound(f"未找到技能卡：{code}")
    if role not in ("admin", "qc") and item.get("status") != "PUBLISHED":
        raise SkillNotFound(f"未找到技能卡：{code}")
    return item


def create_skill(payload: dict, actor: str) -> dict:
    data = _validate(payload)
    with _lock:
        items = _load()
        if _find(items, data["code"]) is not None:
            raise SkillConflict(f"技能编码已存在：{data['code']}")
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
            raise SkillConflict(f"技能编码已存在：{data['code']}")
        return item


def update_skill(code: str, payload: dict, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") not in EDITABLE:
            raise SkillConflict(f"当前状态不可修改：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")
        data = _validate(payload, current=item)
        if data["code"] != item["code"]:
            raise SkillInvalid("技能编码不可修改")
        item.update(data)
        item.update({
            "updatedBy": actor,
            "updatedAt": _now(),
            "rowVersion": int(item["rowVersion"]) + 1,
        })
        _cas(item, row_version)
        return item


def submit_skill(code: str, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") not in EDITABLE:
            raise SkillConflict(f"当前状态不可提交审核：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")
        _validate(item)  # 存量字段再校验一次，防止绕过接口改库后把脏配置送审
        item.update({
            "status": "IN_REVIEW",
            "submittedBy": actor,
            "submittedAt": _now(),
            "updatedBy": actor,
            "updatedAt": _now(),
            "rowVersion": int(item["rowVersion"]) + 1,
        })
        _cas(item, row_version)
        return item


def review_skill(code: str, approve: bool, note: str | None, row_version: int,
                 actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") != "IN_REVIEW":
            raise SkillConflict(f"当前状态不可审核：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")
        if actor == item.get("createdBy"):
            raise SkillForbidden("不能审核自己创建的技能卡（四眼原则）")
        # 评审 M-02 整改：四眼应比"提交人"而非仅"创建人"——否则 A 创建、B 修改、B 提交、
        # B 审核可自审自批（submittedBy 此前只写不读）。两者任一相同即拒绝。
        if actor == item.get("submittedBy"):
            raise SkillForbidden("不能审核自己提交的技能卡（四眼原则）")
        text = (note or "").strip()
        if not approve and not text:
            raise SkillInvalid("驳回必须填写原因")
        now = _now()
        if approve:
            _validate(item)
            # 评审 M-35：发布准入必须校验同一能力域的**聚合**提示预算，
            # 否则发布后该能力域所有 /consult 都会因超预算 422。
            others = sum(
                len(i.get("promptTemplate") or "") for i in items
                if i.get("status") == "PUBLISHED"
                and i.get("agentId") == item.get("agentId")
                and i.get("code") != item.get("code"))
            total = others + len(item.get("promptTemplate") or "")
            if total > guardrails.MAX_PROMPT_BUDGET:
                raise SkillInvalid(
                    f"该能力域已发布技能提示合计 {total} 字，超过 "
                    f"{guardrails.MAX_PROMPT_BUDGET} 字上限；请收敛提示或先停用部分技能"
                    "（否则发布后该能力域问答会全部失败）")
            item.update({
                "status": "PUBLISHED",
                "version": int(item.get("version", 0)) + 1,
            })
        else:
            item["status"] = "REJECTED"
        item.update({
            "reviewedBy": actor,
            "reviewedAt": now,
            "reviewNote": text or None,
            "updatedBy": actor,
            "updatedAt": now,
            "rowVersion": int(item["rowVersion"]) + 1,
        })
        _cas(item, row_version)
        return item


def disable_skill(code: str, row_version: int, actor: str) -> dict:
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") != "PUBLISHED":
            raise SkillConflict(f"仅生效中的技能卡可停用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")
        item.update({
            "status": "DISABLED",
            "updatedBy": actor,
            "updatedAt": _now(),
            "rowVersion": int(item["rowVersion"]) + 1,
        })
        _cas(item, row_version)
        return item


def enable_skill(code: str, row_version: int, actor: str) -> dict:
    """重新启用已停用的技能卡（评审 M-36：DISABLED 不再是终态）。

    走回审核队列（DISABLED → IN_REVIEW），仍由另一人四眼通过后才生效，
    避免"停用后再启用"绕过审核。
    """
    with _lock:
        items = _load()
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") != "DISABLED":
            raise SkillConflict(f"仅已停用的技能卡可重新启用：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")
        item.update({
            "status": "IN_REVIEW",
            "submittedBy": actor,
            "submittedAt": _now(),
            "updatedBy": actor,
            "updatedAt": _now(),
            "rowVersion": int(item["rowVersion"]) + 1,
        })
        _cas(item, row_version)
        return item


def published_for_agent(agent_id: str) -> list[dict]:
    """运行时读取：某智能体当前生效的技能卡（供后续提示拼装/工具白名单收敛使用）。"""
    return [i for i in _load()
            if i.get("status") == "PUBLISHED" and i.get("agentId") == agent_id]
