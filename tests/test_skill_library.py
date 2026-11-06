"""G92 技能库：经验录入校验、确定性提炼、回放准入、草稿生成与 API。"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from backend.agents import skill_library, skills
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

PATTERN = "二甲双胍是否一线治疗"


def exp(pattern: str = PATTERN, agent: str = "literature", **over) -> dict:
    base = {
        "agentId": agent,
        "title": "二甲双胍一线治疗经验",
        "pattern": pattern,
        "summary": "结合指南证据评估二甲双胍作为一线降糖药的适用性，并标注证据等级。",
        "sourceRef": "复核单 R-2026-001（已脱敏）",
        "deidentified": True,
        "toolAllowlist": ["kb_search"],
        "evidenceRefs": ["ADA Standards of Care 2026"],
        "contraindications": ["eGFR 极低时需调整方案"],
    }
    base.update(over)
    return base


def _create(pattern: str = PATTERN, **over) -> dict:
    return skill_library.create_experience(exp(pattern, **over), "qc01")


@pytest.mark.parametrize("override", [
    {"deidentified": False},
    {"agentId": "nope"},
    {"summary": "太短"},
    {"evidenceRefs": []},
    {"contraindications": []},
    {"toolAllowlist": ["drug_check"]},          # literature 未声明
    {"toolAllowlist": ["nope"]},
    {"pattern": "电话13800138000"},
    {"sourceRef": "身份证110101199003071234"},
])
def test_experience_validation(override):
    with pytest.raises(skill_library.LibraryInvalid):
        skill_library.create_experience(exp(**override), "qc01")


def test_distill_requires_two_experiences():
    _create()
    assert skill_library.distill("qc01") == []
    _create()
    proposals = skill_library.distill("qc01")
    assert len(proposals) == 1
    p = proposals[0]
    assert p["agentId"] == "literature" and len(p["experienceIds"]) == 2
    assert p["status"] == "PROPOSED"
    assert p["evidenceRefs"] and p["toolAllowlist"] == ["kb_search"]

    # 重复提炼不产生重复候选
    assert len(skill_library.distill("qc01")) == 1


def test_adopt_passes_replay_and_creates_draft():
    _create()
    _create()
    proposal = skill_library.distill("qc01")[0]
    result = skill_library.adopt(proposal["id"], "admin01")
    assert result["proposal"]["status"] == "ADOPTED"
    assert result["proposal"]["replay"]["passed"] is True
    card = result["skill"]
    assert card["status"] == "DRAFT"
    assert card["agentId"] == "literature"
    assert skills.get_skill(card["code"], "admin")["status"] == "DRAFT"

    with pytest.raises(skill_library.LibraryConflict):
        skill_library.adopt(proposal["id"], "admin01")


def test_adopt_replay_failure_does_not_create_skill():
    # 药物相互作用模式 → 路由到 drug，但经验声称 literature → 回放失败
    _create(pattern="华法林与阿司匹林相互作用")
    _create(pattern="华法林与阿司匹林相互作用")
    proposal = skill_library.distill("qc01")[0]
    result = skill_library.adopt(proposal["id"], "admin01")
    assert result["proposal"]["status"] == "REJECTED"
    assert result["proposal"]["replay"]["passed"] is False
    assert result["skill"] is None
    assert result["proposal"]["replay"]["cases"][0]["actualAgent"] == "drug"


def test_reject_requires_reason_and_is_terminal():
    _create()
    _create()
    proposal = skill_library.distill("qc01")[0]
    with pytest.raises(skill_library.LibraryInvalid):
        skill_library.reject(proposal["id"], "  ", "qc01")
    item = skill_library.reject(proposal["id"], "证据不足", "qc01")
    assert item["status"] == "REJECTED" and item["decisionNote"] == "证据不足"
    with pytest.raises(skill_library.LibraryConflict):
        skill_library.reject(proposal["id"], "again", "qc01")


def test_visibility_requires_privileged_role():
    _create()
    for fn, args in ((skill_library.list_experiences, ("doctor",)),
                     (skill_library.list_proposals, ("doctor",))):
        with pytest.raises(skill_library.LibraryConflict):
            fn(*args)


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
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


def test_api_experience_distill_adopt(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    qc = _h(_login(c, "qc01"))
    adm = _h(_login(c, "admin01"))

    assert c.post("/api/v1/agent/skill-experiences", headers=doc, json=exp()).status_code == 403
    for _ in range(2):
        r = c.post("/api/v1/agent/skill-experiences", headers=qc, json=exp())
        assert r.status_code == 200, r.text
    assert len(c.get("/api/v1/agent/skill-experiences", headers=qc).json()["experiences"]) == 2

    r = c.post("/api/v1/agent/skill-proposals/distill", headers=qc)
    assert r.status_code == 200
    proposals = r.json()["proposals"]
    assert len(proposals) == 1
    pid = proposals[0]["id"]

    r = c.post(f"/api/v1/agent/skill-proposals/{pid}/adopt", headers=adm)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["proposal"]["status"] == "ADOPTED"
    assert body["skill"]["status"] == "DRAFT"

    actions = {(e.get("payload") or {}).get("action") for e in audit.entries}
    assert {"skill_experience_create", "skill_distill", "skill_adopt"} <= actions


def test_api_reject_audit_keeps_length_not_note(monkeypatch, tmp_path):
    """评审 M-13：审计只记录 note 长度，自由文本（可能含 PHI）不落追加式日志。"""
    c, audit = _client(monkeypatch, tmp_path)
    qc = _h(_login(c, "qc01"))
    for _ in range(2):
        assert c.post("/api/v1/agent/skill-experiences", headers=qc,
                      json=exp()).status_code == 200
    pid = c.post("/api/v1/agent/skill-proposals/distill",
                 headers=qc).json()["proposals"][0]["id"]

    note = "患者 张三 住院号 A123456 证据不足"
    r = c.post(f"/api/v1/agent/skill-proposals/{pid}/reject",
               headers=qc, json={"note": note})
    assert r.status_code == 200, r.text

    dumped = json.dumps([e for e in audit.entries], ensure_ascii=False)
    assert "A123456" not in dumped and "张三" not in dumped
    payloads = [(e.get("payload") or {}) for e in audit.entries
                if (e.get("payload") or {}).get("action") == "skill_proposal_reject"]
    assert payloads and payloads[0].get("noteChars") == len(note)
