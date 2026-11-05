"""G93 技能库运营指标与下线影响面。"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.agents import (bot_memory, bot_teams, bots, skill_library, skill_ops,
                            skills)
from backend.core.auth import seed_default_users
from backend.main import app

PATTERN = "二甲双胍是否一线治疗"


def _exp() -> dict:
    return skill_library.create_experience({
        "agentId": "literature",
        "title": "二甲双胍一线治疗经验",
        "pattern": PATTERN,
        "summary": "结合指南证据评估二甲双胍作为一线降糖药的适用性，并标注证据等级。",
        "sourceRef": "复核单 R-2026-001（已脱敏）",
        "deidentified": True,
        "toolAllowlist": ["kb_search"],
        "evidenceRefs": ["ADA Standards of Care 2026"],
        "contraindications": ["eGFR 极低时需调整方案"],
    }, "qc01")


def _published_library_skill() -> str:
    _exp()
    _exp()
    proposal = skill_library.distill("qc01")[0]
    result = skill_library.adopt(proposal["id"], "admin01")
    code = result["skill"]["code"]
    item = skills.submit_skill(code, 0, "admin01")
    skills.review_skill(code, True, None, item["rowVersion"], "qc01")
    return code


def _bot(code: str, skill_code: str) -> dict:
    return bots.create_bot({
        "code": code, "name": "运营 Bot", "agentId": "literature",
        "persona": "你是循证医学助手，回答必须标注证据来源与不确定性，不做诊断结论。",
        "modelProvider": "default", "modelId": None,
        "skillCodes": [skill_code], "toolAllowlist": ["kb_search"],
        "memoryScope": "bot",
    }, "admin01")


def _publish_bot(code: str) -> dict:
    item = bots.get_bot(code, "admin")
    item = bots.submit_bot(code, item["rowVersion"], "admin01")
    return bots.review_bot(code, True, None, item["rowVersion"], "qc01")


def test_metrics_empty_store():
    m = skill_ops.metrics()
    assert m["experiences"]["total"] == 0
    assert m["proposals"]["total"] == 0
    assert m["skillCards"]["total"] == 0
    assert m["experiences"]["coverageRate"] == 0.0
    assert m["proposals"]["replayPassRate"] == 0.0


def test_metrics_rates_and_references():
    code = _published_library_skill()
    _bot("ops-a", code)
    _bot("ops-b", code)
    m = skill_ops.metrics()
    assert m["experiences"]["total"] == 2
    assert m["experiences"]["coveredByProposal"] == 2
    assert m["experiences"]["coverageRate"] == 1.0
    assert m["proposals"]["adopted"] == 1
    assert m["proposals"]["adoptionRate"] == 1.0
    assert m["proposals"]["replayChecked"] == 1
    assert m["proposals"]["replayPassRate"] == 1.0
    assert m["skillCards"]["published"] == 1
    assert m["skillCards"]["reviewPassRate"] == 1.0
    assert m["skillCards"]["referencedByBots"] == 1
    assert m["skillCards"]["unusedPublished"] == []
    assert m["bots"]["total"] == 2


def test_skill_impact_lists_bots_teams_and_proposals():
    code = _published_library_skill()
    a = _bot("ops-a", code)
    b = _bot("ops-b", code)
    _publish_bot("ops-a")
    _publish_bot("ops-b")
    team = bot_teams.create_team({"code": "ops-team", "name": "运营团队",
                                  "description": "", "botCodes": ["ops-a", "ops-b"]},
                                 "admin01")
    assert team["botCodes"] == ["ops-a", "ops-b"]

    impact = skill_ops.skill_impact(code)
    assert impact["status"] == "PUBLISHED"
    assert {x["code"] for x in impact["bots"]} == {"ops-a", "ops-b"}
    assert [x["code"] for x in impact["teams"]] == ["ops-team"]
    assert impact["proposals"][0]["status"] == "ADOPTED"
    assert impact["warning"] and "2 个 Bot" in impact["warning"]


def test_bot_impact_memories_teams_and_skill_status():
    code = _published_library_skill()
    _bot("ops-a", code)
    _bot("ops-b", code)
    _publish_bot("ops-a")
    _publish_bot("ops-b")
    bot_teams.create_team({"code": "ops-team", "name": "运营团队",
                           "description": "", "botCodes": ["ops-a", "ops-b"]}, "admin01")
    mem = bot_memory.create_memory({"botCode": "ops-a", "key": "kk",
                                    "value": "科室偏好", "source": "科室"}, "admin01")
    bot_memory.confirm_memory(mem["id"], "admin01", "admin")

    impact = skill_ops.bot_impact("ops-a")
    assert impact["status"] == "PUBLISHED"
    assert [t["code"] for t in impact["teams"]] == ["ops-team"]
    assert impact["memories"] == {"total": 1, "confirmed": 1, "candidate": 0}
    assert impact["skillCards"] == [{"code": code, "status": "PUBLISHED"}]
    assert impact["warning"] and "1 个团队" in impact["warning"]


def test_impact_not_found():
    with pytest.raises(skills.SkillNotFound):
        skill_ops.skill_impact("nope")
    with pytest.raises(bots.BotNotFound):
        skill_ops.bot_impact("nope")


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
    from backend.core.medical_audit import AuditLog
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_api_metrics_and_impact_permissions(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    qc = _h(_login(c, "qc01"))

    assert c.get("/api/v1/agent/skill-library/metrics", headers=doc).status_code == 403
    r = c.get("/api/v1/agent/skill-library/metrics", headers=qc)
    assert r.status_code == 200 and "experiences" in r.json()

    code = _published_library_skill()
    _bot("ops-a", code)
    r = c.get(f"/api/v1/agent/skills/{code}/impact", headers=qc)
    assert r.status_code == 200 and r.json()["bots"][0]["code"] == "ops-a"
    r = c.get("/api/v1/agent/bots/ops-a/impact", headers=qc)
    assert r.status_code == 200 and r.json()["botCode"] == "ops-a"
    assert c.get("/api/v1/agent/bots/nope/impact", headers=qc).status_code == 404
