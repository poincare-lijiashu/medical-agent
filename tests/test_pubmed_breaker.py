"""第 65 批：PubMed 熔断改为「连续失败计数」。

原实现：`except Exception: _PUBMED_BREAKER_UNTIL = now + 300`——**任一次**失败（网络抖动、
DNS 抖动、甚至偶发坏 JSON）都会让**所有用户**整整 5 分钟检索不到文献（单点失败全员降级）。
现实现：连续 3 次失败才熔断；中间成功一次即清零；熔断期内不发请求。
"""
import asyncio

import httpx
import pytest

from backend.agents.medical.literature import nodes as lit
from backend.config import settings


@pytest.fixture(autouse=True)
def _reset_breaker(monkeypatch):
    monkeypatch.setattr(lit, "_PUBMED_BREAKER_UNTIL", 0.0)
    monkeypatch.setattr(lit, "_PUBMED_FAIL_STREAK", 0)
    monkeypatch.setattr(lit, "_PUBMED_FAIL_THRESHOLD", 3)
    monkeypatch.setattr(lit, "_PUBMED_BREAKER_COOLDOWN", 300)
    monkeypatch.setattr(settings, "pubmed_enabled", True)


class _Boom:
    calls = 0

    def __init__(self, *a, **kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def get(self, *a, **kw):
        type(self).calls += 1
        raise httpx.ConnectError("boom")


class _Ok(_Boom):
    async def get(self, *a, **kw):
        type(self).calls += 1
        return httpx.Response(200, json={"esearchresult": {"idlist": ["1", "2"]}})


def _patch_client(monkeypatch, cls=_Boom):
    cls.calls = 0
    monkeypatch.setattr(lit.httpx, "AsyncClient", lambda *a, **kw: cls())


def test_single_failure_does_not_open_breaker(monkeypatch):
    """一次抖动不能熔断（原实现这里就已经全员降级 5 分钟）。"""
    _patch_client(monkeypatch)
    assert asyncio.run(lit._pubmed_ids("x")) == []
    assert lit._PUBMED_BREAKER_UNTIL == 0.0
    assert lit._PUBMED_FAIL_STREAK == 1

    assert asyncio.run(lit._pubmed_ids("x")) == []
    assert lit._PUBMED_BREAKER_UNTIL == 0.0, "第 2 次失败仍不应熔断"
    assert lit._PUBMED_FAIL_STREAK == 2


def test_threshold_opens_breaker_and_stops_calling(monkeypatch):
    _patch_client(monkeypatch)
    for _ in range(3):
        asyncio.run(lit._pubmed_ids("x"))
    assert lit._PUBMED_BREAKER_UNTIL > 0, "连续 3 次失败应熔断"
    before = _Boom.calls
    assert asyncio.run(lit._pubmed_ids("x")) == []
    assert _Boom.calls == before, "熔断期间不得再发请求"
    assert lit._PUBMED_FAIL_STREAK == 0, "触发后计数归零重新累计"


def test_success_resets_streak(monkeypatch):
    """连续失败之间夹一次成功 → 计数清零，抗抖动。"""
    _patch_client(monkeypatch)
    asyncio.run(lit._pubmed_ids("x"))
    asyncio.run(lit._pubmed_ids("x"))
    assert lit._PUBMED_FAIL_STREAK == 2

    _patch_client(monkeypatch, _Ok)
    assert asyncio.run(lit._pubmed_ids("x")) == ["1", "2"]
    assert lit._PUBMED_FAIL_STREAK == 0
    assert lit._PUBMED_BREAKER_UNTIL == 0.0


def test_pubmed_disabled_short_circuits_without_request(monkeypatch):
    monkeypatch.setattr(settings, "pubmed_enabled", False)
    _patch_client(monkeypatch)
    assert asyncio.run(lit._pubmed_ids("x")) == []
    assert _Boom.calls == 0, "离网开关应连请求都不发"
    assert lit._PUBMED_FAIL_STREAK == 0
