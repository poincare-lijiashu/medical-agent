"""终评 F10：真实 PG 集成冒烟（可选——PG 127.0.0.1:5433 不可达自动整文件 skip）。

目的：FakeConn 防线的真值补验。test_pg_repo 用假连接只能证明「代码按预期调用了预期
SQL」，本文件把同款 SQL（复用 pg_store 私有 UPSERT 常量）打到真实 Postgres，断言
schema/约束/UPSERT 语义被真 PG 接受（列名/类型/jsonb 转换/唯一索引/CHECK）。

安全纪律（只读为主）：
- 绝不调用 pg_store.save_users/save_queue/save_drug_rules——它们是「JSON 兜底 +
  全表重写同步」语义（DELETE WHERE NOT IN + upsert，last-writer-wins），直接驱动会
  清掉真实业务数据；本文件只对专用测试行执行同款 UPSERT/SELECT/DELETE；
- 插入一律 zz_pgsmoke 专用前缀（username/id/药对名），finally 必删，失败也不留残留；
- skip 条件：PG_OFFLINE=on（测试进程显式离线）或连接探测失败（本机无 PG 容器）。
"""
from __future__ import annotations

import asyncio
import uuid

import asyncpg
import pytest

from backend.core import pg_store

SMOKE_USER = "zz_pgsmoke_user"
SMOKE_RX_PREFIX = "zz-pgsmoke-"


def _dsn() -> str:
    """与 pg_store 同一 DSN 来源（settings.async_database_url → asyncpg 形态）。"""
    return pg_store._dsn()


def _pg_up() -> bool:
    if pg_store.offline_mode():
        return False

    async def _probe():
        c = await asyncpg.connect(_dsn(), timeout=3)
        await c.close()

    try:
        asyncio.run(_probe())
        return True
    except Exception:  # noqa: BLE001 —— 连不上即整文件 skip（可选冒烟，不算失败）
        return False


pytestmark = pytest.mark.skipif(not _pg_up(),
                                reason="真实 PG（127.0.0.1:5433）不可达——可选集成冒烟跳过")


def test_users_upsert_and_select_roundtrip():
    """users：同 _USER_UPSERT 形态 upsert 专用测试账号 → SELECT 回读一致；
    二次 upsert 走 ON CONFLICT DO UPDATE 分支（幂等更新而非报错）→ 清理。"""

    async def body():
        c = await asyncpg.connect(_dsn(), timeout=5)
        try:
            await c.execute(pg_store._USER_UPSERT, SMOKE_USER, "doctor", "口腔科",
                            "pbkdf2$smoke$hash")
            row = await c.fetchrow(
                "SELECT username, role, dept, password_hash FROM users WHERE username=$1",
                SMOKE_USER)
            assert row["role"] == "doctor" and row["dept"] == "口腔科"
            await c.execute(pg_store._USER_UPSERT, SMOKE_USER, "qc", "", "pbkdf2$smoke$hash2")
            row2 = await c.fetchrow("SELECT role FROM users WHERE username=$1", SMOKE_USER)
            assert row2["role"] == "qc"  # 冲突分支更新生效
        finally:
            await c.execute("DELETE FROM users WHERE username=$1", SMOKE_USER)
            await c.close()

    asyncio.run(body())


def test_review_queue_upsert_and_load():
    """review_queue：同 _REVIEW_UPSERT 形态 upsert 专用测试单（17 参数，含 images/meta/
    sources 三个 ::jsonb 转换占位）→ SELECT 回读校验 → 清理。"""
    rid = SMOKE_RX_PREFIX + uuid.uuid4().hex[:8]

    async def body():
        c = await asyncpg.connect(_dsn(), timeout=5)
        try:
            await c.execute(pg_store._REVIEW_UPSERT, rid, "2026-01-01T00:00:00", "imaging",
                            "冒烟问题", "冒烟回答", 0.7, "冒烟风险", "pending", "doctor01",
                            None, None, None, '["data:image/png;base64,AAA"]', None, None,
                            None, '["KB:smoke"]')
            row = await c.fetchrow(
                "SELECT agent, confidence, images, sources FROM review_queue WHERE id=$1", rid)
            assert row["agent"] == "imaging"
            assert abs(row["confidence"] - 0.7) < 1e-9
            assert row["images"] == '["data:image/png;base64,AAA"]'  # jsonb 原文回读
            assert row["sources"] == '["KB:smoke"]'
        finally:
            await c.execute("DELETE FROM review_queue WHERE id=$1", rid)
            await c.close()

    asyncio.run(body())


def test_drug_rules_roundtrip_and_unique_pair():
    """drug_rules：同 _RULE_UPSERT 形态全量往返 1 条（severity CHECK 约束 + 药对唯一
    索引幂等）→ SELECT 回读 → 清理。"""
    a, b = "zz_pgsmoke甲药", "zz_pgsmoke乙药"

    async def body():
        c = await asyncpg.connect(_dsn(), timeout=5)
        try:
            await c.execute(pg_store._RULE_UPSERT, a, b, "中危", "冒烟机制", "冒烟处置", "冒烟来源")
            row = await c.fetchrow(
                "SELECT severity, mechanism, management, source FROM drug_rules "
                "WHERE drug_a=$1 AND drug_b=$2", a, b)
            assert row["severity"] == "中危"
            assert row["mechanism"] == "冒烟机制" and row["management"] == "冒烟处置"
            # 幂等：同药对二次 upsert 不新增行（ux_drug_rules_pair 唯一索引 + ON CONFLICT）
            await c.execute(pg_store._RULE_UPSERT, a, b, "高危", "机制2", "处置2", "来源2")
            n = await c.fetchval(
                "SELECT count(*) FROM drug_rules WHERE drug_a=$1 AND drug_b=$2", a, b)
            assert n == 1
            row2 = await c.fetchrow(
                "SELECT severity, mechanism FROM drug_rules WHERE drug_a=$1 AND drug_b=$2", a, b)
            assert row2["severity"] == "高危" and row2["mechanism"] == "机制2"
        finally:
            await c.execute("DELETE FROM drug_rules WHERE drug_a=$1 AND drug_b=$2", a, b)
            await c.close()

    asyncio.run(body())
