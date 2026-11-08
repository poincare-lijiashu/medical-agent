"""第 86 批：个人视角列表下推 PG（我的待确认 / 我的质控驳回 / 药物驳回）。

三个列表原本都是 `_load()[::-1]` 全表扫描后按 `submitted_by` 过滤，医生端每次进页面都会拉。
本批改为固定 where 常量的 PG 条件查询（值走 $n），返回已剥离 images；空表/无池/离线/异常
回落既有内存实现，行为不变。

锁定语义：
- 顺序新→旧（ts DESC, id DESC，与 `_load()[::-1]` 一致）；
- 待确认：`self_confirm_required AND confirmed_by_self IS NOT TRUE`（NULL/False 都算未确认）；
- 质控驳回：`agent='qc'` 且（rejected 或 resubmit_of 非空）——重提链任意状态都回；
- 药物驳回：`agent='drug'` 且 rejected；
- 所有列表一律剥离 images。
"""
import json

from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

COLS = list(pg_store._REVIEW_COLS)

PENDING_CONFIRM_WHERE = ("submitted_by = $1 AND self_confirm_required "
                         "AND confirmed_by_self IS NOT TRUE")
REJECTIONS_WHERE = ("submitted_by = $1 AND agent = 'qc' AND "
                    "(status = 'rejected' OR COALESCE(resubmit_of, '') <> '')")
DRUG_REJECTIONS_WHERE = "submitted_by = $1 AND agent = 'drug' AND status = 'rejected'"


def _row(**kw):
    base = {k: None for k in COLS}
    base.update({"meta": None, "sources": None, "self_confirm_required": False,
                 "confirmed_by_self": None})
    base.update(kw)
    base["images"] = kw.get("images")
    return base


ROWS = [
    _row(id="m1", ts="2026-01-01T00:00:00+00:00", submitted_by="doctor01", agent="qc",
         status="rejected", review_note="缺检验", images=None),
    _row(id="m2", ts="2026-01-02T00:00:00+00:00", submitted_by="doctor01", agent="qc",
         status="approved", self_confirm_required=True, confirmed_by_self=False,
         images=json.dumps(["big"])),
    _row(id="m3", ts="2026-01-03T00:00:00+00:00", submitted_by="doctor01", agent="qc",
         status="approved", self_confirm_required=True, confirmed_by_self=True),
    _row(id="m4", ts="2026-01-04T00:00:00+00:00", submitted_by="doctor01", agent="drug",
         status="rejected"),
    _row(id="m5", ts="2026-01-05T00:00:00+00:00", submitted_by="doctor02", agent="qc",
         status="rejected"),
    _row(id="m6", ts="2026-01-06T00:00:00+00:00", submitted_by="doctor01", agent="qc",
         status="approved", resubmit_of="m1", attempt=2),
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


def _match(item, where, args):
    if where == PENDING_CONFIRM_WHERE:
        return (item.get("submitted_by") == args[0] and item.get("self_confirm_required")
                and item.get("confirmed_by_self") is not True)
    if where == REJECTIONS_WHERE:
        return (item.get("submitted_by") == args[0] and item.get("agent") == "qc"
                and (item.get("status") == "rejected" or bool(item.get("resubmit_of"))))
    if where == DRUG_REJECTIONS_WHERE:
        return (item.get("submitted_by") == args[0] and item.get("agent") == "drug"
                and item.get("status") == "rejected")
    raise AssertionError(f"假池未实现的 where: {where}")


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
        where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        rows = [r for r in self.rows if _match(r, where, a)]
        return sorted(rows, key=lambda r: (r.get("ts") or "", r.get("id") or ""),
                      reverse=True)


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 域层 ----------

def test_pending_confirm_only_unconfirmed(monkeypatch):
    _use_pool(monkeypatch)
    items = review.my_pending_confirm("doctor01")
    assert [i["id"] for i in items] == ["m2"]  # m3 已确认、m1/m4 未标记
    assert "images" not in items[0]  # 个人列表一律剥图


def test_rejections_includes_resubmit_chain(monkeypatch):
    _use_pool(monkeypatch)
    items = review.my_rejections("doctor01")
    assert [i["id"] for i in items] == ["m6", "m1"]  # 新→旧；m6 为重提链
    assert all("images" not in i for i in items)


def test_drug_rejections(monkeypatch):
    _use_pool(monkeypatch)
    assert [i["id"] for i in review.my_drug_rejections("doctor01")] == ["m4"]
    assert review.my_drug_rejections("doctor02") == []


def test_other_user_isolation(monkeypatch):
    _use_pool(monkeypatch)
    assert [i["id"] for i in review.my_rejections("doctor02")] == ["m5"]
    assert review.my_pending_confirm("doctor02") == []


def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert pg_store.review_my_rows(PENDING_CONFIRM_WHERE, ("doctor01",)) is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.review_my_rows(PENDING_CONFIRM_WHERE, ("doctor01",)) is None


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert pg_store.review_my_rows(PENDING_CONFIRM_WHERE, ("doctor01",)) is None


def test_domain_falls_back_to_json(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(review, "_load", lambda: [dict(r) for r in ROWS])
    assert [i["id"] for i in review.my_rejections("doctor01")] == ["m6", "m1"]
    assert [i["id"] for i in review.my_pending_confirm("doctor01")] == ["m2"]


# ---------- 路由层 ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app)


def test_route_my_pending_confirm_uses_pg(monkeypatch, tmp_path):
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("PG 可用时不应再读全表")

    monkeypatch.setattr(review, "_load", _boom)
    c = _client(monkeypatch, tmp_path)
    tok = create_access_token("doctor01", "doctor")
    r = c.get("/api/v1/medical/review/my-pending-confirm",
              headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 200, r.text
    assert [i["id"] for i in r.json()["items"]] == ["m2"]
