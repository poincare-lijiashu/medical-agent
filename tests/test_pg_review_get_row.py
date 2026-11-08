"""第 84 批：单条复核详情按主键直查 PG（假池注入）。

背景：`review.get(rid)` 原实现是 `_load()` 读全表再线性查找——详情页/知情确认/重提预填
每次都为一条记录付全表代价（真实队列 600+ 条）。本批优先主键直查，PG 不可用/空表/异常
回落既有线性查找（行为不变）。

返回约定 `(found, item)`：
- `(True, dict)`  PG 有这条；
- `(True, None)`  PG 表非空但无此 id → 确定不存在（不再回落 JSON，防止已删记录"复活"）；
- `(False, None)` PG 无法回答（无池/离线/空表/异常）→ 调用方走 JSON 现状。
"""
import json

from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

COLS = list(pg_store._REVIEW_COLS)


def _row(**kw):
    base = {k: None for k in COLS}
    base.update({"meta": None, "sources": None})
    base.update(kw)
    base["images"] = kw.get("images")
    return base


ROWS = [
    _row(id="g1", ts="2026-01-01T00:00:00+00:00", status="pending", agent="qc",
         question="待核对问题", submitted_by="doctor01", reviewed_by=None,
         images=json.dumps(["i1"])),
    _row(id="g2", ts="2026-01-02T00:00:00+00:00", status="approved", agent="qc",
         question="已处理问题", submitted_by="doctor01", reviewed_by="qc01", images=None),
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
        assert s.startswith("SELECT id,ts"), s[:80]
        assert "WHERE id = $1" in s, s[:120]
        return [r for r in self.rows if r["id"] == a[0]]


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 数据层 ----------

def test_hit_decodes_images(monkeypatch):
    _use_pool(monkeypatch)
    found, item = pg_store.get_review_row("g1")
    assert found is True
    assert item["id"] == "g1" and item["images"] == ["i1"]


def test_missing_id_is_definitive(monkeypatch):
    """表非空且无此 id → (True, None)：确定不存在，不让调用方回落 JSON。"""
    _use_pool(monkeypatch)
    assert pg_store.get_review_row("nope") == (True, None)


def test_empty_table_returns_not_found(monkeypatch):
    _use_pool(monkeypatch, [])
    assert pg_store.get_review_row("g1") == (False, None)


def test_no_pool_returns_not_found(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.get_review_row("g1") == (False, None)


def test_pg_failure_returns_not_found(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert pg_store.get_review_row("g1") == (False, None)


# ---------- 域层 ----------

def test_get_prefers_pg(monkeypatch):
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("PG 命中时不应再读全表")

    monkeypatch.setattr(review, "_load", _boom)
    assert review.get("g2")["status"] == "approved"
    assert review.get("nope") is None  # (True, None) 同样不该读全表


def test_get_falls_back_to_json(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(review, "_load", lambda: [dict(ROWS[0])])
    assert review.get("g1")["question"] == "待核对问题"
    assert review.get("nope") is None


# ---------- 路由层 ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def test_route_detail_uses_pg(monkeypatch, tmp_path):
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("详情端点不应再读全表")

    monkeypatch.setattr(review, "_load", _boom)
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/item/g1", headers=_h("admin01", "admin"))
    assert r.status_code == 200, r.text
    assert r.json()["item"]["id"] == "g1"
    miss = c.get("/api/v1/medical/review/item/nope", headers=_h("admin01", "admin"))
    assert miss.status_code == 404
