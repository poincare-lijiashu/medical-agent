"""第 85 批：会诊/处方单据按主键直查 PG（假池注入）。

背景：`consults.get(cid)` / `prescriptions.get(rid)` 原为 `_load()` 读全表再线性查找。
单据详情/审核回执路径频繁调用，队列增长后每次都要整表解码。本批复用第 84 批三态模式：
- `(True, dict)`  PG 命中；
- `(True, None)` PG 表非空但无此 id → 确定不存在（不回落 JSON，防已删单据"复活"）；
- `(False, None)` PG 无法回答（无池/离线/空表/异常）→ 调用方走 JSON 现状。
"""
import json

from backend.core import consults as consult_mod
from backend.core import pg_store
from backend.core import prescriptions as rx_mod

CONSULT = {"id": "c1", "initiator": "doctor01", "status": "open",
           "target_depts": ["心内科"], "opinions": []}
RX = {"id": "rx1", "doctor": "doctor01", "status": "pending_pharm",
      "case_text": "病例", "drugs": [{"name": "华法林"}]}

DB = {"consults": {"c1": CONSULT}, "prescriptions": {"rx1": RX}}


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, db):
        self.conn = _FakeConn(db)

    def acquire(self):
        return _FakeAcquire(self.conn)


class _FakeConn:
    def __init__(self, db):
        self.db = db

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        for t in ("consults", "prescriptions"):
            if s == f"SELECT count(*) FROM {t}":
                return len(self.db[t])
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        for t in ("consults", "prescriptions"):
            if s == f"SELECT data FROM {t} WHERE id = $1":
                item = self.db[t].get(a[0])
                return [{"data": json.dumps(item, ensure_ascii=False)}] if item else []
        raise AssertionError(f"假池未实现的 fetch: {s[:100]}")


def _use_pool(monkeypatch, db=None):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(db or DB))


# ---------- 数据层 ----------

def test_consult_hit(monkeypatch):
    _use_pool(monkeypatch)
    found, item = pg_store.get_consult_row("c1")
    assert found is True and item["id"] == "c1"


def test_prescription_hit(monkeypatch):
    _use_pool(monkeypatch)
    found, item = pg_store.get_prescription_row("rx1")
    assert found is True and item["drugs"] == [{"name": "华法林"}]


def test_missing_id_is_definitive(monkeypatch):
    _use_pool(monkeypatch)
    assert pg_store.get_consult_row("nope") == (True, None)
    assert pg_store.get_prescription_row("nope") == (True, None)


def test_empty_table_returns_not_found(monkeypatch):
    _use_pool(monkeypatch, {"consults": {}, "prescriptions": {}})
    assert pg_store.get_consult_row("c1") == (False, None)
    assert pg_store.get_prescription_row("rx1") == (False, None)


def test_no_pool_returns_not_found(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.get_consult_row("c1") == (False, None)


def test_pg_failure_returns_not_found(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(DB))
    pg_store._pool.conn = _Boom(DB)
    assert pg_store.get_consult_row("c1") == (False, None)
    assert pg_store.get_prescription_row("rx1") == (False, None)


# ---------- 域层 ----------

def test_consult_get_prefers_pg(monkeypatch):
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("PG 命中时不应再读全表")

    monkeypatch.setattr(consult_mod, "_load", _boom)
    assert consult_mod.get("c1")["initiator"] == "doctor01"
    assert consult_mod.get("nope") is None


def test_prescription_get_prefers_pg(monkeypatch):
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("PG 命中时不应再读全表")

    monkeypatch.setattr(rx_mod, "_load", _boom)
    assert rx_mod.get("rx1")["status"] == "pending_pharm"
    assert rx_mod.get("nope") is None


def test_both_fall_back_to_json(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(consult_mod, "_load", lambda: [dict(CONSULT)])
    monkeypatch.setattr(rx_mod, "_load", lambda: [dict(RX)])
    assert consult_mod.get("c1")["status"] == "open"
    assert rx_mod.get("rx1")["doctor"] == "doctor01"
    assert consult_mod.get("nope") is None and rx_mod.get("nope") is None


def test_corrupted_row_is_definitive(monkeypatch):
    """jsonb 损坏 → (True, None)：PG 已回答（不回落 JSON，避免坏行让人误读旧值）。"""
    class _Bad(_FakeConn):
        async def fetch(self, sql, *a):
            s = " ".join(sql.split())
            if s.startswith("SELECT data FROM"):
                return [{"data": "{not-json"}]
            return await super().fetch(sql, *a)

    monkeypatch.setattr(pg_store, "_pool", _FakePool(DB))
    pg_store._pool.conn = _Bad(DB)
    assert pg_store.get_consult_row("c1") == (True, None)
