"""G95 运营建议（只建议，不执行）。

把 G94 的告警翻译成**可追溯的待办建议**：
- 每条建议有明确的处理对象（候选 / 技能卡 / 经验库）、建议动作与严重度；
- 不自动采纳、不自动停用、不自动补经验；处理仍由人在对应页面完成；
- 去重：同一 ``code + targetId`` 只保留一条（含已忽略/已完成），避免反复打扰；
- 生成与处理都写审计，建议本身可忽略/标记完成并留原因。
"""
from __future__ import annotations

import json
import os
import threading
import uuid
from datetime import datetime, timezone

from backend.agents import skill_library, skill_ops, skill_trends
from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ADVICE_FILE = os.path.join(BASE_DIR, "data", "skill_advices.json")

MAX_PER_RUN = 20
_lock = threading.RLock()


class AdviceError(RuntimeError):
    code = "SKILL_ADVICE_ERROR"


class AdviceNotFound(AdviceError):
    code = "SKILL_ADVICE_NOT_FOUND"


class AdviceInvalid(AdviceError):
    code = "SKILL_ADVICE_INVALID"


class AdviceConflict(AdviceError):
    code = "SKILL_ADVICE_CONFLICT"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _load_json() -> list:
    if not os.path.isfile(ADVICE_FILE):
        return []
    try:
        with open(ADVICE_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:  # noqa: BLE001
        return []


def _load() -> list:
    try:
        return pg_store.load_json_list("agent_skill_advices", ADVICE_FILE, _load_json)
    except Exception:  # noqa: BLE001
        return []


def _save(items: list) -> None:
    pg_store.save_json_list("agent_skill_advices", ADVICE_FILE, items)


def _find(items: list, advice_id: str) -> dict | None:
    for item in items:
        if item.get("id") == advice_id:
            return item
    return None


def _advice(code: str, severity: str, title: str, detail: str, action: str,
            target_type: str, target_id: str | None) -> dict:
    return {
        "code": code,
        "severity": severity,
        "title": title,
        "detail": detail,
        "suggestedAction": action,
        "targetType": target_type,
        "targetId": target_id,
        "dedupeKey": f"{code}:{target_id or '-'}",
    }


def generate(actor: str) -> list[dict]:
    """由告警与当前数据生成建议；不修改任何技能/Bot/候选。"""
    alerts = skill_trends.alerts().get("alerts") or []
    proposals = skill_library.list_proposals("admin")
    metrics = skill_ops.metrics()
    pending = [p for p in proposals if p.get("status") == "PROPOSED"]
    unused = (metrics.get("skillCards") or {}).get("unusedPublished") or []

    drafts: list[dict] = []
    for alert in alerts:
        code = alert.get("code")
        if code in ("ADOPTION_RATE_LOW", "PROPOSAL_BACKLOG"):
            for p in pending[:5]:
                drafts.append(_advice(
                    "PROPOSAL_REVIEW", "medium",
                    f"候选技能待处理：{p.get('name')}",
                    f"候选 {p.get('code')} 处于 PROPOSED，采纳率/积压告警已触发。",
                    "打开技能库，判断是否采纳（会先跑回放）或拒绝并填写原因",
                    "PROPOSAL", p.get("id")))
        elif code == "UNUSED_PUBLISHED_SKILLS":
            for skill_code in unused[:5]:
                drafts.append(_advice(
                    "UNUSED_SKILL", "medium",
                    f"生效技能未被引用：{skill_code}",
                    "该技能卡已发布但没有 Bot 引用，长期闲置会造成口径漂移。",
                    "评估绑定到合适 Bot，或停用该技能卡（先看影响面）",
                    "SKILL", skill_code))
        elif code in ("REPLAY_PASS_RATE_LOW", "REPLAY_PASS_RATE_DROP"):
            drafts.append(_advice(
                "REPLAY_REVIEW", "high",
                "回放通过率异常，请复核候选",
                alert.get("message") or "回放通过率低于阈值或较上次下降。",
                "逐条查看候选的问题模式与证据，必要时拒绝；不要为通过率放宽回放",
                "LIBRARY", None))
        elif code == "EXPERIENCE_COVERAGE_LOW":
            drafts.append(_advice(
                "EXPERIENCE_GAP", "low",
                "经验覆盖率偏低",
                alert.get("message") or "较多经验尚未进入候选。",
                "补充同能力域、同问题模式的已审核脱敏经验后再提炼",
                "LIBRARY", None))

    with _lock:
        items = _load()
        existing = {i.get("dedupeKey") for i in items}
        created = []
        for d in drafts:
            if d["dedupeKey"] in existing:
                continue
            item = {
                "id": uuid.uuid4().hex,
                **d,
                "status": "OPEN",
                "createdBy": actor,
                "createdAt": _now(),
                "decidedBy": None,
                "decidedAt": None,
                "note": None,
            }
            items.append(item)
            existing.add(d["dedupeKey"])
            created.append(item)
            if len(created) >= MAX_PER_RUN:
                break
        if created:
            _save(items)
    return list_advices("admin")


def list_advices(role: str, status: str | None = None) -> list[dict]:
    if role not in ("admin", "qc"):
        raise AdviceConflict("仅管理员/质控可查看运营建议")
    items = _load()
    if status:
        items = [i for i in items if i.get("status") == status]
    items.sort(key=lambda i: ({"high": 0, "medium": 1, "low": 2}.get(i.get("severity"), 9),
                              i.get("createdAt") or ""))
    return items


def dismiss(advice_id: str, note: str, actor: str) -> dict:
    return _decide(advice_id, "DISMISSED", note, actor, require_note=True)


def complete(advice_id: str, note: str | None, actor: str) -> dict:
    return _decide(advice_id, "DONE", note, actor, require_note=False)


def _decide(advice_id: str, status: str, note: str | None, actor: str,
            require_note: bool) -> dict:
    text = (note or "").strip()
    if require_note and not text:
        raise AdviceInvalid("忽略建议必须填写原因")
    with _lock:
        items = _load()
        item = _find(items, (advice_id or "").strip())
        if item is None:
            raise AdviceNotFound(f"未找到运营建议：{advice_id}")
        if item.get("status") != "OPEN":
            raise AdviceConflict(f"当前建议状态不可处理：{item.get('status')}")
        item.update({"status": status, "decidedBy": actor,
                     "decidedAt": _now(), "note": text or None})
        _save(items)
        return item
