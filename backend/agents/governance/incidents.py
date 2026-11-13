"""§296 误报与不良事件闭环（计划书 W8.4）。

为什么不能只记一条日志：
- 误报/漏报/有害输出必须能追到"哪条 AI 输出、哪个能力域、哪个模型版本"；
- 处理过程必须可分派、可定责、可复盘（根因 + 纠正措施），关闭需第二人复核；
- 逾期未闭环要能统计出来，否则"已记录"等于"没人管"。

状态机：``OPEN → INVESTIGATING → RESOLVED → CLOSED``；
``RESOLVED/CLOSED → OPEN``（重开必须写原因，并顺延 7 天截止）。
事件流追加式保存在 ``events`` 中（本模块 JSON 列表整体保存，事件只追加不改写）。
"""
from __future__ import annotations

from backend.agents import catalog
from backend.agents.governance import common, versions
from backend.agents.governance.common import (
    GovernanceForbidden,
    GovernanceInvalid,
    GovernanceNotFound,
)

KINDS = ("FALSE_POSITIVE", "FALSE_NEGATIVE", "HARMFUL", "OTHER")
SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
SOURCES = ("CLINICIAN", "PATIENT", "QC", "MONITOR", "OTHER")
STATUSES = ("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED")
DUE_DAYS = {"CRITICAL": 3, "HIGH": 7, "MEDIUM": 14, "LOW": 30}
MAX_ITEMS = 5000


def _items() -> list[dict]:
    return common.load("incidents")


def _find(items: list[dict], incident_no: str) -> dict:
    for item in items:
        if item.get("incidentNo") == incident_no:
            return item
    raise GovernanceNotFound("未找到该 AI 事件")


def _event(item: dict, action: str, from_status: str | None, to_status: str,
           actor: str, note: str | None = None) -> None:
    item.setdefault("events", []).append({
        "action": action,
        "fromStatus": from_status,
        "toStatus": to_status,
        "actor": actor,
        "note": note,
        "at": common.now_iso(),
    })


def _require_agent(value) -> str:
    agent_id = common.require_code(value, "agentId").lower()
    if catalog.get_agent(agent_id) is None:
        raise GovernanceInvalid("agentId 不在智能体目录中")
    return agent_id


def _next_no(items: list[dict]) -> str:
    prefix = "AI-INC-" + common.now().strftime("%Y%m%d") + "-"
    seq = sum(1 for i in items if str(i.get("incidentNo", "")).startswith(prefix)) + 1
    return f"{prefix}{seq:04d}"


def create_incident(payload: dict, actor: str) -> dict:
    agent_id = _require_agent(payload.get("agentId"))
    kind = common.require_choice(payload.get("kind"), "kind", KINDS)
    severity = common.require_choice(payload.get("severity"), "severity", SEVERITIES)
    source = common.require_choice(payload.get("source") or "CLINICIAN", "source", SOURCES)
    title = common.require_no_phi(
        common.require_text(payload.get("title"), "标题", 5, 120), "标题")
    description = common.require_no_phi(
        common.require_text(payload.get("description"), "事件描述", 10, 2000), "事件描述")
    model_version_id = common.optional_text(payload.get("modelVersionId"),
                                            "模型版本", 64)
    if model_version_id:
        versions.get_version(model_version_id)  # 不存在则 404，避免挂空引用
    skill_code = common.optional_text(payload.get("skillCode"), "技能编码", 64)
    study_code = common.optional_text(payload.get("studyCode"), "研究编号", 64)
    if study_code:
        from backend.agents.governance import validation
        validation.get_study(study_code)
    due = payload.get("dueAt")
    due_at = (common.parse_time(due, "截止时间").isoformat(timespec="seconds")
              if due else common.days_from_now(DUE_DAYS[severity]))

    with common.lock:
        items = _items()
        item = {
            "incidentNo": _next_no(items),
            "agentId": agent_id,
            "kind": kind,
            "severity": severity,
            "title": title,
            "description": description,
            "source": source,
            "modelVersionId": model_version_id,
            "skillCode": skill_code,
            "studyCode": study_code,
            "status": "OPEN",
            "owner": None,
            "dueAt": due_at,
            "rootCause": None,
            "correctiveAction": None,
            "resolutionNote": None,
            "resolvedBy": None,
            "resolvedAt": None,
            "closedBy": None,
            "closedAt": None,
            "reopenReason": None,
            "createdBy": actor,
            "createdAt": common.now_iso(),
            "updatedAt": common.now_iso(),
            "rowVersion": 1,
            "events": [],
        }
        _event(item, "CREATE", None, "OPEN", actor)
        items.append(item)
        common.save("incidents", items[-MAX_ITEMS:])
        return item


def list_incidents(status: str | None = None, severity: str | None = None,
                   agent_id: str | None = None, overdue_only: bool = False,
                   limit: int = 100) -> list[dict]:
    items = _items()
    if status:
        st = common.require_choice(status, "status", STATUSES)
        items = [i for i in items if i.get("status") == st]
    if severity:
        sv = common.require_choice(severity, "severity", SEVERITIES)
        items = [i for i in items if i.get("severity") == sv]
    if agent_id:
        items = [i for i in items if i.get("agentId") == str(agent_id).strip().lower()]
    if overdue_only:
        current = common.now_iso()
        items = [i for i in items if i.get("status") != "CLOSED"
                 and (i.get("dueAt") or "") < current]
    try:
        limit = max(1, min(int(limit), 500))
    except (TypeError, ValueError):
        limit = 100
    items.sort(key=lambda i: (i.get("createdAt") or ""), reverse=True)
    return [{k: v for k, v in i.items() if k != "events"} | {"eventCount": len(i.get("events") or [])}
            for i in items[:limit]]


def get_incident(incident_no: str) -> dict:
    return _find(_items(), incident_no)


def assign_incident(incident_no: str, owner, due_at, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, incident_no)
        if item.get("status") not in ("OPEN", "INVESTIGATING"):
            raise GovernanceInvalid("只有未解决事件可以分派")
        common.require_row_version(item, expected)
        item["owner"] = common.require_text(owner, "责任人", 2, 64)
        if due_at:
            item["dueAt"] = common.parse_time(due_at, "截止时间").isoformat(timespec="seconds")
        common.bump(item)
        common.save("incidents", items)
        return item


def start_incident(incident_no: str, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, incident_no)
        if item.get("status") != "OPEN":
            raise GovernanceInvalid("只有待处理事件可以开始调查")
        common.require_row_version(item, expected)
        item["status"] = "INVESTIGATING"
        item["owner"] = item.get("owner") or actor
        common.bump(item)
        _event(item, "START", "OPEN", "INVESTIGATING", actor)
        common.save("incidents", items)
        return item


def resolve_incident(incident_no: str, payload: dict, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, incident_no)
        if item.get("status") not in ("OPEN", "INVESTIGATING"):
            raise GovernanceInvalid("只有未解决事件可以标记解决")
        previous = item.get("status")
        common.require_row_version(item, payload.get("rowVersion"))
        root = common.require_no_phi(
            common.require_text(payload.get("rootCause"), "根因", 10, 2000), "根因")
        action = common.require_no_phi(
            common.require_text(payload.get("correctiveAction"), "纠正措施", 10, 2000),
            "纠正措施")
        note = common.optional_text(payload.get("resolutionNote"), "解决说明", 500)
        if note:
            note = common.require_no_phi(note, "解决说明")
        item["rootCause"] = root
        item["correctiveAction"] = action
        item["resolutionNote"] = note
        item["status"] = "RESOLVED"
        item["resolvedBy"] = actor
        item["resolvedAt"] = common.now_iso()
        item["owner"] = item.get("owner") or actor
        common.bump(item)
        _event(item, "RESOLVE", previous, "RESOLVED", actor, note)
        common.save("incidents", items)
        return item


def close_incident(incident_no: str, payload: dict, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, incident_no)
        if item.get("status") != "RESOLVED":
            raise GovernanceInvalid("只有已解决事件可以关闭")
        if actor == item.get("resolvedBy"):
            raise GovernanceForbidden("关闭事件不能由解决人本人完成，请由其他人复核")
        common.require_row_version(item, payload.get("rowVersion"))
        note = common.require_text(payload.get("closeNote") or "复核通过", "关闭说明", 2, 500)
        note = common.require_no_phi(note, "关闭说明")
        item["status"] = "CLOSED"
        item["closedBy"] = actor
        item["closedAt"] = common.now_iso()
        common.bump(item)
        _event(item, "CLOSE", "RESOLVED", "CLOSED", actor, note)
        common.save("incidents", items)
        return item


def reopen_incident(incident_no: str, reason, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, incident_no)
        previous = item.get("status")
        if previous not in ("RESOLVED", "CLOSED"):
            raise GovernanceInvalid("只有已解决或已关闭事件可以重开")
        common.require_row_version(item, expected)
        note = common.require_text(reason, "重开原因", 2, 500)
        item["status"] = "OPEN"
        item["reopenReason"] = note
        item["resolvedBy"] = None
        item["resolvedAt"] = None
        item["closedBy"] = None
        item["closedAt"] = None
        item["dueAt"] = common.days_from_now(7)
        common.bump(item)
        _event(item, "REOPEN", previous, "OPEN", actor, note)
        common.save("incidents", items)
        return item


def summary() -> dict:
    items = _items()
    current = common.now_iso()
    counts = {status: 0 for status in STATUSES}
    by_severity = {severity: 0 for severity in SEVERITIES}
    overdue = 0
    open_high = 0
    closed_hours: list[float] = []
    for item in items:
        status = item.get("status") or "OPEN"
        counts[status] = counts.get(status, 0) + 1
        severity = item.get("severity") or "MEDIUM"
        by_severity[severity] = by_severity.get(severity, 0) + 1
        if status != "CLOSED" and (item.get("dueAt") or "") < current:
            overdue += 1
            if severity in ("HIGH", "CRITICAL"):
                open_high += 1
        if status == "CLOSED" and item.get("closedAt") and item.get("createdAt"):
            try:
                closed = common.parse_time(item["closedAt"], "closedAt")
                created = common.parse_time(item["createdAt"], "createdAt")
                closed_hours.append((closed - created).total_seconds() / 3600)
            except GovernanceInvalid:
                pass
    total = len(items)
    closed = counts.get("CLOSED", 0)
    return {
        "total": total,
        "open": counts.get("OPEN", 0),
        "investigating": counts.get("INVESTIGATING", 0),
        "resolved": counts.get("RESOLVED", 0),
        "closed": closed,
        "overdue": overdue,
        "openHigh": open_high,
        "bySeverity": by_severity,
        "closureRate": round(closed / total, 4) if total else 0.0,
        "meanTimeToCloseHours": round(sum(closed_hours) / len(closed_hours), 1)
        if closed_hours else 0.0,
    }

