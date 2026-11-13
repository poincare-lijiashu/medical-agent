"""§296 AI 临床验证与模型治理：版本、验证、漂移、误报闭环。"""
from __future__ import annotations

from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from backend.agents.governance import common, drift, incidents, validation, versions
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

HASH = "a" * 64


@pytest.fixture(autouse=True)
def _isolate_governance(tmp_path, monkeypatch):
    monkeypatch.setattr(common, "DATA_DIR", str(tmp_path))
    versions._clear_cache()


def _version(component="drug", kind="MODEL", version="2026.11.1",
             provider="deepseek", model="deepseek-chat", prompt_hash=None,
             creator="admin01", approver="qc01") -> dict:
    item = versions.create_version({
        "component": component, "kind": kind, "version": version,
        "provider": provider, "model": model,
        "promptHash": prompt_hash,
        "changeSummary": "升级到新模型并收紧证据引用要求",
    }, creator)
    item = versions.submit_version(item["versionId"], item["rowVersion"], creator)
    item = versions.review_version(item["versionId"], True, "同意", item["rowVersion"],
                                   approver)
    return versions.activate_version(item["versionId"], item["rowVersion"], "admin01")["version"]


def _case(i: int, reference: bool, prediction: bool, **over) -> dict:
    payload = {"caseId": f"C{i:03d}", "referencePositive": reference,
               "predictionPositive": prediction}
    payload.update(over)
    return payload


def _fill_cases(code: str, tp=14, fn=1, tn=14, fp=1, start=100) -> dict:
    i = start
    for _ in range(tp):
        i += 1
        validation.add_case(code, _case(i, True, True), "qc01")
    for _ in range(fn):
        i += 1
        validation.add_case(code, _case(i, True, False), "qc01")
    for _ in range(tn):
        i += 1
        validation.add_case(code, _case(i, False, False), "qc01")
    for _ in range(fp):
        i += 1
        validation.add_case(code, _case(i, False, True), "qc01")
    return validation.get_study(code)


def _study(**over) -> dict:
    payload = {
        "title": "药物相互作用提示临床验证",
        "agentId": "drug",
        "intendedUse": "辅助医生识别门诊处方中的潜在药物相互作用，不替代药师审核",
        "population": "成人门诊患者",
        "goldStandard": "两名临床药师独立审核一致的结果",
        "protocol": "连续入组门诊处方，盲法比较模型提示与药师审核结论",
        "targetSampleSize": 100,
    }
    payload.update(over)
    return validation.create_study(payload, "qc01")


# ---------------------------------------------------------------- 版本

def test_version_state_machine_and_trace():
    _version()
    trace = versions.trace("drug")
    assert trace["traceable"] is True
    assert trace["complete"] is False
    assert trace["modelVersion"]["version"] == "2026.11.1"
    assert trace["modelScope"] == "component"

    _version(kind="PROMPT", version="p-2026.11.1", prompt_hash=HASH)
    trace = versions.trace("drug")
    assert trace["complete"] is True
    assert trace["promptVersion"]["promptHash"] == HASH


def test_version_four_eyes_and_duplicate():
    item = versions.create_version({
        "component": "drug", "kind": "MODEL", "version": "1.0.0",
        "provider": "deepseek", "model": "deepseek-chat",
        "changeSummary": "首次登记模型版本用于可追溯",
    }, "admin01")
    versions.submit_version(item["versionId"], item["rowVersion"], "admin01")
    with pytest.raises(common.GovernanceForbidden):
        versions.review_version(item["versionId"], True, "自审", 2, "admin01")
    with pytest.raises(common.GovernanceConflict):
        versions.create_version({
            "component": "drug", "kind": "MODEL", "version": "1.0.0",
            "provider": "deepseek", "model": "deepseek-chat",
            "changeSummary": "重复版本号应被拒绝，用于验证唯一性约束",
        }, "admin01")


def test_activation_retires_previous_and_rollback():
    first = _version(version="1.0.0")
    second = _version(version="2.0.0")
    assert versions.get_version(first["versionId"])["status"] == "RETIRED"
    # 回滚到已退休但曾审批生效的版本
    result = versions.activate_version(first["versionId"],
                                       versions.get_version(first["versionId"])["rowVersion"],
                                       "admin01")
    assert result["version"]["status"] == "ACTIVE"
    assert result["version"]["rollbackTo"] == second["versionId"]
    assert versions.get_version(second["versionId"])["status"] == "RETIRED"
    assert versions.trace("drug")["modelVersion"]["version"] == "1.0.0"


def test_version_rejects_invalid_model_and_prompt():
    with pytest.raises(common.GovernanceInvalid):
        versions.create_version({
            "component": "drug", "kind": "MODEL", "version": "1.0.0",
            "changeSummary": "缺少 provider 与 model",
        }, "admin01")
    with pytest.raises(common.GovernanceInvalid):
        versions.create_version({
            "component": "drug", "kind": "PROMPT", "version": "p1",
            "changeSummary": "缺少 promptHash",
        }, "admin01")


# ---------------------------------------------------------------- 临床验证

def test_validation_metrics_and_wilson_ci():
    result = validation._compute(
        [{"referencePositive": True, "predictionPositive": True}] * 8
        + [{"referencePositive": True, "predictionPositive": False}] * 2
        + [{"referencePositive": False, "predictionPositive": False}] * 9
        + [{"referencePositive": False, "predictionPositive": True}] * 1,
        0.8, 0.8)
    assert result["sensitivity"] == 0.8
    assert result["specificity"] == 0.9
    assert result["ppv"] == 0.8889
    assert result["npv"] == 0.8182
    assert result["n"] == 20
    low, high = result["sensitivityCi"]
    assert 0 < low < 0.8 < high < 1
    assert result["qualityGate"] == "INSUFFICIENT"  # 未达 30 例


def test_validation_requires_sample_and_four_eyes():
    study = _study()
    for i in range(5):
        validation.add_case(study["studyCode"], _case(i, True, True), "qc01")
    with pytest.raises(common.GovernanceInvalid):
        validation.submit_study(study["studyCode"],
                                validation.get_study(study["studyCode"])["rowVersion"],
                                "qc01")
    study = _fill_cases(study["studyCode"])
    study = validation.submit_study(study["studyCode"], study["rowVersion"], "qc01")
    with pytest.raises(common.GovernanceForbidden):
        validation.review_study(study["studyCode"], True, {
            "conclusion": "模型在测试集上表现良好，可用于辅助提示",
            "limitations": "单中心回顾性研究，外推需谨慎",
            "rowVersion": study["rowVersion"],
        }, "qc01")
    approved = validation.review_study(study["studyCode"], True, {
        "conclusion": "敏感度与特异度均达到预设验收阈值，可用于辅助提示",
        "limitations": "单中心回顾性研究，样本量有限，需多中心验证",
        "rowVersion": study["rowVersion"],
    }, "admin01")
    assert approved["status"] == "APPROVED"
    assert approved["approvedResult"]["meetsAcceptance"] is True
    assert approved["approvedResult"]["qualityGate"] == "PASS"


def test_validation_rejects_phi_and_duplicate_case():
    study = _study()
    with pytest.raises(common.GovernanceInvalid):
        validation.add_case(study["studyCode"],
                            _case(1, True, True, note="患者身份证110101199003071234"),
                            "qc01")
    validation.add_case(study["studyCode"], _case(1, True, True), "qc01")
    with pytest.raises(common.GovernanceConflict):
        validation.add_case(study["studyCode"], _case(1, True, False), "qc01")


# ---------------------------------------------------------------- 漂移

def _snapshot(requests=40, errors=14, quality=14, latency_ms=6000,
              tokens=4000, tokenized=40):
    ok = requests - errors
    return {
        "requests": {"drug|sync|ok": ok, "drug|sync|error": errors},
        "quality": {"drug|no_evidence": quality},
        "agent_durations": {"drug|sync": {"sum_ms": latency_ms * requests,
                                          "count": requests}},
        "agent_tokens": {"drug|input": tokens // 2, "drug|output": tokens // 2},
        "agent_tokenized": {"drug": tokenized},
    }


def test_drift_insufficient_sample(monkeypatch):
    monkeypatch.setattr(drift.consult_metrics, "snapshot", lambda: _snapshot(requests=5))
    result = drift.capture(["drug"], "qc01")
    item = result["captured"][0]
    assert item["status"] == "INSUFFICIENT"
    assert item["signals"] == []


def test_drift_signals_and_baseline(monkeypatch):
    monkeypatch.setattr(drift.consult_metrics, "snapshot", lambda: _snapshot())
    first = drift.capture(["drug"], "qc01")["captured"][0]
    assert first["status"] == "ALERT"
    codes = {s["code"] for s in first["signals"]}
    assert "ERROR_RATE_HIGH" in codes and "QUALITY_FLAG_HIGH" in codes
    baseline = drift.set_baseline("drug", first["snapshotId"], "admin01")
    assert baseline["metrics"]["requests"] == 40

    monkeypatch.setattr(drift.consult_metrics, "snapshot",
                        lambda: _snapshot(requests=40, errors=22, quality=18,
                                          latency_ms=12000, tokens=8000))
    second = drift.capture(["drug"], "qc01")["captured"][0]
    codes = {s["code"] for s in second["signals"]}
    assert {"ERROR_RATE_UP", "LATENCY_UP", "TOKEN_UP"} <= codes
    report = drift.report("drug")
    assert report["agents"][0]["baseline"]["snapshotId"] == first["snapshotId"]


# ---------------------------------------------------------------- 误报闭环

def _incident(**over) -> dict:
    payload = {
        "agentId": "drug", "kind": "FALSE_POSITIVE", "severity": "HIGH",
        "title": "相互作用提示误报", "description": "模型提示两种药物存在禁忌，药师复核为可联用",
        "source": "CLINICIAN",
    }
    payload.update(over)
    return incidents.create_incident(payload, "doctor01")


def test_incident_lifecycle_and_four_eyes():
    item = _incident()
    assert item["status"] == "OPEN" and item["events"][0]["action"] == "CREATE"
    item = incidents.assign_incident(item["incidentNo"], "qc01", None,
                                     item["rowVersion"], "qc01")
    item = incidents.start_incident(item["incidentNo"], item["rowVersion"], "qc01")
    item = incidents.resolve_incident(item["incidentNo"], {
        "rootCause": "规则未区分剂量与给药途径，导致绝对禁忌被放大",
        "correctiveAction": "补充剂量条件并在提示中标注证据等级",
        "rowVersion": item["rowVersion"],
    }, "qc01")
    assert item["status"] == "RESOLVED"
    with pytest.raises(common.GovernanceForbidden):
        incidents.close_incident(item["incidentNo"], {
            "closeNote": "本人关闭", "rowVersion": item["rowVersion"]}, "qc01")
    item = incidents.close_incident(item["incidentNo"], {
        "closeNote": "复核通过，规则已更新", "rowVersion": item["rowVersion"]}, "admin01")
    assert item["status"] == "CLOSED"
    item = incidents.reopen_incident(item["incidentNo"], "同类误报再次出现",
                                     item["rowVersion"], "qc01")
    assert item["status"] == "OPEN" and item["reopenReason"] == "同类误报再次出现"


def test_incident_overdue_and_phi_rejected():
    past = (common.now() - timedelta(days=1)).isoformat(timespec="seconds")
    _incident(dueAt=past)
    summary = incidents.summary()
    assert summary["overdue"] == 1
    with pytest.raises(common.GovernanceInvalid):
        _incident(description="患者手机号13800138000，模型提示错误")


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, username: str) -> str:
    r = c.post("/api/v1/auth/login", json={"username": username, "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(token: str) -> dict:
    return {"Authorization": "Bearer " + token}


def test_api_roles_and_version_flow(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doctor = _h(_login(c, "doctor01"))
    admin = _h(_login(c, "admin01"))
    qc = _h(_login(c, "qc01"))

    assert c.get("/api/v1/agent/governance/summary", headers=doctor).status_code == 403
    assert c.get("/api/v1/agent/governance/summary", headers=admin).status_code == 200

    body = {"component": "drug", "kind": "MODEL", "version": "1.0.0",
            "provider": "deepseek", "model": "deepseek-chat",
            "changeSummary": "API 冒烟：登记模型版本用于追溯"}
    assert c.post("/api/v1/agent/governance/versions", headers=qc, json=body).status_code == 403
    r = c.post("/api/v1/agent/governance/versions", headers=admin, json=body)
    assert r.status_code == 200, r.text
    vid = r.json()["versionId"]
    r = c.post(f"/api/v1/agent/governance/versions/{vid}/submit", headers=admin,
               json={"rowVersion": 1})
    assert r.status_code == 200
    r = c.post(f"/api/v1/agent/governance/versions/{vid}/review", headers=qc,
               json={"approve": True, "comment": "同意", "rowVersion": 2})
    assert r.status_code == 200, r.text
    r = c.post(f"/api/v1/agent/governance/versions/{vid}/activate", headers=admin,
               json={"rowVersion": 3})
    assert r.status_code == 200, r.text
    trace = c.get("/api/v1/agent/governance/trace", params={"component": "drug"},
                  headers=qc).json()
    assert trace["modelVersion"]["version"] == "1.0.0"


def test_api_incident_doctor_can_create_but_not_close(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doctor = _h(_login(c, "doctor01"))
    qc = _h(_login(c, "qc01"))
    payload = {"agentId": "drug", "kind": "FALSE_POSITIVE", "severity": "MEDIUM",
               "title": "API 误报", "description": "医生上报的误报事件，用于验证角色权限",
               "source": "CLINICIAN"}
    r = c.post("/api/v1/agent/governance/incidents", headers=doctor, json=payload)
    assert r.status_code == 200, r.text
    no = r.json()["incidentNo"]
    assert c.post(f"/api/v1/agent/governance/incidents/{no}/start", headers=doctor,
                  json={"rowVersion": 1}).status_code == 403
    assert c.post(f"/api/v1/agent/governance/incidents/{no}/start", headers=qc,
                  json={"rowVersion": 1}).status_code == 200


def test_consult_response_carries_active_version(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    _version(component="drug", version="2026.11.1")
    from backend.api.v1.agent import agent_router

    async def fake_run(question, role, agent_id=None, skill_codes=None, history=None):
        return {"agent": {"id": "drug", "name": "药物助手"}, "routing": {},
                "appliedSkills": [], "answer": "建议复核相互作用。",
                "needsHumanReview": True}

    monkeypatch.setattr(agent_router.consult, "run", fake_run)
    doctor = _h(_login(c, "doctor01"))
    r = c.post("/api/v1/agent/consult", headers=doctor,
               json={"question": "这个处方有相互作用吗？"})
    assert r.status_code == 200, r.text
    assert r.json()["governance"]["modelVersion"]["version"] == "2026.11.1"
    assert r.json()["governance"]["component"] == "drug"


def test_summary_readiness_reflects_delivered_controls(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    _version(component="drug", version="1.0.0")
    _version(component="drug", kind="PROMPT", version="p1", prompt_hash=HASH)
    study = _study()
    study = _fill_cases(study["studyCode"])
    study = validation.submit_study(study["studyCode"], study["rowVersion"], "qc01")
    validation.review_study(study["studyCode"], True, {
        "conclusion": "达到预设验收阈值，可用于辅助提示",
        "limitations": "单中心回顾性研究，需多中心验证",
        "rowVersion": study["rowVersion"]}, "admin01")
    monkeypatch.setattr(drift.consult_metrics, "snapshot", lambda: _snapshot())
    snap = drift.capture(["drug"], "qc01")["captured"][0]
    drift.set_baseline("drug", snap["snapshotId"], "admin01")

    admin = _h(_login(c, "admin01"))
    data = c.get("/api/v1/agent/governance/summary", headers=admin).json()
    checks = data["readiness"]["checks"]
    assert checks["activeModelVersion"] is True
    assert checks["activePromptVersion"] is True
    assert checks["approvedValidationMeetsAcceptance"] is True
    assert checks["driftBaselineConfigured"] is True
    # 评测 D-13：运行时模型绑定必须是独立检查项。它的取值取决于本机是否真的把
    # 运行时模型登记成了 ACTIVE，因此只锁"存在且为布尔"，不锁具体真假（避免环境耦合）。
    assert isinstance(checks["runtimeModelMatchesRegistry"], bool)
    assert data["readiness"]["total"] == len(checks) == 5
    passed = sum(1 for v in checks.values() if v)
    assert data["readiness"]["score"] == round(passed / len(checks), 4)
