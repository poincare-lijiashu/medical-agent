"""第 93 批：PG 查询耗时/失败按域计量（Prometheus）。

背景：第 81–92 批把列表/计数/详情下推到 PG 后，这些查询成了首屏与角标轮询的主依赖，
但成功/耗时此前不可见，失败也只有一条 warning。本批在 `_pg_call` 处计量并导出
`medassist_agent_pg_query_total` / `_errors_total` / `_seconds_total`（domain/op 标签）。

锁定语义：
- 只统计**真正尝试了 PG** 的调用（离线/无池/旧编排 stale 的守卫直接返回，不计入）；
- 失败（含熔断 PGUnavailable）计入 errors 并原样抛出，调用方回落 JSON 行为不变；
- 导出基数由内部常量决定（domain/op 不取用户输入），文本可被 Prometheus 抓取。
"""
import pytest

from backend.config import settings
from backend.core import pg_store


def _reset():
    pg_store._reset_pg_query_metrics()


def test_pg_call_records_success(monkeypatch):
    _reset()

    def _fake_run(coro):
        getattr(coro, 'close', lambda: None)()  # 假实现不消费协程：显式关闭，避免 never-awaited 告警
        return {"ok": True}

    monkeypatch.setattr(pg_store, "_run_pg", _fake_run)
    assert pg_store._pg_call("review_queue", "pending_page", object()) == {"ok": True}
    text = pg_store.prometheus_text()
    labels = 'domain="review_queue",op="pending_page"'
    assert f"medassist_agent_pg_query_total{{{labels}}} 1" in text
    assert f"medassist_agent_pg_query_errors_total{{{labels}}} 0" in text
    seconds_line = [ln for ln in text.splitlines()
                    if ln.startswith(f"medassist_agent_pg_query_seconds_total{{{labels}}}")][0]
    assert float(seconds_line.rsplit(" ", 1)[1]) >= 0.0


def test_pg_call_records_error_and_reraises(monkeypatch):
    _reset()

    def _fake_run(coro):
        getattr(coro, "close", lambda: None)()
        raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_run_pg", _fake_run)
    with pytest.raises(RuntimeError, match="boom"):
        pg_store._pg_call("consults", "list", object())
    text = pg_store.prometheus_text()
    labels = 'domain="consults",op="list"'
    assert f"medassist_agent_pg_query_total{{{labels}}} 1" in text
    assert f"medassist_agent_pg_query_errors_total{{{labels}}} 1" in text


def test_circuit_fast_fail_counts_as_error(monkeypatch):
    _reset()

    def _fake_run(coro):
        getattr(coro, "close", lambda: None)()
        raise pg_store.PGUnavailable("circuit open")

    monkeypatch.setattr(pg_store, "_run_pg", _fake_run)
    with pytest.raises(pg_store.PGUnavailable):
        pg_store._pg_call("case_archive", "stats", object())
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_query_errors_total{domain="case_archive",op="stats"} 1' in text


def test_public_entry_falls_back_and_counts_error(monkeypatch):
    """处方分页：PG 失败 → None（回落内存）且计量为 error（不再只是静默 warning）。"""
    _reset()
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "_pool", object())
    pg_store._repo_pg_stale.discard("prescriptions.json")

    def _boom(coro):
        getattr(coro, "close", lambda: None)()
        raise RuntimeError("pg down")

    monkeypatch.setattr(pg_store, "_run_pg", _boom)
    assert pg_store.prescriptions_page() is None
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_query_total{domain="prescriptions",op="page"} 1' in text
    assert 'medassist_agent_pg_query_errors_total{domain="prescriptions",op="page"} 1' in text


def test_guards_do_not_count(monkeypatch):
    """离线/无池在守卫处直接返回：不计入（否则纯 JSON 部署会被误报为 PG 失败）。

    零样本键会固定导出（便于 Prometheus 首抓与规则门禁），但计数必须保持 0。
    """
    _reset()
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.case_archive_list() is None
    assert pg_store.prescriptions_page() is None
    text = pg_store.prometheus_text()
    assert 'medassist_agent_pg_query_total{domain="case_archive",op="list"} 0' in text
    assert 'medassist_agent_pg_query_total{domain="prescriptions",op="page"} 0' in text
    assert 'medassist_agent_pg_query_errors_total{domain="prescriptions",op="page"} 0' in text
    assert "# TYPE medassist_agent_pg_query_total counter" in text
