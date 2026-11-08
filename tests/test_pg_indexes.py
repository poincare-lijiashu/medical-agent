"""第 92 批：81–91 批下推查询的配套 PG 索引（假池注入，绝不连真实 PG）。

背景：第 81–91 批把列表/计数/详情下推到了 PG，但此前只有 audit_log 有索引。
本批给 review_queue / prescriptions / consults / case_archive 补与查询逐字对齐的
索引，全部 IF NOT EXISTS（多实例重复启动幂等，小表可继续走顺序扫描）。

锁定语义：
- 每个索引都由 _init_impl 真正下发（不是只定义常量不接线）；
- 索引 DDL 必须在对应 CREATE TABLE 之后（全新库首次启动不能因表不存在失败）；
- 语句含 IF NOT EXISTS（重复 init 不报错、不改已有索引）；
- 表达式/列序与查询一致（否则上量后也不会被使用）。
"""
import asyncio

from backend.core import pg_store


class _FakeConn:
    def __init__(self):
        self.statements: list[str] = []

    async def execute(self, sql, *args):
        self.statements.append(sql)
        return "OK"


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return _FakeAcquire(self._conn)

    async def close(self):
        return None


def _run_init(monkeypatch) -> list[str]:
    conn = _FakeConn()

    async def _fake_create_pool(dsn, **kwargs):
        return _FakePool(conn)

    monkeypatch.setattr(pg_store.asyncpg, "create_pool", _fake_create_pool)
    monkeypatch.setattr(pg_store, "_pool", None)
    asyncio.run(pg_store._init_impl())
    return conn.statements


def test_init_issues_every_index_ddl(monkeypatch):
    """常量里声明的索引必须全部真正下发（防止只定义不执行）。"""
    statements = _run_init(monkeypatch)
    issued = [s for s in statements if "CREATE INDEX" in s]
    expected = list(pg_store._PG_INDEX_DDL)
    assert issued[-len(expected):] == expected
    assert len(expected) == 15


def test_indexes_are_idempotent(monkeypatch):
    """全部 IF NOT EXISTS：多实例/重复启动幂等，不重建也不报错。"""
    statements = _run_init(monkeypatch)
    for ddl in pg_store._PG_INDEX_DDL:
        assert "CREATE INDEX IF NOT EXISTS" in ddl
        assert ddl in statements


def test_indexes_created_after_their_tables(monkeypatch):
    """全新库首次 init：索引 DDL 必须排在对应建表语句之后。"""
    statements = _run_init(monkeypatch)
    for table in ("review_queue", "prescriptions", "consults", "case_archive"):
        table_at = next(i for i, s in enumerate(statements)
                        if s.startswith(f"CREATE TABLE IF NOT EXISTS {table}("))
        index_at = next(i for i, s in enumerate(statements)
                        if "CREATE INDEX" in s and f"ON {table}" in s)
        assert table_at < index_at, f"{table} 索引早于建表"


def test_index_shapes_match_downstream_queries():
    """索引表达式/列序与 81–91 批查询口径逐字对齐（避免"建了但用不上"）。"""
    # 待核列表：status 过滤 + ts,id 正序（队列先进先出）
    assert "ON review_queue(status, ts, id)" in pg_store._PG_INDEX_DDL[0]
    # 历史/个人列表：提交人/审核人 + ts,id 倒序
    assert "review_queue(submitted_by, ts DESC, id DESC)" in pg_store._PG_INDEX_DDL[1]
    assert "review_queue(reviewed_by, ts DESC, id DESC)" in pg_store._PG_INDEX_DDL[2]
    # 处方：doctor/status + data->>'created_at' 倒序（与 _RX_ORDER 同表达式）
    assert ("prescriptions(doctor, ((data->>'created_at')) DESC NULLS LAST, id DESC)"
            in pg_store._PG_INDEX_DDL[4])
    assert ("prescriptions(status, ((data->>'created_at')) DESC NULLS LAST, id DESC)"
            in pg_store._PG_INDEX_DDL[5])
    # 会诊：jsonb 包含查询用 GIN
    assert "USING gin ((data->'target_depts'))" in pg_store._PG_INDEX_DDL[9]
    assert "USING gin ((data->'opinions'))" in pg_store._PG_INDEX_DDL[10]
    # 病例库：archived_at 倒序 + submitted_by/status
    assert ("case_archive(((data->>'archived_at')) DESC NULLS LAST, id DESC)"
            in pg_store._PG_INDEX_DDL[11])
    assert "case_archive(((data->>'submitted_by')), status)" in pg_store._PG_INDEX_DDL[14]
