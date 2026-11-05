"""G102 统一问答运行计量（Prometheus 文本导出）。

只做**进程内聚合计数**，不落库、不记录问题/回答原文：
- ``medassist_agent_consult_requests_total{agent,channel,status}``：问答次数，
  status 取 ok / error / aborted；channel 取 sync / stream；
- ``medassist_agent_consult_duration_ms_sum|count{channel}``：耗时合计与次数；
- ``medassist_agent_skill_usage_total{skill}``：成功问答中各生效技能卡的次数
  （同一请求内同编码只计 1 次）；
- ``medassist_agent_skill_last_used_timestamp_seconds{skill}``：技能最近一次成功
  使用的时间戳（进程内，重启归零）。

边界：
- 多实例部署时由 Prometheus 按实例聚合；进程重启计数归零（标准 counter 语义）；
- 技能标签上限 200 个，超出归入 ``__other__``，防标签基数失控；
- 只在问答成功（含前置应答）时累计技能使用；失败/中止不计入使用量。
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict

MAX_SKILL_LABELS = 200
OTHER_SKILL = "__other__"

_lock = threading.Lock()
_requests: dict[tuple[str, str, str], int] = defaultdict(int)
_durations: dict[str, list] = defaultdict(lambda: [0.0, 0])
_skill_usage: dict[str, int] = defaultdict(int)
_skill_last_used: dict[str, float] = {}


def _esc(value: str) -> str:
    """Prometheus 标签值转义（反斜杠/引号/换行）。"""
    return (str(value).replace("\\", "\\\\").replace('"', '\\"')
            .replace("\n", "\\n"))


def record(agent_id: str, channel: str, status: str, duration_ms: float,
           skill_codes=()) -> None:
    """记录一次问答结果；``skill_codes`` 只在 status=ok 时累计。"""
    agent = (agent_id or "unknown")[:64]
    ch = channel if channel in ("sync", "stream") else "sync"
    st = status if status in ("ok", "error", "aborted") else "error"
    ms = max(float(duration_ms or 0), 0.0)
    with _lock:
        _requests[(agent, ch, st)] += 1
        bucket = _durations[ch]
        bucket[0] += ms
        bucket[1] += 1
        if st == "ok":
            now = time.time()
            for code in dict.fromkeys(skill_codes or ()):
                key = str(code)[:64]
                if key not in _skill_usage and len(_skill_usage) >= MAX_SKILL_LABELS:
                    key = OTHER_SKILL
                _skill_usage[key] += 1
                _skill_last_used[key] = now


def snapshot() -> dict:
    with _lock:
        return {
            "requests": {f"{a}|{c}|{s}": n for (a, c, s), n in _requests.items()},
            "durations": {c: {"sum_ms": v[0], "count": v[1]}
                          for c, v in _durations.items()},
            "skills": dict(_skill_usage),
            "skill_last_used": dict(_skill_last_used),
        }


def prometheus_text() -> str:
    with _lock:
        lines = [
            "# HELP medassist_agent_consult_requests_total 统一问答请求次数",
            "# TYPE medassist_agent_consult_requests_total counter",
        ]
        for (agent, ch, st), n in sorted(_requests.items()):
            lines.append(
                f'medassist_agent_consult_requests_total{{agent="{_esc(agent)}",'
                f'channel="{_esc(ch)}",status="{_esc(st)}"}} {n}')
        lines.extend([
            "# HELP medassist_agent_consult_duration_ms_sum 统一问答耗时合计（毫秒）",
            "# TYPE medassist_agent_consult_duration_ms_sum counter",
        ])
        for ch, v in sorted(_durations.items()):
            lines.append(
                f'medassist_agent_consult_duration_ms_sum{{channel="{_esc(ch)}"}} {v[0]:.1f}')
        lines.extend([
            "# HELP medassist_agent_consult_duration_ms_count 统一问答计时次数",
            "# TYPE medassist_agent_consult_duration_ms_count counter",
        ])
        for ch, v in sorted(_durations.items()):
            lines.append(
                f'medassist_agent_consult_duration_ms_count{{channel="{_esc(ch)}"}} {v[1]}')
        lines.extend([
            "# HELP medassist_agent_skill_usage_total 生效技能卡在成功问答中的使用次数",
            "# TYPE medassist_agent_skill_usage_total counter",
        ])
        for code, n in sorted(_skill_usage.items()):
            lines.append(
                f'medassist_agent_skill_usage_total{{skill="{_esc(code)}"}} {n}')
        lines.extend([
            "# HELP medassist_agent_skill_last_used_timestamp_seconds 技能最近一次成功使用时间",
            "# TYPE medassist_agent_skill_last_used_timestamp_seconds gauge",
        ])
        for code, ts in sorted(_skill_last_used.items()):
            lines.append(
                f'medassist_agent_skill_last_used_timestamp_seconds'
                f'{{skill="{_esc(code)}"}} {ts:.3f}')
        return "\n".join(lines) + "\n"


def reset() -> None:
    with _lock:
        _requests.clear()
        _durations.clear()
        _skill_usage.clear()
        _skill_last_used.clear()
