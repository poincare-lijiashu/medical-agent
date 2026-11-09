"""G87 临床技能卡：校验、状态机、四眼审核、CAS、权限与运行时读取。"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.agents import skills
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


@pytest.fixture(autouse=True)
def _isolate_skills(tmp_path, monkeypatch):
    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "agent_skills.json"))


def payload(code: str = "sepsis-bundle", **over) -> dict:
    base = {
        "code": code,
        "name": "脓毒症集束化提醒",
        "description": "脓毒症早期识别与集束化处置的提示口径，供医师复核后使用",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明，不做推断。",
        "toolAllowlist": ["kb_search"],
        "evidenceRefs": ["Surviving Sepsis Campaign 2021"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }
    base.update(over)
    return base


def _created(code: str = "sepsis-bundle", actor: str = "admin01", **over) -> dict:
    return skills.create_skill(payload(code, **over), actor)


def test_create_defaults():
    item = _created()
    assert item["status"] == "DRAFT"
    assert item["version"] == 0 and item["rowVersion"] == 0
    assert item["createdBy"] == "admin01"
    assert item["guardrails"]["hitl"] is True


def test_disabled_can_be_reenabled_only_through_review():
    """评审 M-36：停用不是终态；重新启用回到 IN_REVIEW 且仍需四眼。"""
    item = _created()
    item = skills.submit_skill(item["code"], item["rowVersion"], "admin01")
    pub = skills.review_skill(item["code"], True, None, item["rowVersion"], "qc01")
    off = skills.disable_skill(pub["code"], pub["rowVersion"], "qc01")
    assert off["status"] == "DISABLED"

    with pytest.raises(skills.SkillConflict):
        skills.enable_skill(off["code"], off["rowVersion"] + 1, "admin01")

    back = skills.enable_skill(off["code"], off["rowVersion"], "admin01")
    assert back["status"] == "IN_REVIEW" and back["submittedBy"] == "admin01"
    with pytest.raises(skills.SkillForbidden):
        skills.review_skill(back["code"], True, None, back["rowVersion"], "admin01")

    pub2 = skills.review_skill(back["code"], True, None, back["rowVersion"], "qc01")
    assert pub2["status"] == "PUBLISHED" and pub2["version"] == 2


def test_publish_rejects_aggregate_prompt_over_budget():
    """评审 M-35：同一能力域的聚合提示超预算时，审核通过必须拒绝（而非发布后全线 422）。"""
    long_prompt = "按指南证据回答并逐条标注来源。" * 250  # ≈ 4000 字（单卡上限）
    assert len(long_prompt) <= 4000

    for i in range(3):
        item = _created(code=f"budget-{i}", promptTemplate=long_prompt)
        item = skills.submit_skill(item["code"], item["rowVersion"], "admin01")
        skills.review_skill(item["code"], True, None, item["rowVersion"], "qc01")

    last = _created(code="budget-3", promptTemplate=long_prompt)
    last = skills.submit_skill(last["code"], last["rowVersion"], "admin01")
    with pytest.raises(skills.SkillInvalid):
        skills.review_skill(last["code"], True, None, last["rowVersion"], "qc01")


def test_create_rejects_duplicate_code():
    _created()
    with pytest.raises(skills.SkillConflict):
        _created()


@pytest.mark.parametrize("override", [
    {"code": "Bad_Code"},
    {"agentId": "nope"},
    {"toolAllowlist": ["not_a_tool"]},
    {"toolAllowlist": ["drug_check"]},  # literature 未声明 drug_check
    {"promptTemplate": "太短"},
    {"evidenceRefs": []},
    {"guardrails": {"hitl": False}},  # literature 必须保留复核
    {"guardrails": {"maxInputChars": 50}},
    {"description": "短"},
])
def test_create_rejects_invalid_payload(override):
    with pytest.raises(skills.SkillInvalid):
        _created(**override)


def test_update_only_draft_and_cas():
    _created()
    updated = skills.update_skill("sepsis-bundle",
                                  payload(name="脓毒症集束化提醒（修订）"), 0, "admin01")
    assert updated["name"].endswith("（修订）")
    assert updated["rowVersion"] == 1

    with pytest.raises(skills.SkillConflict):
        skills.update_skill("sepsis-bundle", payload(), 0, "admin01")  # 旧版本

    with pytest.raises(skills.SkillInvalid):
        skills.update_skill("sepsis-bundle", payload(code="other-code"), 1, "admin01")


def test_submit_moves_to_review_and_blocks_second_submit():
    _created()
    item = skills.submit_skill("sepsis-bundle", 0, "admin01")
    assert item["status"] == "IN_REVIEW" and item["submittedBy"] == "admin01"
    with pytest.raises(skills.SkillConflict):
        skills.submit_skill("sepsis-bundle", item["rowVersion"], "admin01")


def test_review_four_eyes_rejects_creator():
    _created(actor="admin01")
    skills.submit_skill("sepsis-bundle", 0, "admin01")
    with pytest.raises(skills.SkillForbidden):
        skills.review_skill("sepsis-bundle", True, None, 1, "admin01")


def test_review_approve_publishes_and_freezes():
    _created(actor="admin01")
    skills.submit_skill("sepsis-bundle", 0, "admin01")
    item = skills.review_skill("sepsis-bundle", True, "已核对指南", 1, "qc01")
    assert item["status"] == "PUBLISHED" and item["version"] == 1
    assert item["reviewedBy"] == "qc01"
    with pytest.raises(skills.SkillConflict):
        skills.update_skill("sepsis-bundle", payload(), item["rowVersion"], "admin01")


def test_review_reject_requires_note_then_resubmit():
    _created(actor="admin01")
    skills.submit_skill("sepsis-bundle", 0, "admin01")
    with pytest.raises(skills.SkillInvalid):
        skills.review_skill("sepsis-bundle", False, "  ", 1, "qc01")
    rejected = skills.review_skill("sepsis-bundle", False, "证据引用不足", 1, "qc01")
    assert rejected["status"] == "REJECTED"
    edited = skills.update_skill("sepsis-bundle",
                                 payload(evidenceRefs=["院内脓毒症处置制度 v3"]),
                                 rejected["rowVersion"], "admin01")
    again = skills.submit_skill("sepsis-bundle", edited["rowVersion"], "admin01")
    assert again["status"] == "IN_REVIEW"


def test_disable_only_published():
    _created(actor="admin01")
    with pytest.raises(skills.SkillConflict):
        skills.disable_skill("sepsis-bundle", 0, "qc01")

    skills.submit_skill("sepsis-bundle", 0, "admin01")
    pub = skills.review_skill("sepsis-bundle", True, None, 1, "qc01")
    item = skills.disable_skill("sepsis-bundle", pub["rowVersion"], "qc01")
    assert item["status"] == "DISABLED"


def test_list_visibility_and_filters():
    _created("draft-one")
    _created("pub-one")
    skills.submit_skill("pub-one", 0, "admin01")
    skills.review_skill("pub-one", True, None, 1, "qc01")

    doctor = skills.list_skills("doctor")
    assert [i["code"] for i in doctor] == ["pub-one"]

    admin = skills.list_skills("admin")
    assert {i["code"] for i in admin} == {"draft-one", "pub-one"}
    assert [i["code"] for i in skills.list_skills("admin", status="DRAFT")] == ["draft-one"]
    # 评审 05-S2：删掉 `or len(...) == 2` 兜底——此前右半段恒真，左半段的断言失败也不会报错。
    # 列表顺序不是接口契约（按 updatedAt 倒序，受写入时序影响），因此断言集合而非顺序。
    assert {i["code"] for i in skills.list_skills("admin", agent_id="literature")} == \
        {"draft-one", "pub-one"}

    with pytest.raises(skills.SkillNotFound):
        skills.get_skill("draft-one", "doctor")


def test_list_filters_by_agent_roles_for_normal_roles():
    """评审 03-S10：普通角色只看自己能力域的已发布技能卡（与 catalog 同口径）。"""
    lit = _created("lit-visible")
    skills.submit_skill("lit-visible", lit["rowVersion"], "admin01")
    skills.review_skill("lit-visible", True, None, 1, "qc01")
    img = _created("img-hidden", agentId="imaging", name="影像结构化所见",
                   description="影像结构化描述技能卡的说明文本，供医师复核后使用。")
    skills.submit_skill("img-hidden", img["rowVersion"], "admin01")
    skills.review_skill("img-hidden", True, None, 1, "qc01")

    assert {s["code"] for s in skills.list_skills("pharmacist")} == {"lit-visible"}
    assert {s["code"] for s in skills.list_skills("doctor")} == {"lit-visible", "img-hidden"}
    admin = {s["code"] for s in skills.list_skills("admin")}
    assert {"lit-visible", "img-hidden"} <= admin


def test_published_for_agent():
    _created("pub-one")
    skills.submit_skill("pub-one", 0, "admin01")
    skills.review_skill("pub-one", True, None, 1, "qc01")
    assert [i["code"] for i in skills.published_for_agent("literature")] == ["pub-one"]
    assert skills.published_for_agent("drug") == []


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_api_requires_role_and_four_eyes(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    adm = _h(_login(c, "admin01"))
    qc = _h(_login(c, "qc01"))

    assert c.post("/api/v1/agent/skills", headers=doc, json=payload()).status_code == 403

    r = c.post("/api/v1/agent/skills", headers=adm, json=payload())
    assert r.status_code == 200, r.text
    item = r.json()
    assert item["status"] == "DRAFT"

    # doctor 看不到草稿
    assert c.get("/api/v1/agent/skills", headers=doc).json()["skills"] == []
    assert c.get("/api/v1/agent/skills", headers=adm).json()["skills"][0]["code"] == "sepsis-bundle"

    r = c.post("/api/v1/agent/skills/sepsis-bundle/submit", headers=adm,
               json={"rowVersion": item["rowVersion"]})
    assert r.status_code == 200 and r.json()["status"] == "IN_REVIEW"
    row = r.json()["rowVersion"]

    # 创建人自审 → 403
    r = c.post("/api/v1/agent/skills/sepsis-bundle/review", headers=adm,
               json={"approve": True, "rowVersion": row})
    assert r.status_code == 403

    # qc 审核通过 → 医生可见
    r = c.post("/api/v1/agent/skills/sepsis-bundle/review", headers=qc,
               json={"approve": True, "note": "ok", "rowVersion": row})
    assert r.status_code == 200 and r.json()["status"] == "PUBLISHED"
    assert c.get("/api/v1/agent/skills", headers=doc).json()["skills"][0]["version"] == 1


def test_api_validation_and_not_found(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    adm = _h(_login(c, "admin01"))
    assert c.post("/api/v1/agent/skills", headers=adm,
                  json=payload(evidenceRefs=[])).status_code == 422
    assert c.post("/api/v1/agent/skills", headers=adm,
                  json=payload(code="dup-code")).status_code == 200
    assert c.post("/api/v1/agent/skills", headers=adm,
                  json=payload(code="dup-code")).status_code == 409
    assert c.get("/api/v1/agent/skills/nope", headers=adm).status_code == 404
