"""G103 技能使用率看板数据：聚合口径、未使用/未发布技能与接口权限。"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.agents import consult_metrics, skill_usage, skills
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


# 评审 05-S11：计量重置已上提到 tests/conftest.py 的 autouse fixture，此处不再重复定义


def _publish(code: str, name: str, agent_id: str = "drug") -> str:
    card = skills.create_skill({
        "code": code, "name": name,
        "description": "用于技能使用率看板测试的已发布技能卡说明文本。",
        "agentId": agent_id,
        "promptTemplate": "请按院内规范作答，逐条标注证据来源，并在证据不足时明确说明不确定性。",
        "toolAllowlist": ["drug_check"] if agent_id == "drug" else ["kb_search"],
        "evidenceRefs": ["院内规则库 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")
    return card["code"]


def test_empty_report():
    r = skill_usage.report()
    assert r["window"] == "process"
    assert r["requests"]["total"] == 0
    assert r["requests"]["successRate"] == 0.0
    assert r["skills"]["published"] == 0
    assert r["skills"]["usageRatio"] == 0.0
    # 评审 01-S7：无数据必须显式标注，不能把 0% 当成"没人用"
    assert r["dataAvailable"] is False
    assert r["dataNote"] and "尚无" in r["dataNote"]


def test_report_marks_data_available_after_requests():
    consult_metrics.record("drug", "sync", "ok", 10.0)
    r = skill_usage.report()
    assert r["dataAvailable"] is True
    assert r["dataNote"] is None
    assert r["skills"]["items"] == []
    assert "重启归零" in r["note"]


def test_usage_ratio_and_items():
    _publish("drug-a", "药物核对 A")
    _publish("drug-b", "药物核对 B")
    consult_metrics.record("drug", "sync", "ok", 10.0, ["drug-a"])
    consult_metrics.record("drug", "stream", "ok", 20.0, ["drug-a"])

    r = skill_usage.report()
    assert r["skills"]["published"] == 2
    assert r["skills"]["used"] == 1
    assert r["skills"]["usageRatio"] == 0.5
    # 评审 M-26：字段名明确"进程内未使用"，避免与 skill_ops 的"未被 Bot 引用"混用
    assert r["skills"]["unusedInProcess"] == ["drug-b"]
    assert r["skills"]["usedNotPublishedInProcess"] == []
    # 评审 M-27：覆盖率近似无判别力，补集中度信号
    assert r["skills"]["skillReferences"] == 2
    assert r["skills"]["dominantShare"] == 1.0
    assert r["skills"]["concentration"] == 1.0

    by_code = {i["code"]: i for i in r["skills"]["items"]}
    assert by_code["drug-a"]["usage"] == 2
    assert by_code["drug-a"]["published"] is True
    assert by_code["drug-a"]["lastUsedAt"]
    assert by_code["drug-a"]["usageShare"] == 1.0
    assert by_code["drug-b"]["usage"] == 0
    assert by_code["drug-b"]["lastUsedAt"] is None


def test_used_but_not_published_is_separate():
    _publish("drug-a", "药物核对 A")
    consult_metrics.record("drug", "sync", "ok", 5.0, ["legacy-skill"])

    r = skill_usage.report()
    assert r["skills"]["usedNotPublishedInProcess"] == ["legacy-skill"]
    assert r["skills"]["used"] == 0
    item = next(i for i in r["skills"]["items"] if i["code"] == "legacy-skill")
    assert item["published"] is False
    assert item["name"] == "legacy-skill"


def test_request_and_duration_aggregation():
    consult_metrics.record("drug", "sync", "ok", 10.0,
                           tokens={"input_tokens": 10, "output_tokens": 5})
    consult_metrics.record("drug", "sync", "error", 30.0)
    consult_metrics.record("literature", "stream", "ok", 50.0)
    consult_metrics.record("literature", "stream", "aborted", 70.0)

    r = skill_usage.report()
    req = r["requests"]
    assert req["total"] == 4
    assert (req["ok"], req["error"], req["aborted"]) == (2, 1, 1)
    assert req["successRate"] == 0.5
    assert req["errorRate"] == 0.25
    assert req["abortedRate"] == 0.25
    assert req["channels"]["sync"] == {"total": 2, "ok": 1, "error": 1, "aborted": 0}
    assert req["channels"]["stream"] == {"total": 2, "ok": 1, "error": 0, "aborted": 1}
    assert req["agents"]["drug"]["total"] == 2
    assert req["agents"]["literature"]["total"] == 2

    assert r["duration"]["avgMs"] == 40.0
    assert r["duration"]["byChannel"]["sync"] == {"avgMs": 20.0, "count": 2}
    assert r["duration"]["byChannel"]["stream"] == {"avgMs": 60.0, "count": 2}
    assert r["tokens"] == {"input": 10, "output": 5, "total": 15,
                           "tokenizedRequests": 1, "coverageRatio": 0.5}


def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_usage_endpoint_role_guard(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = {"Authorization": "Bearer " + _login(c, "doctor01")}
    adm = {"Authorization": "Bearer " + _login(c, "admin01")}

    assert c.get("/api/v1/agent/skill-library/usage", headers=doc).status_code == 403
    r = c.get("/api/v1/agent/skill-library/usage", headers=adm)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["window"] == "process"
    # 评审 05-S12：端点返回值必须与 report() 一致（防止端点层参数错位后仍"绿"）
    assert body == skill_usage.report()
    assert body["requests"]["total"] == 0
    assert body["skills"]["usageRatio"] == 0.0
