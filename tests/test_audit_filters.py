"""第 107 批：审计流过滤（actor / event_type / payload.action）。
第 108 批：过滤后总数 + 专用分页端点 /admin/audit + 读路径计量。

语义：
- 过滤在 SQL 侧完成，`audit_offset` 是**过滤后**的偏移；
- 三个参数均为空时 SQL 与旧版一致（无 WHERE）；
- PG 不可用时回落 JSONL：先过滤、再倒序、最后切片（与 PG 同口径）；
- 第 108 批的 total 是「过滤后」总条数（PG 侧 count(*)；回落时 len(matched)），
  前端据此显示「共 N 条」并精确禁用下一页。
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
    def __init__(self, count_value=0):
        self.sql = ""
        self.args = ()
        self.count_value = count_value

    async def fetch(self, sql, *a):
        self.sql, self.args = " ".join(sql.split()), a
        return []

    async def fetchval(self, sql, *a):
        self.sql, self.args = " ".join(sql.split()), a
        return self.count_value


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


def test_pg_count_sql_shape_with_filters(monkeypatch):
    """第 108 批：总数复用同一 WHERE 片段（条件列名固定、值走 $n）。"""
    conn = _Conn(count_value=2149)
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    assert asyncio.run(pg_store._audit_count_pg_impl("admin01", "review", "")) == 2149
    assert conn.sql == "SELECT count(*) FROM audit_log WHERE actor = $1 AND event_type = $2"
    assert conn.args == ("admin01", "review")

    asyncio.run(pg_store._audit_count_pg_impl())
    assert conn.sql == "SELECT count(*) FROM audit_log"
    assert conn.args == ()


def test_route_passes_filters_to_pg(monkeypatch):
    seen = {}

    async def _fake(limit, offset=0, actor="", event_type="", action=""):
        seen.update(limit=limit, offset=offset, actor=actor,
                    event_type=event_type, action=action)
        return []

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)

    async def _count(actor="", event_type="", action=""):
        return 0

    monkeypatch.setattr(pg_store, "audit_count_pg", _count)
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
    assert d["audit_total"] == 2  # 第 108 批：过滤后总数（JSONL 侧算得）

    d2 = c.get("/api/v1/medical/admin/data?audit_event=review&audit_offset=1", headers=h).json()
    assert [e["action"] for e in d2["audit_recent"]] == ["resolved_rejected", "resolved_approved"]
    assert d2["audit_total"] == 3


# ---------- 第 108 批：专用分页端点 /admin/audit ----------

def test_admin_audit_route_pages_with_jsonl_fallback(monkeypatch):
    """PG 不可用：/admin/audit 与 /admin/data 同口径（过滤→倒序→切片），total=过滤后条数。"""
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _none)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/audit?audit_event=review", headers=h)
    assert r.status_code == 200, r.text
    d = r.json()
    assert [e["action"] for e in d["items"]] == ["reopened", "resolved_rejected",
                                                 "resolved_approved"]
    assert d["total"] == 3
    assert d["offset"] == 0 and d["limit"] == 50
    assert d["filters"] == {"actor": "", "event": "review", "action": ""}

    d2 = c.get("/api/v1/medical/admin/audit?audit_event=review&audit_offset=2", headers=h).json()
    assert [e["action"] for e in d2["items"]] == ["resolved_approved"]
    assert d2["total"] == 3  # total 与偏移无关

    d3 = c.get("/api/v1/medical/admin/audit?audit_offset=-5", headers=h).json()
    assert d3["offset"] == 0  # 负数按 0 处理，绝不 500


def test_admin_audit_route_uses_pg_total(monkeypatch):
    """PG 可用：total 取 count(*)（不是本页长度），空过滤透传空串。"""
    rows = [{"ts": "2026-01-04T00:00:00+00:00", "event_type": "review",
             "actor": "admin01", "payload": {"action": "reopened"}}]

    async def _fake(limit, offset=0, actor="", event_type="", action=""):
        return rows

    async def _count(actor="", event_type="", action=""):
        return 2149

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    monkeypatch.setattr(pg_store, "audit_count_pg", _count)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit?audit_actor=admin01", headers=h).json()
    assert len(d["items"]) == 1 and d["total"] == 2149


def test_admin_audit_route_requires_admin(monkeypatch):
    """只读端点同样仅 admin（qc/doctor 一律 403）。"""
    seed_default_users()
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    r2 = c.get("/api/v1/medical/admin/audit",
               headers={"Authorization": "Bearer " + r.json()["access_token"]})
    assert r2.status_code == 403

