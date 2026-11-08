"""第 77 批：`GET /consults/inbox` 分页 + 角标计数（pendingForMe）。

动机：会诊红点角标每 60s 拉整个收件箱（含意见/影像）回前端过滤，只为数"本科室还有
几单没填意见"。本批新增 `limit/offset/summary` 与 `total/pendingForMe`（服务端按
本科室意见计算，口径与前端原 `opinions.some(dept)` 一致）；**缺省仍是全量**。
"""
from fastapi.testclient import TestClient

from backend.core import consults
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

INBOX = [
    {"id": "c1", "opinions": []},
    {"id": "c2", "opinions": [{"dept": "心内科", "text": "已阅"}]},
    {"id": "c3", "opinions": [{"dept": "外科", "text": "建议"}]},
]


def _client(monkeypatch, tmp_path, dept="心内科"):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    # 第 122 批：路由改调 inbox_page（过滤 → total/pending → 切片 三项一起下推 PG）
    def _fake_page(d, q="", limit=None, offset=0):
        items = [dict(i) for i in INBOX] if d else []
        total = len(items)
        pending = sum(1 for c in items
                      if not any((o or {}).get("dept") == d for o in (c.get("opinions") or [])))
        if limit is not None:
            items = items[offset:offset + limit]
        return items, total, pending

    monkeypatch.setattr(consults, "inbox_page", _fake_page)
    monkeypatch.setattr("backend.api.deps.get_user",
                        lambda u: {"role": "", "dept": dept})
    return TestClient(app), log


def _h():
    return {"Authorization": "Bearer " + create_access_token("doctor01", "doctor")}


def test_default_inbox_full_unchanged(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h()).json()
    assert [i["id"] for i in d["items"]] == ["c1", "c2", "c3"]
    assert d["total"] == 3 and d["dept"] == "心内科"
    assert d["pendingForMe"] == 2, "c1 无意见、c3 只有外科意见 → 本科室仍待填 2 单"


def test_paging_slices_but_count_is_global(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h(),
              params={"limit": 1, "offset": 2}).json()
    assert [i["id"] for i in d["items"]] == ["c3"]
    assert d["total"] == 3 and d["pendingForMe"] == 2


def test_summary_returns_counts_only(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"summary": 1})
    d = r.json()
    assert d["items"] == [] and d["total"] == 3 and d["pendingForMe"] == 2
    assert "opinions" not in r.text, "summary 不得下发会诊正文/意见"


def test_no_dept_returns_empty(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path, dept="")
    d = c.get("/api/v1/medical/consults/inbox", headers=_h()).json()
    assert d["items"] == [] and d["total"] == 0 and d["pendingForMe"] == 0


def test_audit_payload_paging_fields_and_silent_skip(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    c.get("/api/v1/medical/consults/inbox", headers=_h(),
          params={"limit": 1, "offset": 1})
    ev = [e for e in log.entries if e["payload"].get("action") == "list_inbox"]
    assert ev, "非 silent 应写 list_inbox 审计"
    p = ev[-1]["payload"]
    assert p["n"] == 1 and p["total"] == 3 and p["limit"] == 1 and p["offset"] == 1
    assert p["summary"] is False
    before = len(log.entries)
    c.get("/api/v1/medical/consults/inbox", headers=_h(),
          params={"summary": 1, "silent": 1})
    assert len(log.entries) == before, "silent 轮询不得写审计"
