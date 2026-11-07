"""模型调用并发闸门（评审 03-S8）：上限、排队超时与 HTTP 映射。"""
from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException

from backend.agents import model_gate
from backend.api import deps
from backend.config import settings


@pytest.fixture(autouse=True)
def _reset_gate():
    model_gate.reset()
    yield
    model_gate.reset()


def test_defaults_are_positive():
    """并发上限与排队超时必须为正；否则闸门形同虚设。"""
    assert settings.agent_model_max_concurrency >= 1
    assert settings.agent_model_queue_timeout_s > 0


def test_second_caller_times_out_when_limit_is_one(monkeypatch):
    monkeypatch.setattr(settings, "agent_model_max_concurrency", 1)
    monkeypatch.setattr(settings, "agent_model_queue_timeout_s", 0.05)

    async def scenario() -> None:
        holding = asyncio.Event()

        async def hold() -> None:
            async with model_gate.slot():
                holding.set()
                await asyncio.sleep(0.25)

        task = asyncio.create_task(hold())
        await holding.wait()
        with pytest.raises(model_gate.ModelBusy):
            async with model_gate.slot():
                pass
        await task

    asyncio.run(scenario())


def test_slots_allow_configured_parallelism(monkeypatch):
    """上限 2 时两个调用可并行；第三个排队超时（不是串行化一切）。"""
    monkeypatch.setattr(settings, "agent_model_max_concurrency", 2)
    monkeypatch.setattr(settings, "agent_model_queue_timeout_s", 0.05)

    async def scenario() -> None:
        in_flight = 0
        peak = 0
        both_entered = asyncio.Event()

        async def hold() -> None:
            nonlocal in_flight, peak
            async with model_gate.slot():
                in_flight += 1
                peak = max(peak, in_flight)
                if in_flight == 2:
                    both_entered.set()
                await asyncio.sleep(0.2)
                in_flight -= 1

        tasks = [asyncio.create_task(hold()) for _ in range(2)]
        await asyncio.wait_for(both_entered.wait(), timeout=0.15)
        with pytest.raises(model_gate.ModelBusy):
            async with model_gate.slot():
                pass
        await asyncio.gather(*tasks)
        assert peak == 2
        assert in_flight == 0

    asyncio.run(scenario())


def test_slot_releases_when_body_raises(monkeypatch):
    """调用体内抛错也必须释放槽位，否则一次异常会永久占用并发额度。"""
    monkeypatch.setattr(settings, "agent_model_max_concurrency", 1)
    monkeypatch.setattr(settings, "agent_model_queue_timeout_s", 0.05)

    async def scenario() -> None:
        with pytest.raises(RuntimeError):
            async with model_gate.slot():
                raise RuntimeError("boom")
        async with model_gate.slot():  # 槽位已释放，可再次进入
            pass

    asyncio.run(scenario())


def test_dependency_maps_busy_to_503(monkeypatch):
    monkeypatch.setattr(settings, "agent_model_max_concurrency", 1)
    monkeypatch.setattr(settings, "agent_model_queue_timeout_s", 0.05)

    async def scenario() -> None:
        async with model_gate.slot():
            gen = deps.model_slot()
            with pytest.raises(HTTPException) as excinfo:
                await gen.__anext__()
            assert excinfo.value.status_code == 503
            assert "繁忙" in excinfo.value.detail

    asyncio.run(scenario())


def test_dependency_yields_and_releases(monkeypatch):
    monkeypatch.setattr(settings, "agent_model_max_concurrency", 1)
    monkeypatch.setattr(settings, "agent_model_queue_timeout_s", 0.05)

    async def scenario() -> None:
        gen = deps.model_slot()
        await gen.__anext__()          # 进入：占一个槽位
        with pytest.raises(StopAsyncIteration):
            await gen.__anext__()      # 模拟请求结束：依赖收尾
        async with model_gate.slot():  # 收尾后槽位已归还
            pass

    asyncio.run(scenario())
