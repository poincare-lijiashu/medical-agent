"""第 124 批：会诊意见子表（派生索引）——双写、全量重建、回填口径。

背景：`consults.data->'opinions'` 仍是唯一真源；子表只服务于"待本科室填意见"这类
**取反**查询（§127 实测 jsonb 取反无法走 GIN）。本批先做 双写 + 回填脚本，读路径不切换。
"""
import asyncio
import importlib.util
import inspect
from pathlib import Path

from backend.core import pg_store

# 与 test_audit_archive 同模式：按文件路径加载脚本（scripts/ 不是包）
_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "backfill_consult_opinions.py"
_spec = importlib.util.spec_from_file_location("backfill_consult_opinions", _SCRIPT)
backfill = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(backfill)


class _Tx:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Conn:
    def __init__(self):
        self.executed: list[str] = []
        self.bulk: list[tuple] = []
        self.bulk_sql: list[str] = []

    def transaction(self):
        return _Tx()

    async def execute(self, sql, *a):
        self.executed.append(" ".join(sql.split()))

    async def executemany(self, sql, args):
        self.bulk_sql.append(" ".join(sql.split()))
        self.bulk.extend(list(args))

    async def fetch(self, sql, *a):
        return []

    async def fetchval(self, sql, *a):
        return 0


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


ITEM = {"id": "c1", "initiator": "doctor01", "status": "open",
        "opinions": [
            {"doctor": "d1", "dept": "内科", "content": "建议查心电图",
             "ts": "2026-11-09T10:00:00+00:00"},
            {"doctor": "", "dept": "外科", "content": "无署名的脏数据"},
            {"doctor": "d2", "dept": "外科", "content": "建议清创", "ts": "坏时间"},
        ]}


def test_opinion_rows_mapping_skips_empty_doctor_and_bad_ts():
    rows = pg_store.consult_opinion_rows(ITEM)
    assert [r[0] for r in rows] == ["c1", "c1"]
    assert rows[0][1:] == ("内科", "d1", "建议查心电图", rows[0][4])
    assert rows[0][4] is not None and rows[0][4].year == 2026
    assert rows[1][2] == "d2" and rows[1][4] is None  # 非法时间 → None（不丢行）
    assert pg_store.consult_opinion_rows({"opinions": ITEM["opinions"]}) == []  # 无 id


def test_sync_consult_opinions_deletes_then_upserts():
    conn = _Conn()
    asyncio.run(pg_store._sync_consult_opinions(conn, ITEM))
    assert conn.executed == ["DELETE FROM consult_opinions WHERE consult_id = $1"]
    assert len(conn.bulk) == 2 and conn.bulk[0][0] == "c1"
    assert "ON CONFLICT(consult_id,doctor) DO UPDATE" in " ".join(conn.bulk_sql[0].split())


def test_sync_consult_opinions_swallows_errors():
    """派生索引失败必须吞掉（主单写入不能因它回滚）。"""
    class _Boom(_Conn):
        async def executemany(self, sql, args):
            raise RuntimeError("boom")

    asyncio.run(pg_store._sync_consult_opinions(_Boom(), ITEM))  # 不抛即通过


def test_upsert_consult_row_syncs_opinions(monkeypatch):
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    monkeypatch.setattr(pg_store, "_pg_write_call", lambda d, o, coro: asyncio.run(coro))
    pg_store.upsert_consult_row(ITEM)
    joined = " | ".join(conn.executed)
    assert "INSERT INTO consults(id,data,initiator,status)" in joined
    assert "DELETE FROM consult_opinions WHERE consult_id = $1" in joined
    assert len(conn.bulk) == 2


def test_full_save_rebuilds_derived_table(monkeypatch):
    """全量同步（save_consults 路径）整体重建子表，避免删除会诊后残留孤儿行。"""
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    asyncio.run(pg_store._pg_save_consults([ITEM]))
    assert "DELETE FROM consult_opinions" in conn.executed
    assert any("jsonb_array_elements" in s for s in conn.executed)
    assert "INSERT INTO consult_opinions" in conn.executed[-1]


def test_opinions_table_ddl_is_wired_into_init():
    assert "CREATE TABLE IF NOT EXISTS consult_opinions" in pg_store._CONSULT_OPINIONS_DDL
    assert "primary key (consult_id, doctor)" in pg_store._CONSULT_OPINIONS_DDL
    src = inspect.getsource(pg_store._init_impl)
    assert "_CONSULT_OPINIONS_DDL" in src
    assert "create index if not exists ix_consult_opinions_dept" in src.lower()


def test_backfill_plan_counts_and_skips():
    rows = [{"id": "c1", "data": ITEM},
            {"id": "c2", "data": {"id": "c2", "opinions": []}},
            {"id": "c3", "data": "not-json"}]
    stats = backfill.plan(rows)
    assert stats["consults"] == 2           # c3 数据损坏 → 跳过
    assert stats["opinions"] == 2           # c1 两条有效意见
    assert stats["skipped"] == 1            # c1 里无 doctor 的一条
    assert stats["perConsult"] == {"c1": 2, "c2": 0}


def test_backfill_diff_by_consult():
    # 0 条意见的单本就不该在子表里出现（两边都算 0 → 一致）
    assert backfill.diff_by_consult({"c1": 2, "c2": 0}, {"c1": 2}) == []
    assert backfill.diff_by_consult({"c1": 2}, {"c1": 0}) == ["c1"]  # 期望有、实际缺
    assert backfill.diff_by_consult({"c1": 1}, {"c1": 2}) == ["c1"]  # 行数不符
    assert backfill.diff_by_consult({"c1": 1}, {"c1": 1, "c9": 1}) == ["c9"]  # 孤儿行
