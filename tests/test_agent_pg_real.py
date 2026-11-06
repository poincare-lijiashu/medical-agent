"""评审 M-30：Agent 域 8 张 PG 表的**真库**断言（medagent_test）。

背景：此前"多实例安全"只有线程级间接证据，PG 真库语义零证据。
本文件直接对 ``agent_skills`` + 7 张通用 JSON 列表表做"写入 → 查询 → 清理"，
PG 不可用（PG_OFFLINE/连接失败）时自动跳过，不阻塞离线环境。

安全：只写本测试生成的唯一键，结束即删；不改动测试库既有行。
"""
from __future__ import annotations

import asyncio
import uuid

import pytest

from backend.core import pg_store

# 表 → 主键（与 pg_store._JSON_LIST_KEYS 保持一致）
GENERIC = {
    "agent_bots": "code",
    "agent_bot_teams": "code",
    "agent_bot_memories": "id",
    "agent_skill_experiences": "id",
    "agent_skill_proposals": "id",
    "agent_skill_metrics": "id",
    "agent_skill_advices": "id",
}


@pytest.fixture()
def real_pg(pg_originals, monkeypatch):
    """初始化真库，并把被 conftest 替换成 JSON-only 的 PG API 还原。"""
    if pg_store.offline_mode():
        pytest.skip("PG_OFFLINE=on，跳过真库断言")
    if not asyncio.run(pg_store.init()):
        pytest.skip("PG 不可用（medagent_test），跳过真库断言")
    for name, fn in pg_originals.items():
        monkeypatch.setattr(pg_store, name, fn)
    yield pg_store._pool
    asyncio.run(pg_store.close())


def _count(table: str, key: str, value: str) -> int:
    async def go():
        async with pg_store._pool.acquire() as c:
            return await c.fetchval(f"SELECT count(*) FROM {table} WHERE {key}=$1", value)

    return pg_store._run_pg(go())


def _delete(table: str, key: str, value: str) -> None:
    async def go():
        async with pg_store._pool.acquire() as c:
            await c.execute(f"DELETE FROM {table} WHERE {key}=$1", value)

    pg_store._run_pg(go())


@pytest.mark.parametrize("table", sorted(GENERIC))
def test_generic_tables_write_read_roundtrip(real_pg, tmp_path, table):
    key = GENERIC[table]
    value = f"probe-{uuid.uuid4().hex[:10]}"
    item = {key: value, "status": "PROPOSED", "probe": True}
    path = str(tmp_path / f"{table}.json")
    try:
        pg_store.save_json_list(table, path, [item])
        assert _count(table, key, value) == 1, f"{table} 未落到 PG"

        loaded = pg_store.load_json_list(table, path)
        assert any(i.get(key) == value for i in loaded), f"{table} 回读不到刚写入的行"
    finally:
        _delete(table, key, value)


def test_agent_skills_table_write_read_roundtrip(real_pg, tmp_path):
    code = f"probe-{uuid.uuid4().hex[:10]}"
    item = {"code": code, "status": "DRAFT", "probe": True}
    path = str(tmp_path / "agent_skills.json")
    try:
        pg_store.save_skills([item], path)
        assert _count("agent_skills", "code", code) == 1

        loaded = pg_store.load_skills(path)
        assert any(i.get("code") == code for i in loaded)
    finally:
        _delete("agent_skills", "code", code)


def test_upsert_item_keeps_both_writers_rows(real_pg, tmp_path):
    """评审 M-31：两个并发写入者各 upsert 不同主键 → 两行都在（行级语义）。

    对照 M-32 已知限制：``save_json_list``（整表覆盖）下两写者会互相删除，
    因此快照/建议域必须走 ``upsert_json_item``；技能/Bot/团队/记忆域尚未改造。
    """
    import threading

    table, key = "agent_skill_metrics", "id"
    path = str(tmp_path / "snap.json")
    values = [f"probe-{uuid.uuid4().hex[:10]}" for _ in range(2)]

    def work(value: str):
        pg_store.upsert_json_item(table, path, {key: value, "probe": True}, False)

    threads = [threading.Thread(target=work, args=(v,)) for v in values]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    try:
        assert sum(_count(table, key, v) for v in values) == 2, "并发 upsert 丢失行"
    finally:
        for v in values:
            _delete(table, key, v)


def test_agent_skills_status_column_is_written(real_pg, tmp_path):
    """状态列参与告警/看板查询，真库侧必须与 data 一致。"""
    code = f"probe-{uuid.uuid4().hex[:10]}"
    path = str(tmp_path / "agent_skills.json")

    async def status_of():
        async with pg_store._pool.acquire() as c:
            return await c.fetchval("SELECT status FROM agent_skills WHERE code=$1", code)

    try:
        pg_store.save_skills([{"code": code, "status": "PUBLISHED"}], path)
        assert pg_store._run_pg(status_of()) == "PUBLISHED"
    finally:
        _delete("agent_skills", "code", code)
