"""轻量可观测：HTTP 请求计数（按状态码）+ 时延直方图（Prometheus 文本导出）。

命名遵循 Prometheus 约定（评测 06 P1-5 整改）：

- ``medassist_http_requests_total{route,method,status}``：**一个指标一个名字**，
  状态码/方法是标签维度。旧实现把语义塞进 ``name`` 标签（``medassist_counter_total``），
  ``rate()`` / ``sum by`` 都得先 ``sum by (name)`` 才能用，语义反直觉；
- ``medassist_http_request_duration_seconds_{bucket,sum,count}``：**标准 histogram**，
  可直接 ``histogram_quantile(0.95, ...)`` 算 P95/P99（旧实现只有 sum/count，只能算均值）；
- 单位统一为秒（Prometheus base unit），与 ``medassist_agent_consult_duration_seconds`` 一致；
- ``route`` 取 FastAPI 路由模板防高基数；运行时未匹配的路由归为 ``unmatched``
  （扫描器打随机路径不会撑爆标签基数）。

多实例/多 worker 仍是**进程内**计数（跨进程不聚合），部署形态可见性见
``medassist_agent_metrics_worker_count`` 与其告警。
"""
from __future__ import annotations

import bisect
import threading
from collections import defaultdict

_lock = threading.Lock()
# (route, method, status) -> 次数
_requests: dict[tuple[str, str, str], int] = defaultdict(int)
# 直方图桶（秒）：覆盖 5ms–30s，覆盖 AI 接口（LLM 长请求）与普通 CRUD
BUCKETS: tuple[float, ...] = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25,
                              0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
_bucket_counts: dict[str, list[int]] = {}
_lat_sum_s: dict[str, float] = defaultdict(float)
_lat_count: dict[str, int] = defaultdict(int)

REQUESTS_METRIC = "medassist_http_requests_total"
DURATION_METRIC = "medassist_http_request_duration_seconds"


def inc_http(route: str, method: str, status: int | str) -> None:
    """记录一次 HTTP 请求（route 用路由模板，不要传真实路径）。"""
    key = (str(route or "unmatched"), str(method or "?").upper(), str(status))
    with _lock:
        _requests[key] += 1


def observe_seconds(route: str, seconds: float) -> None:
    """记录一次请求耗时（秒），落到 histogram 桶。"""
    r = str(route or "unmatched")
    s = max(0.0, float(seconds))
    idx = bisect.bisect_left(BUCKETS, s)  # 第一个 >= s 的桶（le 语义）
    with _lock:
        counts = _bucket_counts.get(r)
        if counts is None:
            counts = [0] * len(BUCKETS)
            _bucket_counts[r] = counts
        if idx < len(BUCKETS):
            counts[idx] += 1
        _lat_sum_s[r] += s
        _lat_count[r] += 1


def snapshot() -> dict:
    """进程内快照（测试/排障用；不做跨进程聚合）。"""
    with _lock:
        return {
            "requests": [
                {"route": r, "method": m, "status": st, "count": n}
                for (r, m, st), n in sorted(_requests.items())
            ],
            "latency": {
                r: {"sum_s": _lat_sum_s[r], "count": _lat_count[r],
                    "buckets": list(_bucket_counts.get(r, [0] * len(BUCKETS)))}
                for r in sorted(_lat_count)
            },
        }


def _esc(v: object) -> str:
    return str(v).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


def _le(v: float) -> str:
    return f"{v:g}"


def exposition() -> str:
    """Prometheus 文本格式（counter + 标准 histogram）。"""
    snap = snapshot()
    lines = [
        f"# HELP {REQUESTS_METRIC} HTTP 请求次数（按路由模板/方法/状态码）",
        f"# TYPE {REQUESTS_METRIC} counter",
    ]
    for row in snap["requests"]:
        lines.append(
            f'{REQUESTS_METRIC}{{route="{_esc(row["route"])}",'
            f'method="{_esc(row["method"])}",status="{_esc(row["status"])}"}} {row["count"]}'
        )
    lines.append(f"# HELP {DURATION_METRIC} HTTP 请求耗时（秒）")
    lines.append(f"# TYPE {DURATION_METRIC} histogram")
    for route, m in snap["latency"].items():
        cumulative = 0
        for le, c in zip(BUCKETS, m["buckets"]):
            cumulative += c
            lines.append(f'{DURATION_METRIC}_bucket{{route="{_esc(route)}",'
                         f'le="{_le(le)}"}} {cumulative}')
        lines.append(f'{DURATION_METRIC}_bucket{{route="{_esc(route)}",le="+Inf"}} {m["count"]}')
        lines.append(f'{DURATION_METRIC}_sum{{route="{_esc(route)}"}} {m["sum_s"]:.6f}')
        lines.append(f'{DURATION_METRIC}_count{{route="{_esc(route)}"}} {m["count"]}')
    return "\n".join(lines) + "\n"


def reset() -> None:
    with _lock:
        _requests.clear()
        _bucket_counts.clear()
        _lat_sum_s.clear()
        _lat_count.clear()
