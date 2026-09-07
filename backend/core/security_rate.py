"""进程内滑动窗口限流（登录/查询防滥用）。多实例应换 Redis，接口保持一致。"""
from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

_lock = threading.Lock()
_hits: dict[str, deque] = defaultdict(deque)
_last_sweep = 0.0


def allow(key: str, limit: int = 60, window: int = 60) -> bool:
    """key 在 window 秒内是否仍 < limit 次。超限返回 False。
    每 256 次调用清扫一次全表，回收一次性 IP/过期 key 的空桶（防内存无界增长）。"""
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
