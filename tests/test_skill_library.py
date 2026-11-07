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


def test_experience_records_its_backer():
    """评审 01-S11：经验没有独立第二人复核，但必须记录"谁背书"（录入人 + 时间）。"""
    item = _create()
    assert item["status"] == "APPROVED"
    assert item["approvedBy"] == "qc01"
    assert item["approvedAt"]


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

    # 重复提炼不产生重复候选：distill 只返回本次新建（此处应为空）
    assert skill_library.distill("qc01") == []


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


def test_replay_rejects_partially_routed_proposal():
    """评审 05-S7：同一候选里"部分经验路由到别的域"必须整体不通过。

    两条经验归一化后同模式（`_norm` 会去掉连字符），因此被合并进同一个候选；
    但原始措辞一条含 "x线"（命中 imaging）、一条含 "x-线"（不命中、回落 literature），
    回放必须逐条判定并因为存在失败项而整体拒绝。
    """
    _create(pattern="胸部X线检查如何判读", agent="imaging")
    _create(pattern="胸部X-线检查如何判读", agent="imaging")
    proposal = skill_library.distill("qc01")[0]
    assert len(proposal["experienceIds"]) == 2

    result = skill_library.adopt(proposal["id"], "admin01")
    replay = result["proposal"]["replay"]
    assert replay["passed"] is False
    assert result["proposal"]["status"] == "REJECTED"
    assert result["skill"] is None
    assert [c["passed"] for c in replay["cases"]] == [True, False]
    assert replay["cases"][1]["actualAgent"] == "literature"


def test_replay_rejects_when_experiences_are_gone():
    """评审 05-S7：经验被删除后 cases 为空，不得因"没有失败项"而放行。"""
    _create()
    _create()
    proposal = skill_library.distill("qc01")[0]
    skill_library._save_experiences([])  # 模拟经验被清理

    result = skill_library.adopt(proposal["id"], "admin01")
    assert result["proposal"]["replay"]["cases"] == []
    assert result["proposal"]["replay"]["passed"] is False
    assert result["proposal"]["status"] == "REJECTED"
    assert result["skill"] is None


def test_distill_skips_pattern_whose_skill_code_already_exists():
    """评审 01-S12：同模式技能卡已存在时不再重复提炼（否则采纳必撞编码）。"""
    _create()
    _create()
    proposal = skill_library.distill("qc01")[0]
    skills.create_skill({
        "code": proposal["code"], "name": "同模式历史技能",
        "description": "用于验证同一问题模式不会重复生成候选的技能卡说明文本。",
        "agentId": proposal["agentId"],
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["指南 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    skill_library.reject(proposal["id"], "证据不足", "qc01")

    codes = {s.get("code") for s in skills.list_skills("admin")}
    assert proposal["code"] in codes, (proposal["code"], codes)
    # distill 只返回本次新建；同 code 技能卡已存在 → 不新建
    assert skill_library.distill("qc01") == []


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
