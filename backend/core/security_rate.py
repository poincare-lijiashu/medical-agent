"""限流：默认进程内滑动窗口（单机，防登录/查询滥用）；REDIS_URL 配置后切换 Redis 固定窗口计数（多实例共享聚合）。

轮 B2 双后端：计数存储统一收口到 backend/core/stores.py 的 KVStore 抽象——
redis_url 为空时内存滑窗路径行为与单机版完全一致（零改变）；非空时计数落共享存储，
Redis 不可用由 RedisStore 自动回落本实例内存镜像（限流语义降级为单实例，服务不中断）。
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

from backend.config import settings
from backend.core.stores import get_store

_lock = threading.Lock()
_hits: dict[str, deque] = defaultdict(deque)
_last_sweep = 0.0


def allow(key: str, limit: int = 60, window: int = 60) -> bool:
    """key 在 window 秒内是否仍 < limit 次。超限返回 False。

    双后端：REDIS_URL 未配置 → 进程内滑动窗口（默认，多实例不共享）；
    配置后 → Redis 固定窗口计数（多实例聚合计数）。
    每 256 次调用清扫一次全表（仅内存路径），回收一次性 IP/过期 key 的空桶
    （防内存无界增长）。"""
    if (settings.redis_url or "").strip():
        return _allow_shared(key, limit, window)
    return _allow_memory(key, limit, window)


def _allow_shared(key: str, limit: int, window: int) -> bool:
    """Redis 固定窗口：计数与过期都在共享存储上（window 对齐时间片，多实例聚合）。
    计数操作失败由 RedisStore 内部回落内存镜像——降级为单实例语义，不抛错。"""
    store = get_store()
    bucket = f"rl:{key}:{int(time.time() // window)}"  # wallclock：跨实例时钟对齐
    count = store.incr(bucket)
    if count == 1:
        store.expire(bucket, window)  # 首次写入定生死：window 秒后整桶过期，防无界
    return count <= limit


def _allow_memory(key: str, limit: int, window: int) -> bool:
    """进程内滑动窗口（默认路径，逻辑与单机版一致）。"""
    now = time.monotonic()
    with _lock:
        if len(_hits) > 256 and now - _last_sweep > 60:
            _last_sweep = now
            for k in [k for k, dq in _hits.items() if not dq or now - dq[-1] > window]:
                _hits.pop(k, None)
        dq = _hits[key]
        while dq and now - dq[0] > window:
            dq.popleft()
        if len(dq) >= limit:
            return False
        dq.append(now)
        return True


def reset() -> None:
    with _lock:
        _hits.clear()
