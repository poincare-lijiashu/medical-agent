"""评审 M-30：Agent 域 9 张 PG 表的**真库**断言（medagent_test）。

背景：此前"多实例安全"只有线程级间接证据，PG 真库语义零证据。
本文件直接对 ``agent_skills`` + 8 张通用 JSON 列表表做"写入 → 查询 → 清理"，
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
    "agent_sleep_reports": "reportId",
    # §289：AI 日调用量台账
    "agent_usage_daily": "id",
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
    """评审 M-31：两个并发写入者各 upsert 不同主键 → 两行都在（行级语义）。"""
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


def _skill_payload(code: str) -> dict:
    return {
        "code": code,
        "name": "并发探测技能",
        "description": "用于真库并发写入校验，不会进入任何临床路径。",
        "agentId": "drug",
        "promptTemplate": "请核对相互作用与禁忌，并逐条标注证据来源；证据不足时说明不确定性。",
        "toolAllowlist": ["drug_check"],
        "evidenceRefs": ["院内规则库 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }


def test_skills_multi_writer_keeps_all_rows(real_pg, tmp_path, monkeypatch):
    """评审 M-32：两个写入者并发 create_skill → 两行都在（业务写路径已改行级）。"""
    import threading

    from backend.agents import skills

    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "skills.json"))
    codes = [f"probe-{uuid.uuid4().hex[:10]}" for _ in range(2)]
    errors: list[Exception] = []

    def work(code: str):
        try:
            skills.create_skill(_skill_payload(code), "admin01")
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=work, args=(c,)) for c in codes]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    try:
        assert not errors, errors
        for code in codes:
            assert _count("agent_skills", "code", code) == 1, "并发创建丢失技能行"
    finally:
        for code in codes:
            _delete("agent_skills", "code", code)


def test_bots_multi_writer_keeps_all_rows(real_pg, tmp_path, monkeypatch):
    """评审 M-32：Bot 域同样改为按 code 行级 upsert。"""
    import threading

    from backend.agents import bots

    monkeypatch.setattr(bots, "BOTS_FILE", str(tmp_path / "bots.json"))
    codes = [f"probe-{uuid.uuid4().hex[:10]}" for _ in range(2)]
    errors: list[Exception] = []

    def payload(code: str) -> dict:
        return {
            "code": code, "name": "并发探测 Bot",
            "agentId": "literature",
            "persona": "你是循证医学助手，回答必须标注证据来源与不确定性，不做诊断结论。",
            "modelProvider": "default", "modelId": None,
            "skillCodes": [], "toolAllowlist": [], "memoryScope": "bot",
        }

    def work(code: str):
        try:
            bots.create_bot(payload(code), "admin01")
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=work, args=(c,)) for c in codes]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    try:
        assert not errors, errors
        for code in codes:
            assert _count("agent_bots", "code", code) == 1, "并发创建丢失 Bot 行"
    finally:
        for code in codes:
            _delete("agent_bots", "code", code)


def test_cas_update_rejects_stale_row_version(real_pg, tmp_path, monkeypatch):
    """评审 M-32：rowVersion 比较下推 SQL——同版本只能成功一次。"""
    from backend.agents import skills

    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "skills.json"))
    code = f"probe-{uuid.uuid4().hex[:10]}"
    card = skills.create_skill(_skill_payload(code), "admin01")
    try:
        assert pg_store.cas_update_skill_item({**card, "rowVersion": 1}, 0,
                                              skills.SKILLS_FILE) is True
        # 另一个实例仍持有 v0 → 必须失败（不能后写覆盖）
        assert pg_store.cas_update_skill_item({**card, "rowVersion": 1}, 0,
                                              skills.SKILLS_FILE) is False
        assert pg_store.cas_update_skill_item({**card, "rowVersion": 1}, 1,
                                              skills.SKILLS_FILE) is True
    finally:
        _delete("agent_skills", "code", code)


def test_insert_skill_rejects_duplicate_code(real_pg, tmp_path, monkeypatch):
    """创建路径跨实例去重：同 code 第二次插入返回 False。"""
    from backend.agents import skills

    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "skills.json"))
    code = f"probe-{uuid.uuid4().hex[:10]}"
    item = {**_skill_payload(code), "status": "DRAFT", "rowVersion": 0}
    try:
        assert pg_store.insert_skill_item(item, skills.SKILLS_FILE) is True
        assert pg_store.insert_skill_item(item, skills.SKILLS_FILE) is False
    finally:
        _delete("agent_skills", "code", code)


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
