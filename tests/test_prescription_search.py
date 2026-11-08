"""第 103 批：处方列表关键字检索（PG 下推 + 内存同构回落）。

范围：`/prescriptions/mine`（医生本人）与 `/prescriptions/pending`（药剂科待审）。
语义：病例摘要 / 医生 / 科室 / 药品名的大写不敏感子串；`%` `_` `!` 按字面量（ESCAPE '!'）；
`summary=1` 的角标计数不受 q 影响（只回状态分布）。
"""
from fastapi.testclient import TestClient

from backend.core import pg_store
from backend.core import prescriptions as rx
from backend.core.auth import create_access_token, seed_default_users
from backend.main import app

ITEMS = [
    {"id": "rx-1", "doctor": "doctor01", "dept": "口腔科", "case_text": "咽痛伴发热",
     "drugs": [{"name": "阿莫西林", "dose": "0.5g"}], "status": "pending_pharm"},
    {"id": "rx-2", "doctor": "doctor01", "dept": "口腔科", "case_text": "头痛",
     "drugs": [{"name": "布洛芬", "dose": "0.2g"}], "status": "approved"},
]


def _h(username: str, role: str) -> dict:
    return {"Authorization": "Bearer " + create_access_token(username, role)}


class _FakeAudit:
    """路由会写审计（list/list_pending）；单测不应污染真实审计文件。"""

    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append(k)

    def recent(self, n=8, offset=0):
        return []


def _isolate_audit(monkeypatch):
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", _FakeAudit)


def test_page_mine_pushes_q_and_escapes(monkeypatch):
    captured = {}

    def _fake(where="", params=(), limit=None, offset=0, count_by_status=False):
        captured.update(where=where, params=params, limit=limit, offset=offset)
        return {"items": [], "total": 0}

    monkeypatch.setattr(pg_store, "prescriptions_page", _fake)
    rx.page_mine("doctor01", 10, 0, "approved", False, "阿莫西林%")
    assert "doctor = $1" in captured["where"]
    assert "status = $2" in captured["where"]
    assert "LIKE" in captured["where"] and "ESCAPE '!'" in captured["where"]
    assert "jsonb_array_elements" in captured["where"]
    assert captured["params"] == ("doctor01", "approved", "阿莫西林!%")
    assert captured["limit"] == 10


def test_summary_ignores_q(monkeypatch):
    captured = {}

    def _fake(where="", params=(), limit=None, offset=0, count_by_status=False):
        captured.update(where=where, params=params, count_by_status=count_by_status)
        return {"items": [], "total": 0, "counts": {}}

    monkeypatch.setattr(pg_store, "prescriptions_page", _fake)
    rx.page_mine("doctor01", None, 0, None, True, "阿莫西林")
    assert captured["where"] == "doctor = $1"
    assert captured["params"] == ("doctor01",)
    assert captured["count_by_status"] is True


def test_page_pending_pushes_q(monkeypatch):
    captured = {}

    def _fake(where="", params=(), limit=None, offset=0, count_by_status=False):
        captured.update(where=where, params=params)
        return {"items": [], "total": 0}

    monkeypatch.setattr(pg_store, "prescriptions_page", _fake)
    rx.page_pending(5, 0, "布洛芬")
    assert captured["where"].startswith("status = 'pending_pharm' AND (")
    assert captured["params"] == ("布洛芬",)


def test_matches_query_semantics():
    assert rx.matches_query(ITEMS[0], "阿莫西林") is True
    assert rx.matches_query(ITEMS[0], "口腔科") is True
    assert rx.matches_query(ITEMS[0], "DOCTOR01") is True  # 大小写不敏感
    assert rx.matches_query(ITEMS[0], "布洛芬") is False
    assert rx.matches_query(ITEMS[0], "") is True


def test_mine_route_filters_in_memory(monkeypatch):
    seed_default_users()
    _isolate_audit(monkeypatch)
    monkeypatch.setattr(rx, "page_mine", lambda *a, **k: None)
    monkeypatch.setattr(rx, "list_mine", lambda me: [dict(i) for i in ITEMS])
    c = TestClient(app)
    h = _h("doctor01", "doctor")
    d = c.get("/api/v1/medical/prescriptions/mine?q=阿莫西林", headers=h).json()
    assert [i["id"] for i in d["items"]] == ["rx-1"]
    assert d["total"] == 1
    d2 = c.get("/api/v1/medical/prescriptions/mine?q=%20", headers=h).json()  # 空白 = 不过滤
    assert d2["total"] == 2


def test_pending_route_passes_q_to_pg(monkeypatch):
    seed_default_users()
    _isolate_audit(monkeypatch)
    seen = {}

    def _page(limit, offset, q=""):
        seen.update(limit=limit, offset=offset, q=q)
        return {"items": [], "total": 0}

    monkeypatch.setattr(rx, "page_pending", _page)
    c = TestClient(app)
    r = c.get("/api/v1/medical/prescriptions/pending?limit=3&q=布洛芬",
              headers=_h("pharm01", "pharmacist"))
    assert r.status_code == 200, r.text
    assert seen == {"limit": 3, "offset": 0, "q": "布洛芬"}
