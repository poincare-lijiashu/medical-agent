"""§296 模型/提示词版本治理（计划书 W8.2）。

目标：任一 AI 输出都能回答"当时用的是哪个模型版本、哪版提示词、谁批准的"。

规则：
- 状态机 ``DRAFT → IN_REVIEW → APPROVED → ACTIVE → RETIRED``；
  驳回回到 ``DRAFT`` 并必须写原因；
- 四眼：审批人不能是创建人；
- 同组件同类型只能有一个 ``ACTIVE``；激活新版本会自动把旧版本置为 ``RETIRED``；
- 回滚 = 重新激活一个已批准的旧版本，``rollbackTo`` 记录被替换的版本；
- 生效版本按组件缓存 30s，激活/停用立即失效（避免每次问答都读盘）。
"""
from __future__ import annotations

import time

from backend.agents.governance import common
from backend.agents.governance.common import (
    GovernanceConflict,
    GovernanceForbidden,
    GovernanceInvalid,
    GovernanceNotFound,
)

KINDS = ("MODEL", "PROMPT")
STATUSES = ("DRAFT", "IN_REVIEW", "APPROVED", "ACTIVE", "RETIRED")
TRACE_TTL_S = 30.0

_cache: dict[str, tuple[float, dict]] = {}


def _items() -> list[dict]:
    return common.load("versions")


def _find(items: list[dict], version_id: str) -> dict:
    for item in items:
        if item.get("versionId") == version_id:
            return item
    raise GovernanceNotFound("未找到该版本记录")


def _clear_cache() -> None:
    _cache.clear()


def list_versions(component: str | None = None, status: str | None = None,
                  kind: str | None = None) -> list[dict]:
    items = _items()
    if component:
        comp = common.require_component(component)
        items = [i for i in items if i.get("component") == comp]
    if status:
        st = common.require_choice(status, "status", STATUSES)
        items = [i for i in items if i.get("status") == st]
    if kind:
        kd = common.require_choice(kind, "kind", KINDS)
        items = [i for i in items if i.get("kind") == kd]
    return sorted(items, key=lambda i: (i.get("createdAt") or ""), reverse=True)


def get_version(version_id: str) -> dict:
    return _find(_items(), version_id)


def create_version(payload: dict, actor: str) -> dict:
    component = common.require_component(payload.get("component"))
    kind = common.require_choice(payload.get("kind"), "kind", KINDS)
    version = common.require_version(payload.get("version"))
    provider = common.optional_text(payload.get("provider"), "provider", 64)
    model = common.optional_text(payload.get("model"), "model", 128)
    prompt_hash = payload.get("promptHash")
    if kind == "MODEL" and (not provider or not model):
        raise GovernanceInvalid("模型版本必须填写 provider 与 model")
    if kind == "PROMPT" and not prompt_hash:
        raise GovernanceInvalid("提示词版本必须填写 promptHash（SHA-256）")
    prompt_hash = (common.require_sha256(prompt_hash, "promptHash")
                   if prompt_hash else None)
    change = common.require_no_phi(
        common.require_text(payload.get("changeSummary"), "变更说明", 10, 1000),
        "变更说明")

    with common.lock:
        items = _items()
        if any(i.get("component") == component and i.get("kind") == kind
               and i.get("version") == version for i in items):
            raise GovernanceConflict("该组件同类型已存在相同版本号，请换一个版本号")
        item = {
            "versionId": common.new_id("mv"),
            "component": component,
            "kind": kind,
            "version": version,
            "provider": provider,
            "model": model,
            "promptHash": prompt_hash,
            "changeSummary": change,
            "status": "DRAFT",
            "createdBy": actor,
            "createdAt": common.now_iso(),
            "updatedAt": common.now_iso(),
            "rowVersion": 1,
        }
        items.append(item)
        common.save("versions", items)
        _clear_cache()
        return item


def submit_version(version_id: str, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, version_id)
        if item.get("status") != "DRAFT":
            raise GovernanceInvalid("只有草稿版本可以提交审核")
        common.require_row_version(item, expected)
        item["status"] = "IN_REVIEW"
        item["submittedBy"] = actor
        item["submittedAt"] = common.now_iso()
        common.bump(item)
        common.save("versions", items)
        return item


def review_version(version_id: str, approve: bool, comment, expected: int,
                   actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, version_id)
        if item.get("status") != "IN_REVIEW":
            raise GovernanceInvalid("只有待审核版本可以审批")
        if actor == item.get("createdBy"):
            raise GovernanceForbidden("不能审批自己创建的版本，请由其他人复核")
        common.require_row_version(item, expected)
        note = common.optional_text(comment, "审批意见", 500)
        if approve:
            item["status"] = "APPROVED"
            item["approvedBy"] = actor
            item["approvedAt"] = common.now_iso()
            item["approvalComment"] = note
        else:
            if not note or len(note) < 2:
                raise GovernanceInvalid("驳回必须填写原因（至少 2 个字符）")
            item["status"] = "DRAFT"
            item["rejectedBy"] = actor
            item["rejectedAt"] = common.now_iso()
            item["rejectReason"] = note
        common.bump(item)
        common.save("versions", items)
        return item


def activate_version(version_id: str, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        target = _find(items, version_id)
        if target.get("status") not in ("APPROVED", "RETIRED"):
            raise GovernanceInvalid("只有已批准或曾生效过的版本可以生效")
        if target.get("status") == "RETIRED" and not target.get("approvedBy"):
            raise GovernanceInvalid("该版本没有审批记录，不能作为回滚目标")
        common.require_row_version(target, expected)
        previous = None
        for item in items:
            if item is target:
                continue
            if (item.get("component") == target.get("component")
                    and item.get("kind") == target.get("kind")
                    and item.get("status") == "ACTIVE"):
                previous = item
                item["status"] = "RETIRED"
                item["retiredBy"] = actor
                item["retiredAt"] = common.now_iso()
                item["retireReason"] = f"被 {target['versionId']} 替代"
                common.bump(item)
        target["status"] = "ACTIVE"
        target["activatedBy"] = actor
        target["activatedAt"] = common.now_iso()
        target["rollbackTo"] = previous.get("versionId") if previous else None
        common.bump(target)
        common.save("versions", items)
        _clear_cache()
        return {"version": target, "superseded": previous}


def retire_version(version_id: str, reason, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        item = _find(items, version_id)
        if item.get("status") not in ("ACTIVE", "APPROVED"):
            raise GovernanceInvalid("只有生效或已批准的版本可以停用")
        note = common.require_text(reason, "停用原因", 2, 256)
        common.require_row_version(item, expected)
        item["status"] = "RETIRED"
        item["retiredBy"] = actor
        item["retiredAt"] = common.now_iso()
        item["retireReason"] = note
        common.bump(item)
        common.save("versions", items)
        _clear_cache()
        return item


def _view(item: dict | None) -> dict | None:
    if not item:
        return None
    return {
        "versionId": item.get("versionId"),
        "component": item.get("component"),
        "kind": item.get("kind"),
        "version": item.get("version"),
        "provider": item.get("provider"),
        "model": item.get("model"),
        "promptHash": item.get("promptHash"),
        "approvedBy": item.get("approvedBy"),
        "activatedAt": item.get("activatedAt"),
    }


def trace(component: str) -> dict:
    """返回该组件当前生效的模型/提示词版本；供问答响应携带，证明可追溯。"""
    comp = common.require_component(component)
    cached = _cache.get(comp)
    current = time.monotonic()
    if cached and cached[0] > current:
        return cached[1]

    items = _items()

    def _active(kind: str) -> tuple[dict | None, str | None]:
        for item in items:
            if (item.get("component") == comp and item.get("kind") == kind
                    and item.get("status") == "ACTIVE"):
                return item, "component"
        for item in items:
            if (item.get("component") == "global" and item.get("kind") == kind
                    and item.get("status") == "ACTIVE"):
                return item, "global"
        return None, None

    model, model_scope = _active("MODEL")
    prompt, prompt_scope = _active("PROMPT")
    value = {
        "component": comp,
        "modelVersion": _view(model),
        "promptVersion": _view(prompt),
        "modelScope": model_scope,
        "promptScope": prompt_scope,
        "traceable": bool(model or prompt),
        "complete": bool(model and prompt),
        "capturedAt": common.now_iso(),
    }
    _cache[comp] = (current + TRACE_TTL_S, value)
    return value


def active_versions() -> list[dict]:
    return [i for i in _items() if i.get("status") == "ACTIVE"]

