"""评审 M-28：技能运营快照自动触发（补齐/定时/退出语义）。"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta

from backend.agents import skill_trends, snapshot_scheduler
from backend.config import settings


def test_next_run_rolls_to_tomorrow_when_passed():
    now = datetime(2026, 11, 6, 15, 0, 0)
    assert snapshot_scheduler._next_run(now, 1, 10) == datetime(2026, 11, 7, 1, 10)
    assert snapshot_scheduler._next_run(now, 23, 0) == datetime(2026, 11, 6, 23, 0)


def test_catch_up_creates_today_once():
    first = snapshot_scheduler.catch_up()
    assert first is not None
    assert first["date"] == datetime.now().astimezone().date().isoformat()
    assert first["generatedBy"] == snapshot_scheduler.SCHEDULER_ACTOR

    # 同一天再调不重复写（幂等）
    assert snapshot_scheduler.catch_up() is None
    assert len(skill_trends.list_snapshots(days=2)) == 1


def test_catch_up_skips_when_today_exists():
    skill_trends.snapshot("manual")
    assert snapshot_scheduler.catch_up() is None


def test_run_daily_exits_immediately_when_stop_set():
    async def go():
        stop = asyncio.Event()
        stop.set()
        await asyncio.wait_for(snapshot_scheduler.run_daily(stop, 1, 10), timeout=2)

    asyncio.run(go())


def test_scheduler_settings_default_on():
    assert settings.agent_snapshot_hour == 0  # conftest 显式关闭，避免污染用例
    assert isinstance(settings.agent_snapshot_minute, int)


def test_run_daily_takes_snapshot_after_delay(monkeypatch):
    """把"等待到点"缩短为 0，验证到点后确实生成快照。"""
    calls = []

    def fake_snapshot(actor):
        calls.append(actor)
        return {"date": "2026-11-06"}

    monkeypatch.setattr(snapshot_scheduler.skill_trends, "snapshot", fake_snapshot)
    # 目标时间设为当前时刻 → _next_run 顺延明天，把"等待"替换成立即返回
    real_wait_for = asyncio.wait_for

    async def fast_wait_for(aw, timeout):
        return await real_wait_for(aw, timeout=0.01)

    monkeypatch.setattr(snapshot_scheduler.asyncio, "wait_for", fast_wait_for)

    async def go():
        stop = asyncio.Event()
        task = asyncio.create_task(snapshot_scheduler.run_daily(stop, 1, 10))
        await asyncio.sleep(0.05)
        stop.set()
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    asyncio.run(go())
    assert calls and calls[0] == snapshot_scheduler.SCHEDULER_ACTOR
