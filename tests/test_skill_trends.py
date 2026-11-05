"""G94 运营趋势与告警：快照幂等、历史不足、差值、阈值告警与 API。"""
from __future__ import annotations

from datetime import date

import pytest
from fastapi.testclient import TestClient

from backend.agents import skill_library, skill_ops, skill_trends, skills
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
        "description": "用于测试审核通过率的技能卡说明文本",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["指南 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")


def test_snapshot_is_idempotent_per_day():
    skill_trends.snapshot("qc01", date(2026, 11, 1))
    skill_trends.snapshot("admin01", date(2026, 11, 1))
    snaps = skill_trends.list_snapshots(30)
    assert len(snaps) == 1
    assert snaps[0]["date"] == "2026-11-01"
    assert snaps[0]["generatedBy"] == "admin01"


def test_trends_insufficient_history():
    out = skill_trends.trends()
    assert out["sufficient"] is False
    assert out["deltas"] is None
    assert "不足" in out["note"]

    skill_trends.snapshot("qc01", date(2026, 11, 1))
    out = skill_trends.trends()
    assert out["sufficient"] is False


def test_trends_deltas_between_days():
    skill_trends.snapshot("qc01", date(2026, 11, 1))
    _exp("模式一甲")
    _exp("模式一甲")
    skill_library.distill("qc01")
    _publish_skill("trend-skill", "趋势技能")
    skill_trends.snapshot("qc01", date(2026, 11, 2))

    out = skill_trends.trends()
    assert out["sufficient"] is True
    assert out["deltas"]["experiences"] == 2
    assert out["deltas"]["proposals"] == 1
    assert out["deltas"]["published"] == 1


def test_alerts_empty():
    out = skill_trends.alerts()
    assert out["alerts"] == []
    assert out["levelCounts"] == {"high": 0, "medium": 0, "low": 0}


def test_alerts_adoption_and_unused_thresholds():
    # 3 个候选全部未采纳 → 采纳率 0
    for i in range(3):
        _exp(f"模式{i}A")
        _exp(f"模式{i}A")
    skill_library.distill("qc01")
    # 3 个生效技能未被任何 Bot 引用
    for i in range(3):
        _publish_skill(f"unused-{i}", f"未引用技能{i}")
    out = skill_trends.alerts()
    codes = {a["code"] for a in out["alerts"]}
    assert "ADOPTION_RATE_LOW" in codes
    assert "UNUSED_PUBLISHED_SKILLS" in codes
    assert out["levelCounts"]["medium"] >= 2


def test_alerts_replay_drop_from_snapshots(monkeypatch):
    base = {"experiences": {"total": 0, "coverageRate": 0},
            "proposals": {"total": 0, "proposed": 0, "replayChecked": 0,
                          "replayPassRate": 0, "adoptionRate": 0},
            "skillCards": {"published": 0, "unusedPublished": [],
                           "reviewPassRate": 0}}
    older = {"date": "2026-11-01", "metrics": {**base,
             "proposals": {**base["proposals"], "replayPassRate": 1.0}}}
    newer = {"date": "2026-11-02", "metrics": {**base,
             "proposals": {**base["proposals"], "replayPassRate": 0.5}}}
    monkeypatch.setattr(skill_trends, "list_snapshots", lambda days=2: [older, newer])
    monkeypatch.setattr(skill_ops, "metrics", lambda: base)
    out = skill_trends.alerts()
    assert any(a["code"] == "REPLAY_PASS_RATE_DROP" and a["level"] == "high"
               for a in out["alerts"])


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


def test_api_snapshot_trends_alerts(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = _h(_login(c, "doctor01"))
    qc = _h(_login(c, "qc01"))

    assert c.post("/api/v1/agent/skill-library/snapshot", headers=doc, json={}).status_code == 403
    r = c.post("/api/v1/agent/skill-library/snapshot", headers=qc,
               json={"date": "2026-11-01"})
    assert r.status_code == 200 and r.json()["date"] == "2026-11-01"
    assert c.post("/api/v1/agent/skill-library/snapshot", headers=qc,
                  json={"date": "2026-13-01"}).status_code == 422

    trends = c.get("/api/v1/agent/skill-library/trends", headers=qc)
    assert trends.status_code == 200 and trends.json()["sufficient"] is False
    alerts = c.get("/api/v1/agent/skill-library/alerts", headers=qc)
    assert alerts.status_code == 200 and "alerts" in alerts.json()
    actions = {(e.get("payload") or {}).get("action") for e in audit.entries}
    assert "skill_metrics_snapshot" in actions
