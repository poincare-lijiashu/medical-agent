"""评测 04 P1-2：概览活动流按角色裁剪（横向越权修复）。

此前 `GET /medical/overview` 对**任何**登录用户返回全院最近 8 条审计事件
（操作者 + 完整 payload，可能含患者姓名）。契约改为：
- admin / qc（院级质控角色）→ 全院事件 + 完整 payload；
- 其余角色（doctor/pharmacist/nurse/guest 等）→ **仅本人**事件，payload 只保留 action。
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from backend.core.auth import create_access_token, seed_default_users
from backend.core.medical_audit import get_audit_logger
from backend.main import app


def _client() -> TestClient:
    seed_default_users()
    return TestClient(app)


def _bearer(username: str, role: str) -> dict:
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def _seed_events() -> None:
    al = get_audit_logger()
    al.write(event_type="review", action="resolved_approved", actor="admin01",
             payload={"patient": "张三", "rid": "r-admin"})
    al.write(event_type="prescription", action="prescribed", actor="doctor01",
             payload={"patient": "李四", "rid": "r-doc"})


def test_doctor_only_sees_own_events_without_patient_fields():
    c = _client()
    _seed_events()
    d = c.get("/api/v1/medical/overview", headers=_bearer("doctor01", "doctor")).json()
    assert d["recent"], "至少应返回本人的事件"
    assert {e["actor"] for e in d["recent"]} == {"doctor01"}
    assert all("patient" not in (e.get("payload") or {}) for e in d["recent"])
    # 人话渲染仍需要 event/action
    assert all(e["event"] and e["ts"] for e in d["recent"])


def test_pharmacist_is_scoped_to_self():
    c = _client()
    _seed_events()
    d = c.get("/api/v1/medical/overview", headers=_bearer("pharm01", "pharmacist")).json()
    assert all(e["actor"] == "pharm01" for e in d["recent"])
    if d["recent"]:
        assert "patient" not in (d["recent"][0].get("payload") or {})


def test_nurse_role_is_scoped_to_self():
    """护士是 Java 平台映射出的只读角色（本地用户表无此账号），用 helper 覆盖同一分支。"""
    from backend.api.v1.medical.admin import _overview_events

    al = get_audit_logger()
    al.write(event_type="review", action="x", actor="nurse01", payload={"patient": "王五"})
    al.write(event_type="review", action="y", actor="doctor01", payload={"patient": "李四"})
    rows = _overview_events(al, {"username": "nurse01", "role": "nurse"})
    assert [r["actor"] for r in rows] == ["nurse01"]
    assert rows[0]["payload"] == {"action": "x"}


def test_admin_and_qc_see_hospital_wide_events_with_payload():
    for username, role in (("admin01", "admin"), ("qc01", "qc")):
        c = _client()
        _seed_events()
        d = c.get("/api/v1/medical/overview", headers=_bearer(username, role)).json()
        actors = {e["actor"] for e in d["recent"]}
        assert {"admin01", "doctor01"} <= actors, f"{role} 应看到全院事件"
        assert any((e.get("payload") or {}).get("patient") for e in d["recent"]), \
            "院级角色应保留完整 payload（审计需要）"


def test_helper_caps_to_eight_newest_first():
    from backend.api.v1.medical.admin import _overview_events

    al = get_audit_logger()
    for i in range(10):
        al.write(event_type="review", action=f"a{i}", actor="doctor01", payload={"i": i})
    rows = _overview_events(al, {"username": "doctor01", "role": "doctor"})
    assert len(rows) == 8
    assert rows[0]["action"] == "a9"  # 新→旧
    assert all(r["actor"] == "doctor01" for r in rows)
