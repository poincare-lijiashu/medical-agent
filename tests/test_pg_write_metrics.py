"""第 95 批：PG 写路径计量（镜像写 / 全量同步 / 单行 UPSERT）。

背景：读路径（93/94 批）已有次数/失败/耗时直方图，写路径仍是盲区——镜像写失败只写
一条 warning（数据只落 JSON）、全量同步失败只置 stale、单行 UPSERT 失败由调用方兜底。
本批与读路径同构导出 `medassist_agent_pg_write_total` / `_errors_total` /
`_duration_seconds`（domain/op 标签）。

锁定语义：
- 只统计**真正尝试写 PG** 的调用（无池/离线守卫直接返回或抛 RuntimeError，不计入）；
- 镜像写吞异常但计 error（调用方语义不变：绝不冒泡）；
- 全量同步/UPSERT 计 error 后原样抛出（pg_primary=true 的 500 语义、旧编排的 stale 置位都不变）。
"""
import asyncio
import os

import pytest

from backend.config import settings
from backend.core import pg_store


def _reset():
    pg_store._reset_pg_query_metrics()


def _boom_run(coro):
    getattr(coro, "close", lambda: None)()
    raise RuntimeError("pg down")


def _bucket(text: str, labels: str, bound: str) -> int:
    prefix = f"medassist_agent_pg_write_duration_seconds_bucket{{{labels},le=\"{bound}\"}}"
    line = [ln for ln in text.splitlines() if ln.startswith(prefix)][0]
    return int(line.rsplit(" ", 1)[1])


def test_write_call_records_success(monkeypatch):
    _reset()

    def _fake_run(coro):
        getattr(coro, "close", lambda: None)()
        return None

    monkeypatch.setattr(pg_store, "_run_pg", _fake_run)
    pg_store._pg_write_call("prescriptions", "upsert_row", object())
    text = pg_store.prometheus_text()
    labels = 'domain="prescriptions",op="upsert_row"'
    assert f"medassist_agent_pg_write_total{{{labels}}} 1" in text
    assert f"medassist_agent_pg_write_errors_total{{{labels}}} 0" in text
    assert _bucket(text, labels, "+Inf") == 1


def test_write_call_records_error_and_reraises(monkeypatch):
    _reset()

    def _fake_run(coro):
        getattr(coro, "close", lambda: None)()
        raise RuntimeError("disk full")

    monkeypatch.setattr(pg_store, "_run_pg", _fake_run)
    with pytest.raises(RuntimeError, match="disk full"):
        pg_store._pg_write_call("case_archive", "upsert_row", object())
    text = pg_store.prometheus_text()
    assert ('medassist_agent_pg_write_errors_total'
            '{domain="case_archive",op="upsert_row"} 1') in text


def test_write_async_records_success_and_error(monkeypatch):
    _reset()
    calls = {"n": 0}

    async def _arun_ok(coro):
        getattr(coro, "close", lambda: None)()
        calls["n"] += 1

    monkeypatch.setattr(pg_store, "_arun", _arun_ok)
    asyncio.run(pg_store._pg_write_async("audit_log", "mirror", object()))

    async def _arun_boom(coro):
        getattr(coro, "close", lambda: None)()
        raise RuntimeError("pg gone")

    monkeypatch.setattr(pg_store, "_arun", _arun_boom)
    with pytest.raises(RuntimeError, match="pg gone"):
        asyncio.run(pg_store._pg_write_async("audit_log", "mirror", object()))

    text = pg_store.prometheus_text()
    assert calls["n"] == 1
    assert 'medassist_agent_pg_write_total{domain="audit_log",op="mirror"} 2' in text
    assert 'medassist_agent_pg_write_errors_total{domain="audit_log",op="mirror"} 1' in text


def test_primary_write_failure_raises_without_json(monkeypatch, tmp_path):
    """pg_primary=true：PG 失败直接抛（不写 JSON，防分裂），并计量为 error。"""
    _reset()
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "_pool", object())
    monkeypatch.setattr(pg_store, "_run_pg", _boom_run)
    path = tmp_path / "queue.json"

    async def _save(_data):
        return None

    with pytest.raises(RuntimeError, match="pg down"):
        pg_store._json_then_pg(_save, str(path), [{"id": "x"}])
    assert not os.path.exists(path)  # 不能出现"PG 没写进、JSON 写了"的分裂
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_write_total{domain="repo",op="full_sync"} 1' in text
    assert 'medassist_agent_pg_write_errors_total{domain="repo",op="full_sync"} 1' in text


def test_legacy_write_failure_sets_stale_but_keeps_json(monkeypatch, tmp_path):
    """旧编排（pg_primary=false）：JSON 先落盘，PG 失败置 stale 且不冒泡，计量为 error。"""
    _reset()
    monkeypatch.setattr(settings, "pg_primary", False)
    monkeypatch.setattr(pg_store, "_pool", object())
    monkeypatch.setattr(pg_store, "_run_pg", _boom_run)
    path = tmp_path / "queue.json"
    pg_store._repo_pg_stale.discard("queue.json")

    async def _save(_data):
        return None

    pg_store._json_then_pg(_save, str(path), [{"id": "x"}])
    assert os.path.exists(path)
    assert "queue.json" in pg_store._repo_pg_stale
    assert ('medassist_agent_pg_write_errors_total'
            '{domain="repo",op="full_sync"} 1') in pg_store.prometheus_text()


def test_mirror_failure_is_swallowed_but_counted(monkeypatch):
    """镜像写 best-effort：失败不冒泡，但必须可观测。"""
    _reset()
    monkeypatch.setattr(pg_store, "_pool", object())

    async def _arun_boom(coro):
        getattr(coro, "close", lambda: None)()
        raise RuntimeError("pg gone")

    monkeypatch.setattr(pg_store, "_arun", _arun_boom)
    asyncio.run(pg_store.mirror_review({"id": "rev-1"}))
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_write_errors_total{domain="review_queue",op="mirror"} 1' in text


def test_purge_and_delete_paths_are_counted(monkeypatch):
    """第 96 批：批量 purge（宽松/严格/按 agent）与用户删除也计入写路径。"""
    _reset()
    monkeypatch.setattr(pg_store, "_pool", object())

    async def _arun_ok(coro):
        getattr(coro, "close", lambda: None)()
        return "DELETE 3"

    monkeypatch.setattr(pg_store, "_arun", _arun_ok)
    assert asyncio.run(pg_store.purge_pending_reviews()) == "DELETE 3"
    assert asyncio.run(pg_store.purge_drug_reviews()) == "DELETE 3"

    def _run_ok(coro):
        getattr(coro, "close", lambda: None)()
        return "DELETE 2"

    monkeypatch.setattr(pg_store, "_run_pg", _run_ok)
    assert pg_store.purge_pending_reviews_strict() == "DELETE 2"

    def _run_delete(coro):
        getattr(coro, "close", lambda: None)()
        return 1

    monkeypatch.setattr(pg_store, "_run_pg", _run_delete)
    assert pg_store.delete_user_row("doctor01") == 1

    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_write_total{domain="review_queue",op="purge"} 2' in text
    assert 'medassist_agent_pg_write_total{domain="review_queue",op="purge_drug"} 1' in text
    assert 'medassist_agent_pg_write_total{domain="users",op="delete_row"} 1' in text
    assert 'medassist_agent_pg_write_errors_total{domain="users",op="delete_row"} 0' in text


def test_guards_do_not_count_writes(monkeypatch):
    """无池：镜像写直接返回、UPSERT 抛 RuntimeError，都不计写入次数。

    第 102 批起未触达键不导出：只有锚点（repo/full_sync）保持零样本。
    """
    _reset()
    monkeypatch.setattr(pg_store, "_pool", None)
    asyncio.run(pg_store.mirror_audit("event", "system", {}))
    with pytest.raises(RuntimeError):
        pg_store.upsert_prescription_row({"id": "rx-1"})
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_write_total{domain="repo",op="full_sync"} 0' in text
    assert 'domain="audit_log",op="mirror"' not in text
    assert 'domain="prescriptions",op="upsert_row"' not in text
    assert "# TYPE medassist_agent_pg_write_duration_seconds histogram" in text
