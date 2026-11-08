"""第 107 批：审计流过滤（actor / event_type / payload.action）。

语义：
- 过滤在 SQL 侧完成，`audit_offset` 是**过滤后**的偏移；
- 三个参数均为空时 SQL 与旧版一致（无 WHERE）；
- PG 不可用时回落 JSONL：先过滤、再倒序、最后切片（与 PG 同口径）。
"""
import asyncio

from fastapi.testclient import TestClient

from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.main import app

ENTRIES = [
    {"ts": "2026-01-01T00:00:00+00:00", "event_type": "review", "actor": "admin01",
     "payload": {"action": "resolved_approved"}},
    {"ts": "2026-01-02T00:00:00+00:00", "event_type": "prescription", "actor": "pharm01",
     "payload": {"action": "approved"}},
    {"ts": "2026-01-03T00:00:00+00:00", "event_type": "review", "actor": "qc01",
     "payload": {"action": "resolved_rejected"}},
    {"ts": "2026-01-04T00:00:00+00:00", "event_type": "review", "actor": "admin01",
     "payload": {"action": "reopened"}},
]


class _Audit:
    def __init__(self):
        self.entries = [dict(e) for e in ENTRIES]

    def write(self, *a, **k):
        pass

    def recent(self, n=8, offset=0):
        return self.entries[::-1][offset:offset + n]


class _Acq:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _Conn:
    def __init__(self):
        self.sql = ""
        self.args = ()

    async def fetch(self, sql, *a):
        self.sql, self.args = " ".join(sql.split()), a
        return []


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acq(self.conn)


def _client(monkeypatch):
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", _Audit)
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return c, {"Authorization": "Bearer " + r.json()["access_token"]}


def test_pg_sql_shape_with_filters(monkeypatch):
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    asyncio.run(pg_store._audit_recent_pg_impl(50, 10, "admin01", "review", "reopened"))
    assert ("WHERE actor = $1 AND event_type = $2 AND payload->>'action' = $3" in conn.sql)
    assert conn.args == ("admin01", "review", "reopened", 50, 10)


def test_pg_sql_unchanged_without_filters(monkeypatch):
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    asyncio.run(pg_store._audit_recent_pg_impl(50, 0))
    assert "WHERE" not in conn.sql
    assert conn.args == (50, 0)


def test_route_passes_filters_to_pg(monkeypatch):
    seen = {}

    async def _fake(limit, offset=0, actor="", event_type="", action=""):
        seen.update(limit=limit, offset=offset, actor=actor,
                    event_type=event_type, action=action)
        return []

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/data?audit_offset=50&audit_actor=%20admin01%20"
              "&audit_event=review&audit_action=reopened", headers=h)
    assert r.status_code == 200, r.text
    assert seen == {"limit": 50, "offset": 50, "actor": "admin01",
                    "event_type": "review", "action": "reopened"}


def test_jsonl_fallback_filters_then_pages(monkeypatch):
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _none)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/data?audit_actor=admin01", headers=h).json()
    acts = [e["action"] for e in d["audit_recent"]]
    assert acts == ["reopened", "resolved_approved"]  # 仅 admin01，且新→旧

    d2 = c.get("/api/v1/medical/admin/data?audit_event=review&audit_offset=1", headers=h).json()
    assert [e["action"] for e in d2["audit_recent"]] == ["resolved_rejected", "resolved_approved"]
