"""轻量可观测：进程内计数器 + 时延直方图（Prometheus 文本导出）。多实例应换 OpenTelemetry/Prometheus。"""
from __future__ import annotations

import threading
from collections import defaultdict

_lock = threading.Lock()
_counters: dict[str, int] = defaultdict(int)
_lat_sum_ms: dict[str, float] = defaultdict(float)
_lat_count: dict[str, int] = defaultdict(int)


def inc(name: str, value: int = 1) -> None:
    with _lock:
        _counters[name] += value


def observe_ms(route: str, ms: float) -> None:
    with _lock:
        _lat_sum_ms[route] += ms
        _lat_count[route] += 1


def snapshot() -> dict:
    with _lock:
        return {
            "counters": dict(_counters),
            "latency": {r: {"sum_ms": _lat_sum_ms[r], "count": _lat_count[r],
                            "avg_ms": (_lat_sum_ms[r] / _lat_count[r] if _lat_count[r] else 0)}
                        for r in _lat_count},
        }


def exposition() -> str:
    """Prometheus 文本格式。"""
    s = snapshot()
    lines = []
    for k, v in s["counters"].items():
        lines.append(f'medassist_counter_total{{name="{k}"}} {v}')
    for r, m in s["latency"].items():
        lines.append(f'medassist_latency_ms_sum{{route="{r}"}} {m["sum_ms"]:.1f}')
        lines.append(f'medassist_latency_ms_count{{route="{r}"}} {m["count"]}')
    return "\n".join(lines) + "\n"


def reset() -> None:
    with _lock:
        _counters.clear()
        _lat_sum_ms.clear()
        _lat_count.clear()
