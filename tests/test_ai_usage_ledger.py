"""§289 AI 日调用量台账与授权对账上报。

锁住四件事：
1. 计费口径：成功问答计 1 次，前置闲聊（prefilter）与空 agent 不计；
2. 耐久：同日重启不丢、不重复（按 日期#进程实例 成行，合计=行之和）；
3. 日切：跨 UTC 零点按日分开，昨日增量不会算到今天；
4. 上报：内部令牌、幂等、未配置即关闭、失败不丢台账。

PG/JSON 存储由 conftest 的 autouse fixture 锁到 JSON；这里只关注台账语义。
"""
from __future__ import annotations

import json

import httpx
import pytest

from backend.agents import ai_usage
from backend.config import settings


@pytest.fixture(autouse=True)
def _isolate(monkeypatch, tmp_path):
    monkeypatch.setattr(ai_usage, "LEDGER_FILE", str(tmp_path / "ai_usage_daily.json"))
    monkeypatch.setattr(ai_usage, "WORKER_ID", "worker-test")
    monkeypatch.setattr(settings, "platform_license_base_url", "")
    monkeypatch.setattr(settings, "internal_api_token", "")
    ai_usage._reset_for_tests()
    yield
    ai_usage._reset_for_tests()


def _rows() -> list[dict]:
    with open(ai_usage.LEDGER_FILE, encoding="utf-8") as f:
        return json.load(f)


def test_prefilter_and_blank_are_not_billable():
    ai_usage.record("prefilter")
    ai_usage.record("")
    ai_usage.record("  ")
    ai_usage.record("drug")

    assert ai_usage.flush()["aiCalls"] == 1
    assert ai_usage.totals(ai_usage._today()) == 1


def test_restart_neither_loses_nor_double_counts(monkeypatch):
    ai_usage.record("drug")
    ai_usage.record("drug")
    assert ai_usage.flush()["aiCalls"] == 2

    # 模拟进程重启：内存清零、新的进程实例 id，但台账文件还在
    ai_usage._reset_for_tests()
    monkeypatch.setattr(ai_usage, "WORKER_ID", "worker-restarted")
    ai_usage.record("drug")
    ai_usage.record("drug")
    ai_usage.record("drug")

    assert ai_usage.flush()["aiCalls"] == 5
    assert len(_rows()) == 2, "两个进程实例应各留一行，便于审计"


def test_day_rollover_keeps_days_separate(monkeypatch):
    monkeypatch.setattr(ai_usage, "_today", lambda: "2026-11-12")
    ai_usage.record("drug")
    ai_usage.record("drug")
    assert ai_usage.flush()["aiCalls"] == 2

    monkeypatch.setattr(ai_usage, "_today", lambda: "2026-11-13")
    ai_usage.record("drug")
    assert ai_usage.flush()["aiCalls"] == 1

    assert ai_usage.totals("2026-11-12") == 2
    assert ai_usage.totals("2026-11-13") == 1


def test_report_is_disabled_without_configuration():
    ai_usage.record("drug")

    assert ai_usage.is_configured() is False
    assert ai_usage.report_once() is False
    assert ai_usage.start() is False


def test_report_pushes_daily_total_with_internal_token(monkeypatch):
    monkeypatch.setattr(settings, "platform_license_base_url", "http://license.test")
    monkeypatch.setattr(settings, "internal_api_token", "internal-token")
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"code": 0, "data": {"aiCalls": 2}})

    monkeypatch.setattr(ai_usage, "_new_client",
                        lambda timeout_s: httpx.Client(transport=httpx.MockTransport(handler)))
    ai_usage.record("drug")
    ai_usage.record("drug")

    assert ai_usage.report_once() is True
    assert len(calls) == 1
    request = calls[0]
    assert request.url.path == "/internal/license/usage/ai"
    assert request.headers["x-internal-token"] == "internal-token"
    body = json.loads(request.content)
    assert body["aiCalls"] == 2
    assert body["source"] == "AI_CORE"
    assert body["workerId"] == "worker-test"
    assert body["date"] == ai_usage._today()
    assert ai_usage.snapshot()["lastPush"]["ok"] is True


def test_unchanged_total_is_not_reposted_before_heartbeat(monkeypatch):
    monkeypatch.setattr(settings, "platform_license_base_url", "http://license.test")
    monkeypatch.setattr(settings, "internal_api_token", "internal-token")
    hits = []

    def handler(request: httpx.Request) -> httpx.Response:
        hits.append(request)
        return httpx.Response(200, json={"code": 0, "data": {}})

    monkeypatch.setattr(ai_usage, "_new_client",
                        lambda timeout_s: httpx.Client(transport=httpx.MockTransport(handler)))
    ai_usage.record("drug")

    assert ai_usage.report_once() is True
    assert ai_usage.report_once() is True
    assert len(hits) == 1, "计数没变化且未到心跳窗口，不应每分钟写授权库"

    ai_usage.record("drug")   # 计数变化 → 必须再推
    assert ai_usage.report_once() is True
    assert len(hits) == 2


def test_report_failure_keeps_ledger_and_is_counted(monkeypatch):
    monkeypatch.setattr(settings, "platform_license_base_url", "http://license.test")
    monkeypatch.setattr(settings, "internal_api_token", "internal-token")

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("license down")

    monkeypatch.setattr(ai_usage, "_new_client",
                        lambda timeout_s: httpx.Client(transport=httpx.MockTransport(handler)))
    ai_usage.record("drug")

    with pytest.raises(httpx.ConnectError):
        ai_usage.report_once()

    snap = ai_usage.snapshot()
    assert snap["failures"] == 1
    assert snap["lastPush"]["ok"] is False
    assert snap["todayCalls"] == 1, "上报失败不能丢台账"
    assert "medassist_agent_ai_usage_push_failures_total 1" in ai_usage.prometheus_text()


def test_prometheus_text_exposes_today_calls():
    ai_usage.record("drug")
    ai_usage.record("drug")
    ai_usage.flush()

    text = ai_usage.prometheus_text()
    assert "medassist_agent_ai_calls_today 2" in text
    assert "medassist_agent_ai_usage_push_timestamp_seconds 0" in text


def test_total_never_drops_below_own_count_when_ledger_unreadable(monkeypatch):
    """PG 写失败/台账暂不可读时，上报值不能比本进程已计数更小（否则授权侧看到回落）。"""
    monkeypatch.setattr(ai_usage, "_load_rows", lambda: [])
    ai_usage.record("drug")
    ai_usage.record("drug")
    ai_usage.record("drug")

    assert ai_usage.totals(ai_usage._today()) == 3
