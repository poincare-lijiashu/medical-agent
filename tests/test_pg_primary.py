"""W4.1 PG 真源翻转验收（转型计划书三条可测标准）：

① 单一 PG 事务写路径：编排分派单点 `_json_then_pg`（10 个调用点零改动），
   由 test_primary_write_fail_no_split / test_legacy_orchestrations_json_first 锁定语义；
② 并发集成测试：多线程并发写同一域（读-改-写语义）→ 串行交替下不丢更新；
   ⚠️ 已知边界：save_* 为**全量同步 API**（整域 DELETE 差集 + UPSERT），多写者
   并发各自持局部视图时天然互相覆盖——跨实例严格不丢更新需行级增量 UPSERT API
   （W4.1 后续工程，见 DEPLOY.md）；本测试的并发部分验证"不崩 + PG/JSON 终态一致"。
③ JSON 降级镜像：PG 主写成功后 JSON 仍落盘（灾难时最近恢复源），失败时不写（防分裂）。
"""
from __future__ import annotations

import json
import threading

import pytest

from backend.core import pg_store
from backend.core.pg_store import _json_then_pg, _pg_save_users


@pytest.fixture()
def pg_pool():
    """连真 PG（本机 5433 容器）；不可用则整文件 skip。"""
    import asyncio

    ok = asyncio.run(pg_store.init())
    if not ok:
        pytest.skip("PG 不可用（容器未启动），跳过真源翻转集成测试")
    yield pg_store._pool
    asyncio.run(pg_store.close())


def test_concurrent_read_modify_write_converges(pg_pool, tmp_path):
    """验收②：8 线程读-改-写同一域（模拟真实调用方语义：load 全域 → 增量改 → save）。
    断言：全部写入收敛进 PG 终态（RMW 链不丢已确认更新）+ PG 与 JSON 镜像终态一致。"""
    path = str(tmp_path / "users.json")
    lock = threading.Lock()  # RMW 临界区：模拟同实例内的调用方串行化（进程内锁既有语义）
    errors: list[str] = []

    def worker(wid: int):
        for i in range(5):
            try:
                with lock:  # RMW 必须原子（与生产端 auth 域锁同语义）
                    users = pg_store.load_users(path) or {}
                    users[f"w{wid}u{i}"] = {"role": "doctor", "dept": "", "password_hash": "h"}
                    users["shared"] = {"role": "doctor", "dept": "内科", "password_hash": "h"}
                    _json_then_pg(_pg_save_users, path, users)
            except Exception as e:  # noqa: BLE001
                errors.append(f"w{wid}u{i}: {e}")

    threads = [threading.Thread(target=worker, args=(w,)) for w in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors

    got = pg_store.load_users(path)
    missing = [f"w{w}u{i}" for w in range(8) for i in range(5) if f"w{w}u{i}" not in (got or {})]
    assert not missing, f"丢更新：{len(missing)} 个已确认写入不在 PG 终态"
    assert "shared" in (got or {})
    # PG 与 JSON 镜像终态一致（真源翻转后镜像应成功跟上）
    mirror = json.loads(open(path, encoding="utf-8").read())
    assert set(mirror.keys()) == set((got or {}).keys()), "PG 与 JSON 镜像终态不一致"


def test_primary_write_fail_no_split(pg_pool, tmp_path, monkeypatch):
    """验收③（防分裂）：pg_primary=true 且 PG 写失败 → 抛出且 JSON 不落盘
    （不产生"PG 没写进但 JSON 写了"的分裂状态）。"""
    path = str(tmp_path / "users.json")
    from backend.config import settings
    monkeypatch.setattr(settings, "pg_primary", True)

    def boom(data):
        raise RuntimeError("pg down")

    with pytest.raises(RuntimeError, match="pg down"):
        _json_then_pg(boom, path, {"u1": {"role": "doctor"}})
    import os
    assert not os.path.exists(path), "PG 主写失败时 JSON 不应落盘（防分裂）"


def test_mirror_json_written_on_success(pg_pool, tmp_path, monkeypatch):
    """验收③（镜像）：PG 主写成功 → JSON 镜像仍落盘（灾难时最近恢复源）。"""
    path = str(tmp_path / "users.json")
    from backend.config import settings
    monkeypatch.setattr(settings, "pg_primary", True)
    users = {"mirror_test": {"role": "doctor", "dept": "", "password_hash": "h"}}
    _json_then_pg(_pg_save_users, path, users)
    got = json.loads(open(path, encoding="utf-8").read())
    assert "mirror_test" in got


def test_legacy_orchestrations_json_first(pg_pool, tmp_path, monkeypatch):
    """灰度回滚语义：pg_primary=false 回旧编排——JSON 先写真源；PG 失败仅置 stale、
    不抛出（服务可用性优先），读回落 JSON 最新数据。"""
    path = str(tmp_path / "users.json")
    from backend.config import settings
    monkeypatch.setattr(settings, "pg_primary", False)

    def boom(data):
        raise RuntimeError("pg down")

    _json_then_pg(boom, path, {"u1": {"role": "doctor"}})  # 不抛出
    got = json.loads(open(path, encoding="utf-8").read())
    assert "u1" in got  # JSON 已落盘（旧编排真源）


def test_offline_mode_json_only(tmp_path, monkeypatch):
    """离线模式（PG_OFFLINE=on）：JSON 是唯一持久层，两编排等价，不触 PG。"""
    monkeypatch.setenv("PG_OFFLINE", "on")
    path = str(tmp_path / "users.json")
    called = {"n": 0}

    def save(data):
        called["n"] += 1
        raise AssertionError("离线模式不应触 PG")

    _json_then_pg(save, path, {"u1": {"role": "doctor"}})
    assert called["n"] == 0
    assert json.loads(open(path, encoding="utf-8").read())["u1"]["role"] == "doctor"
