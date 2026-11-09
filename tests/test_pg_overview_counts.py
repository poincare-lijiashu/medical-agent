"""第 91 批：概览面板状态计数下推 PG（`GROUP BY status`，替代读全表再计数）。

`GET /overview` 原为 `review.list_all()` 全表解码后内存 sum；队列 1168 行时每次打开
管理台都要整表读。本批：PG 聚合一次拿到 pending / resolved / 全状态分布，
空表/无池/离线/异常回落既有全表计数（行为不变）。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

ROWS = [
    {"id": "r1", "status": "pending"}, {"id": "r2", "status": "pending"},
    {"id": "r3", "status": "approved"}, {"id": "r4", "status": "rejected"},
    {"id": "r5", "status": "draft"},
]


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, rows):
        self.conn = _FakeConn(rows)

    def acquire(self):
        return _FakeAcquire(self.conn)


class _FakeConn:
    def __init__(self, rows):
        self.rows = list(rows)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM review_queue":
            return len(self.rows)
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        assert "GROUP BY status" in s, s[:100]
        counts = {}
        for r in self.rows:
            st = str(r.get("status") or "unknown")
            counts[st] = counts.get(st, 0) + 1
        return [{"status": k, "n": v} for k, v in counts.items()]


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


def test_counts_pending_and_resolved(monkeypatch):
    _use_pool(monkeypatch)
    out = pg_store.review_status_counts()
    assert out["pending"] == 2 and out["resolved"] == 2
    assert out["counts"] == {"pending": 2, "approved": 1, "rejected": 1, "draft": 1}


def test_counts_domain_wrapper(monkeypatch):
    _use_pool(monkeypatch)
    assert review.status_counts()["pending"] == 2


def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert pg_store.review_status_counts() is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.review_status_counts() is None


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert pg_store.review_status_counts() is None


def test_route_overview_uses_pg_counts(monkeypatch, tmp_path):
    _use_pool(monkeypatch)

    def _boom(*a, **k):
        raise AssertionError("PG 可用时概览不应读全表")

    monkeypatch.setattr(review, "list_all", _boom)
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    c = TestClient(app)
    tok = create_access_token("admin01", "admin")
    r = c.get("/api/v1/medical/overview", headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["pending"] == 2 and d["resolved"] == 2


def test_route_overview_falls_back(monkeypatch, tmp_path):
    """无池时走 JSON **全表**计数（第 99 批：不再用 list_all 的前 100 条口径）。"""
    monkeypatch.setattr(pg_store, "_pool", None)
    rows = [dict(r) for r in ROWS]
    monkeypatch.setattr(review, "_load", lambda: rows)
    monkeypatch.setattr(review, "list_all",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("不应读 list_all")))
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    c = TestClient(app)
    tok = create_access_token("admin01", "admin")
    r = c.get("/api/v1/medical/overview", headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["pending"] == 2 and d["resolved"] == 2
