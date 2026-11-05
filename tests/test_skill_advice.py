"""G95 运营建议：告警转待办、去重、忽略/完成、不自动修改与 API。"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.agents import skill_advice, skill_library, skills
from backend.core.auth import seed_default_users
from backend.main import app


def _exp(pattern: str) -> dict:
    return skill_library.create_experience({
        "agentId": "literature",
        "title": "经验标题",
        "pattern": pattern,
        "summary": "结合指南证据评估处置要点，并标注证据等级与适用边界。",
        "sourceRef": "复核单 R-2026-001（已脱敏）",
        "deidentified": True,
        "toolAllowlist": ["kb_search"],
        "evidenceRefs": ["ADA Standards of Care 2026"],
        "contraindications": ["eGFR 极低时需调整方案"],
    }, "qc01")


def _publish_skill(code: str, name: str) -> None:
    card = skills.create_skill({
        "code": code, "name": name,
        "description": "用于测试运营建议的技能卡说明文本",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["指南 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")


def _proposals(n: int) -> None:
    for i in range(n):
        _exp(f"模式{i}A")
        _exp(f"模式{i}A")
    skill_library.distill("qc01")


def test_generate_unused_skill_advices_and_dedupe():
    for i in range(3):
        _publish_skill(f"unused-{i}", f"未引用技能{i}")
    items = skill_advice.generate("qc01")
    unused = [i for i in items if i["code"] == "UNUSED_SKILL"]
    assert len(unused) == 3
    assert {i["targetId"] for i in unused} == {"unused-0", "unused-1", "unused-2"}
    # 再次生成不重复
    again = skill_advice.generate("qc01")
    assert len([i for i in again if i["code"] == "UNUSED_SKILL"]) == 3


def test_generate_proposal_review_advices():
    _proposals(3)
    items = skill_advice.generate("qc01")
    reviews = [i for i in items if i["code"] == "PROPOSAL_REVIEW"]
    assert len(reviews) == 3
    assert all(i["targetType"] == "PROPOSAL" and i["targetId"] for i in reviews)


def test_dismiss_requires_note_and_is_terminal():
    for i in range(3):
        _publish_skill(f"unused-x{i}", f"未引用技能X{i}")
    advice = [i for i in skill_advice.generate("qc01") if i["code"] == "UNUSED_SKILL"][0]
    with pytest.raises(skill_advice.AdviceInvalid):
        skill_advice.dismiss(advice["id"], "  ", "qc01")
    item = skill_advice.dismiss(advice["id"], "已确认由科室自行维护", "qc01")
    assert item["status"] == "DISMISSED" and item["note"] == "已确认由科室自行维护"
    with pytest.raises(skill_advice.AdviceConflict):
        skill_advice.complete(advice["id"], None, "qc01")


def test_complete_and_list_filter():
    for i in range(3):
        _publish_skill(f"unused-y{i}", f"未引用技能Y{i}")
    advice = [i for i in skill_advice.generate("qc01") if i["code"] == "UNUSED_SKILL"][0]
    assert skill_advice.complete(advice["id"], "已绑定到 Bot", "qc01")["status"] == "DONE"
    assert len([i for i in skill_advice.list_advices("qc", status="OPEN")
                if i["code"] == "UNUSED_SKILL"]) == 2
    assert len(skill_advice.list_advices("qc", status="DONE")) == 1


def test_generate_does_not_modify_skills():
    for i in range(3):
        _publish_skill(f"keep-{i}", f"保留技能{i}")
    before = {c["code"]: c["status"] for c in skills.list_skills("admin")}
    items = skill_advice.generate("qc01")
    for item in items:
        if item["status"] == "OPEN":
            skill_advice.complete(item["id"], None, "qc01")
    after = {c["code"]: c["status"] for c in skills.list_skills("admin")}
    assert before == after
    assert all(v == "PUBLISHED" for v in after.values())


def test_advice_id_is_deterministic_and_state_preserved():
    import hashlib
    for i in range(3):
        _publish_skill(f"stable-{i}", f"稳定技能{i}")
    items = [i for i in skill_advice.generate("qc01") if i["code"] == "UNUSED_SKILL"]
    first = items[0]
    assert first["id"] == hashlib.sha1(first["dedupeKey"].encode("utf-8")).hexdigest()[:32]
    skill_advice.dismiss(first["id"], "已知晓", "qc01")
    again = [i for i in skill_advice.generate("qc01") if i["id"] == first["id"]][0]
    assert again["status"] == "DISMISSED" and again["note"] == "已知晓"


def test_visibility_requires_privileged_role():
    with pytest.raises(skill_advice.AdviceConflict):
        skill_advice.list_advices("doctor")


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


def test_api_generate_dismiss_complete(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    qc = _h(_login(c, "qc01"))

    assert c.post("/api/v1/agent/skill-advices/generate", headers=doc).status_code == 403

    for i in range(3):
        _publish_skill(f"api-unused-{i}", f"接口未引用技能{i}")
    r = c.post("/api/v1/agent/skill-advices/generate", headers=qc)
    assert r.status_code == 200
    items = [i for i in r.json()["advices"] if i["code"] == "UNUSED_SKILL"]
    assert len(items) == 3

    assert c.post(f"/api/v1/agent/skill-advices/{items[0]['id']}/dismiss",
                  headers=qc, json={}).status_code == 422
    assert c.post(f"/api/v1/agent/skill-advices/{items[0]['id']}/dismiss",
                  headers=qc, json={"note": "暂不处理"}).status_code == 200
    assert c.post(f"/api/v1/agent/skill-advices/{items[1]['id']}/complete",
                  headers=qc, json={"note": "已绑定"}).status_code == 200

    listed = c.get("/api/v1/agent/skill-advices?status=OPEN", headers=qc).json()["advices"]
    assert [i["id"] for i in listed] == [items[2]["id"]]
    actions = {(e.get("payload") or {}).get("action") for e in audit.entries}
    assert {"skill_advice_generate", "skill_advice_dismiss", "skill_advice_complete"} <= actions
