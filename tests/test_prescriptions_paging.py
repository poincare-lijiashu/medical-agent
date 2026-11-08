"""第 75 批：处方列表分页/计数（`/prescriptions/mine|pending|reviewed-by-me`）。

动机：医生端侧栏角标每 60s 调 `/prescriptions/mine` 只为统计 rejected 数，而处方随使用
年限无界增长；`/prescriptions/pending` 同样被角标整表拉取。本批新增 `limit/offset/total`，
`mine` 追加 `status` 过滤与 `summary=1`（只回计数），**缺省仍是全量**（旧调用方零影响）。
"""
from fastapi.testclient import TestClient

from backend.core import prescriptions as rx
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

MINE = [
    {"id": "m1", "doctor": "doctor01", "status": "pending_pharm", "case_text": "病例1",
     "drugs": [{"name": "华法林"}]},
    {"id": "m2", "doctor": "doctor01", "status": "approved", "case_text": "病例2", "drugs": []},
    {"id": "m3", "doctor": "doctor01", "status": "rejected", "case_text": "病例3", "drugs": []},
]
PENDING = [{"id": "p1", "status": "pending_pharm"}, {"id": "p2", "status": "pending_pharm"}]
REVIEWED = [{"id": "r1", "status": "approved", "pharm_reviewer": "pharm01"},
            {"id": "r2", "status": "rejected", "pharm_reviewer": "pharm01"},
            {"id": "r3", "status": "approved", "pharm_reviewer": "pharm01"}]


def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(rx, "list_mine", lambda me: [dict(i) for i in MINE])
    monkeypatch.setattr(rx, "list_pending", lambda: [dict(i) for i in PENDING])
    monkeypatch.setattr(rx, "list_reviewed_by", lambda me: [dict(i) for i in REVIEWED])
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def _ids(resp):
    assert resp.status_code == 200, resp.text
    return [i["id"] for i in resp.json()["items"]]


def test_mine_default_full_unchanged(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/prescriptions/mine", headers=_h("doctor01", "doctor"))
    assert _ids(r) == ["m1", "m2", "m3"]
    assert r.json()["total"] == 3 and r.json()["me"] == "doctor01"


def test_mine_paging_and_status(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("doctor01", "doctor")
    assert _ids(c.get("/api/v1/medical/prescriptions/mine", headers=H,
                      params={"limit": 1, "offset": 1})) == ["m2"]
    r = c.get("/api/v1/medical/prescriptions/mine", headers=H,
              params={"status": "rejected"})
    assert _ids(r) == ["m3"] and r.json()["total"] == 1
    # limit=0：只取计数（列表空，total 真值）
    r0 = c.get("/api/v1/medical/prescriptions/mine", headers=H, params={"limit": 0})
    assert r0.json()["items"] == [] and r0.json()["total"] == 3


def test_mine_summary_counts(monkeypatch, tmp_path):
    """summary=1：不返回任何处方正文，只回按状态分组的计数（角标轮询用）。"""
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/prescriptions/mine",
              headers=_h("doctor01", "doctor"), params={"summary": 1})
    d = r.json()
    assert d["items"] == [] and d["total"] == 3
    assert d["counts"] == {"pending_pharm": 1, "approved": 1, "rejected": 1}
    assert "case_text" not in r.text, "summary 不得下发任何处方正文"


def test_pending_paging_and_count_only(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("pharm01", "pharmacist")
    assert _ids(c.get("/api/v1/medical/prescriptions/pending", headers=H,
                      params={"limit": 1, "offset": 1})) == ["p2"]
    r = c.get("/api/v1/medical/prescriptions/pending", headers=H, params={"limit": 0})
    assert r.json()["items"] == [] and r.json()["total"] == 2


def test_reviewed_by_me_paging(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("pharm01", "pharmacist")
    r = c.get("/api/v1/medical/prescriptions/reviewed-by-me", headers=H,
              params={"limit": 2, "offset": 2})
    assert _ids(r) == ["r3"] and r.json()["total"] == 3


def test_invalid_params_rejected(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("doctor01", "doctor")
    assert c.get("/api/v1/medical/prescriptions/mine", headers=H,
                 params={"limit": 501}).status_code == 422
    assert c.get("/api/v1/medical/prescriptions/mine", headers=H,
                 params={"offset": -1}).status_code == 422
    assert c.get("/api/v1/medical/prescriptions/mine", headers=H,
                 params={"status": "x" * 33}).status_code == 422


def test_audit_records_paging_fields(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    c.get("/api/v1/medical/prescriptions/mine", headers=_h("doctor01", "doctor"),
          params={"limit": 1, "offset": 1})
    ev = [e for e in log.entries if e["payload"].get("action") == "list"]
    assert ev, "应写 list 审计"
    p = ev[-1]["payload"]
    assert p["n"] == 1 and p["total"] == 3 and p["limit"] == 1 and p["offset"] == 1
    assert p["summary"] is False
