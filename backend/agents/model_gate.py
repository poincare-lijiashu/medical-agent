"""模型调用并发闸门（评审 03-S8）。

为什么在"限流 + 每用户配额"之外再加一层：
- 通用限流防的是刷接口；每用户配额防的是单用户把算力吃满；
- 但几十个用户同时各发 1 次，仍可能把共享 GPU 上的本地模型压到所有回答一起变慢；
- 本模块限制**进程内同时进行**的模型调用数：满额时短暂排队，排队超时显式 503，
  而不是无限等待（客户端能明确知道"忙"，而不是页面一直转圈）。

边界：这是单进程内的信号量，多副本部署时每个副本各自计数；需要全局上限应在网关/模型
服务侧限流。本模块不排队公平性、不做优先级，只做"别把模型打爆"。
"""
from __future__ import annotations

import asyncio

from backend.config import settings


class ModelBusy(RuntimeError):
    """并发闸门已满且排队超时。"""


_gate: asyncio.Semaphore | None = None
_limit: int = 0


def _configured_limit() -> int:
    try:
        value = int(getattr(settings, "agent_model_max_concurrency", 4) or 4)
    except (TypeError, ValueError):
        value = 4
    return max(1, value)


def _semaphore() -> asyncio.Semaphore:
    global _gate, _limit
    limit = _configured_limit()
    if _gate is None or limit != _limit:
        # 运行期改配置（测试/热更）时重建；仅在旧信号量无等待者时安全（生产不热改）
        _gate, _limit = asyncio.Semaphore(limit), limit
    return _gate


def reset() -> None:
    """重置闸门（测试用；生产不要调用，会把正在排队的请求丢在旧信号量上）。"""
    global _gate, _limit
    _gate, _limit = None, 0


class _Slot:
    async def __aenter__(self) -> None:
        try:
            timeout = float(getattr(settings, "agent_model_queue_timeout_s", 10.0) or 0)
        except (TypeError, ValueError):
            timeout = 10.0
        try:
            await asyncio.wait_for(_semaphore().acquire(),
                                   timeout=timeout if timeout > 0 else None)
        except asyncio.TimeoutError as e:
            raise ModelBusy("模型调用繁忙，请稍后再试") from e

    async def __aexit__(self, exc_type, exc, tb) -> None:
        _semaphore().release()


def slot() -> _Slot:
    """占用一个模型调用槽位（异步上下文管理器）。"""
    return _Slot()
