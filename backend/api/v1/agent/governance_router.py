"""§296 AI 治理 API：版本、临床验证、漂移、误报闭环与看板。

权限：
- 读：admin / qc（治理信息含模型与质量数据，不对普通医生开放）；
- 版本创建/提交/生效：admin；审批：admin / qc（服务层强制四眼）；
- 临床验证录入/提交：admin / qc；审批：admin / qc（四眼）；
- 漂移抓取：admin / qc；设基线：admin；
- 事件创建：admin / qc / doctor；分派/解决/关闭/重开：admin / qc。

审计只记动作与业务编号，不记事件标题/描述（避免把可能敏感的内容复制进审计）。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException

from backend.agents.governance import common, drift, incidents, overview, validation, versions
from backend.api.deps import rate_limit, require_role

router = APIRouter(dependencies=[Depends(rate_limit)])

READ = ("admin", "qc")
WRITE = ("admin", "qc")


def _http(e: Exception) -> HTTPException:
    if isinstance(e, common.GovernanceNotFound):
        return HTTPException(404, str(e))
    if isinstance(e, common.GovernanceForbidden):
        return HTTPException(403, str(e))
    if isinstance(e, common.GovernanceConflict):
        return HTTPException(409, str(e))
    if isinstance(e, common.GovernanceInvalid):
        return HTTPException(422, str(e))
    return HTTPException(500, "AI 治理操作失败（服务端异常）")


async def _audit(user: dict, action: str, payload: dict) -> None:
    from backend.api.v1.medical import shared as _shared
    await asyncio.to_thread(
        _shared.get_audit_logger().write,
        event_type="ai_governance", action=action,
        actor=user.get("username", "anonymous"), payload=payload,
    )


def _actor(user: dict) -> str:
    return user.get("username") or user.get("name") or "anonymous"


# ---------------------------------------------------------------- 看板

@router.get("/summary")
def governance_summary(user: dict = Depends(require_role(*READ))):
    return overview()


# ---------------------------------------------------------------- 版本

@router.get("/versions")
def list_versions(component: str | None = None, status: str | None = None,
                  kind: str | None = None,
                  user: dict = Depends(require_role(*READ))):
    try:
        return {"versions": versions.list_versions(component, status, kind)}
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.get("/versions/{version_id}")
def get_version(version_id: str, user: dict = Depends(require_role(*READ))):
    try:
        return versions.get_version(version_id)
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.get("/trace")
def trace(component: str, user: dict = Depends(require_role(*READ))):
    try:
        return versions.trace(component)
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.post("/versions")
async def create_version(req: dict, user: dict = Depends(require_role("admin"))):
    try:
        item = versions.create_version(req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "version_create", {
        "versionId": item["versionId"], "component": item["component"],
        "kind": item["kind"], "version": item["version"]})
    return item


@router.post("/versions/{version_id}/submit")
async def submit_version(version_id: str, req: dict,
                         user: dict = Depends(require_role("admin"))):
    try:
        item = versions.submit_version(version_id, req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "version_submit", {"versionId": version_id})
    return item


@router.post("/versions/{version_id}/review")
async def review_version(version_id: str, req: dict,
                         user: dict = Depends(require_role(*WRITE))):
    try:
        item = versions.review_version(version_id, bool(req.get("approve")),
                                       req.get("comment"), req.get("rowVersion"),
                                       _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "version_review", {
        "versionId": version_id, "approve": bool(req.get("approve")),
        "status": item["status"]})
    return item


@router.post("/versions/{version_id}/activate")
async def activate_version(version_id: str, req: dict,
                           user: dict = Depends(require_role("admin"))):
    try:
        result = versions.activate_version(version_id, req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "version_activate", {
        "versionId": version_id,
        "superseded": (result.get("superseded") or {}).get("versionId")})
    return result


@router.post("/versions/{version_id}/retire")
async def retire_version(version_id: str, req: dict,
                         user: dict = Depends(require_role("admin"))):
    try:
        item = versions.retire_version(version_id, req.get("reason"),
                                       req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "version_retire", {"versionId": version_id})
    return item


# ---------------------------------------------------------------- 临床验证

@router.get("/studies")
def list_studies(status: str | None = None, agentId: str | None = None,
                 user: dict = Depends(require_role(*READ))):
    try:
        return {"studies": validation.list_studies(status, agentId)}
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.get("/studies/{study_code}")
def get_study(study_code: str, user: dict = Depends(require_role(*READ))):
    try:
        return validation.get_study(study_code)
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.post("/studies")
async def create_study(req: dict, user: dict = Depends(require_role(*WRITE))):
    try:
        item = validation.create_study(req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "study_create", {
        "studyCode": item["studyCode"], "agentId": item["agentId"]})
    return item


@router.post("/studies/{study_code}/cases")
async def add_case(study_code: str, req: dict,
                   user: dict = Depends(require_role(*WRITE))):
    try:
        item = validation.add_case(study_code, req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "study_case_add", {
        "studyCode": study_code, "caseId": req.get("caseId"),
        "n": (item.get("result") or {}).get("n")})
    return item


@router.post("/studies/{study_code}/submit")
async def submit_study(study_code: str, req: dict,
                       user: dict = Depends(require_role(*WRITE))):
    try:
        item = validation.submit_study(study_code, req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "study_submit", {"studyCode": study_code})
    return item


@router.post("/studies/{study_code}/review")
async def review_study(study_code: str, req: dict,
                       user: dict = Depends(require_role(*WRITE))):
    try:
        item = validation.review_study(study_code, bool(req.get("approve")), req,
                                       _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "study_review", {
        "studyCode": study_code, "approve": bool(req.get("approve")),
        "status": item["status"],
        "meetsAcceptance": (item.get("approvedResult") or {}).get("meetsAcceptance")})
    return item


@router.post("/studies/{study_code}/retire")
async def retire_study(study_code: str, req: dict,
                       user: dict = Depends(require_role("admin"))):
    try:
        item = validation.retire_study(study_code, req.get("reason"),
                                       req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "study_retire", {"studyCode": study_code})
    return item


# ---------------------------------------------------------------- 漂移

@router.get("/drift")
def drift_report(agentId: str | None = None, limit: int = 50,
                 user: dict = Depends(require_role(*READ))):
    try:
        return drift.report(agentId, limit)
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.post("/drift/snapshots")
async def capture_snapshots(req: dict, user: dict = Depends(require_role(*WRITE))):
    try:
        result = drift.capture(req.get("agentIds"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "drift_capture", {
        "agents": [i["agentId"] for i in result.get("captured") or []],
        "alerts": sum(1 for i in result.get("captured") or [] if i.get("status") == "ALERT")})
    return result


@router.post("/drift/baseline")
async def set_baseline(req: dict, user: dict = Depends(require_role("admin"))):
    try:
        item = drift.set_baseline(req.get("agentId"), req.get("snapshotId"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "drift_baseline", {
        "agentId": item["agentId"], "snapshotId": item["snapshotId"]})
    return item


# ---------------------------------------------------------------- 事件闭环

@router.get("/incidents")
def list_incidents(status: str | None = None, severity: str | None = None,
                   agentId: str | None = None, overdueOnly: bool = False,
                   limit: int = 100, user: dict = Depends(require_role(*READ))):
    try:
        return {"incidents": incidents.list_incidents(status, severity, agentId,
                                                      overdueOnly, limit)}
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.get("/incidents/{incident_no}")
def get_incident(incident_no: str, user: dict = Depends(require_role(*READ))):
    try:
        return incidents.get_incident(incident_no)
    except Exception as e:  # noqa: BLE001
        raise _http(e)


@router.post("/incidents")
async def create_incident(req: dict, user: dict = Depends(require_role("admin", "qc", "doctor"))):
    try:
        item = incidents.create_incident(req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_create", {
        "incidentNo": item["incidentNo"], "agentId": item["agentId"],
        "kind": item["kind"], "severity": item["severity"]})
    return item


@router.post("/incidents/{incident_no}/assign")
async def assign_incident(incident_no: str, req: dict,
                          user: dict = Depends(require_role(*WRITE))):
    try:
        item = incidents.assign_incident(incident_no, req.get("owner"),
                                         req.get("dueAt"), req.get("rowVersion"),
                                         _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_assign", {
        "incidentNo": incident_no, "owner": item.get("owner")})
    return item


@router.post("/incidents/{incident_no}/start")
async def start_incident(incident_no: str, req: dict,
                         user: dict = Depends(require_role(*WRITE))):
    try:
        item = incidents.start_incident(incident_no, req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_start", {"incidentNo": incident_no})
    return item


@router.post("/incidents/{incident_no}/resolve")
async def resolve_incident(incident_no: str, req: dict,
                           user: dict = Depends(require_role(*WRITE))):
    try:
        item = incidents.resolve_incident(incident_no, req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_resolve", {"incidentNo": incident_no})
    return item


@router.post("/incidents/{incident_no}/close")
async def close_incident(incident_no: str, req: dict,
                         user: dict = Depends(require_role(*WRITE))):
    try:
        item = incidents.close_incident(incident_no, req, _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_close", {"incidentNo": incident_no})
    return item


@router.post("/incidents/{incident_no}/reopen")
async def reopen_incident(incident_no: str, req: dict,
                          user: dict = Depends(require_role(*WRITE))):
    try:
        item = incidents.reopen_incident(incident_no, req.get("reason"),
                                         req.get("rowVersion"), _actor(user))
    except Exception as e:  # noqa: BLE001
        raise _http(e)
    await _audit(user, "incident_reopen", {"incidentNo": incident_no})
    return item
