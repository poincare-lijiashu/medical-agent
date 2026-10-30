"""W4.1 行级增量 UPSERT 验收（users 域）：

跨写者语义对比（同 pool 并发提交，正确性由 PG 行级锁保证、与连接来源无关）：
- 全量同步 save_users：多写者各持局部视图 → DELETE 差集互相覆盖（对照段演示丢失）
- 行级 upsert_user_row：只触达目标行 → 并发多写者全部生效

② auth 三写点（create/set_password/delete）行级落盘走真 PG 行级 API（回归）。
"""
from __future__ import annotations

import asyncio
import threading

import pytest

from backend.core import auth as auth_mod
from backend.core import pg_store


@pytest.fixture()
def pg_pool():
    """独立测试库 medagent_test（conftest 强制），进入清空 users 表。"""
    ok = asyncio.run(pg_store.init())
    if not ok:
        pytest.skip("PG 不可用，跳过行级 UPSERT 集成测试")

    async def _clean():
        async with pg_store._pool.acquire() as c:
            await c.execute("DELETE FROM users")

    pg_store._run_pg(_clean())
    yield pg_store._pool
    asyncio.run(pg_store.close())


def test_row_level_vs_full_sync_under_concurrency(pg_pool, tmp_path):
    """① 同一并发场景两段对照：
    - 行级 upsert_user_row ×8（不同用户）→ 8 行全在；
    - 全量同步 save_users ×8（各自局部视图）→ 只剩最后写者视图（丢失演示）。
    """
    path = str(tmp_path / "users.json")
    errors: list[str] = []

    def row_worker(wid: int):
        try:
            pg_store.upsert_user_row(f"row{wid}", "doctor", "", "h")
        except Exception as e:  # noqa: BLE001
            errors.append(f"row{wid}: {e}")

    threads = [threading.Thread(target=row_worker, args=(w,)) for w in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors
    got = pg_store.load_users(path) or {}
    assert all(f"row{w}" in got for w in range(8)), f"行级 UPSERT 丢失：{got}"

    def full_worker(wid: int):
        try:
            pg_store.save_users({f"full{wid}": {"role": "doctor", "dept": "", "password_hash": "h"}},
                                path)
        except Exception as e:  # noqa: BLE001
            errors.append(f"full{wid}: {e}")

    errors.clear()
    threads = [threading.Thread(target=full_worker, args=(w,)) for w in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    # 护栏实战：行级段留下的 8 行 vs 全量同步的 1 行局部视图 = 骤缩 → 全部被拒
    # （上一轮 users 表清空事故的准确复现+拦截）。幸存写者 ≤2（护栏窗口竞态）。
    rejected = [e for e in errors if "骤缩" in e]
    assert len(rejected) >= 6, f"护栏应拦截大多数覆盖性清空，实际拒绝 {len(rejected)}：{errors}"
    survivors = [k for k in (pg_store.load_users(path) or {}) if k.startswith("full")]
    assert len(survivors) <= 2, f"护栏下幸存覆盖不应超过 2，实际 {len(survivors)}"


def test_auth_row_level_lifecycle(pg_pool, tmp_path):
    """② auth 三写点行级落盘：create → set_password → delete 全走真 PG 行级 API。"""
    path = str(tmp_path / "users.json")
    assert auth_mod.create_user("rowlvl", "Str0ngPass!", "doctor", dept="内科")
    got = pg_store.load_users(path) or {}
    assert "rowlvl" in got and got["rowlvl"]["dept"] == "内科"
    assert auth_mod.set_password("rowlvl", "NewPass!2026")
    assert auth_mod.authenticate("rowlvl", "NewPass!2026") is not None
    assert auth_mod.delete_user("rowlvl")
    assert auth_mod.get_user("rowlvl") is None
