"""G102 统一问答运行计量（Prometheus 文本导出）。

只做**进程内聚合计数**，不落库、不记录问题/回答原文：
- ``medassist_agent_consult_requests_total{agent,channel,status}``：问答次数，
  status 取 ok / error / aborted；channel 取 sync / stream；
- ``medassist_agent_consult_duration_ms_sum|count{channel}``：耗时合计与次数；
- ``medassist_agent_skill_usage_total{skill}``：成功问答中各生效技能卡的次数
  （同一请求内同编码只计 1 次）；
- ``medassist_agent_skill_last_used_timestamp_seconds{skill}``：技能最近一次成功
  使用的时间戳（进程内，重启归零）；
- ``medassist_agent_consult_tokens_total{kind="input|output"}``：成功问答的 token
  用量（仅 provider 真实返回 usage 时累计，**绝不估算**）；
- ``medassist_agent_consult_tokenized_requests_total``：返回了 usage 的成功问答数。

边界：
- 多实例部署时由 Prometheus 按实例聚合；进程重启计数归零（标准 counter 语义）；
- **多 worker 进程**（同一端口下 ``WEB_CONCURRENCY/UVICORN_WORKERS > 1``）时，
  每次抓取可能落到不同进程，counter 会来回跳变、被 Prometheus 判为 reset，
  ``rate()`` 结果不可信（评审 M-23）。本模块导出
  ``medassist_agent_metrics_worker_count`` 让该形态**可见**，并在启动日志告警；
  生产要么固定单 worker，要么接受该限制（多进程聚合需改为 multiprocess 模式，未支持）；
- 技能标签上限 200 个，超出归入 ``__other__``，防标签基数失控；
- 只在问答成功（含前置应答）时累计技能使用；失败/中止不计入使用量。
"""
from __future__ import annotations

import os
import threading
import time
from collections import defaultdict

MAX_SKILL_LABELS = 200
OTHER_SKILL = "__other__"
OTHER_AGENT = "__other__"
MAX_AGENT_LABELS = 64
ERROR_REASONS = ("invalid", "forbidden", "unavailable", "timeout", "other")

_lock = threading.Lock()
_requests: dict[tuple[str, str, str], int] = defaultdict(int)
_errors: dict[tuple[str, str], int] = defaultdict(int)
_durations: dict[str, list] = defaultdict(lambda: [0.0, 0])
_skill_usage: dict[str, int] = defaultdict(int)
_skill_last_used: dict[str, float] = {}
_tokens = {"input": 0, "output": 0}
_tokenized_requests = 0


def _worker_count() -> int:
    """读取部署侧的 worker 数（未配置视为 1）。评审 M-23：把多进程形态显式化。"""
    for key in ("WEB_CONCURRENCY", "UVICORN_WORKERS", "GUNICORN_WORKERS"):
        raw = (os.environ.get(key) or "").strip()
        if raw:
            try:
                return max(1, int(raw))
            except ValueError:
                continue
    return 1


def worker_count() -> int:
    """部署侧 worker 数（公开给启动自检/文档使用）。"""
    return _worker_count()


def _esc(value: str) -> str:
    """Prometheus 标签值转义（反斜杠/引号/换行）。"""
    return (str(value).replace("\\", "\\\\").replace('"', '\\"')
            .replace("\n", "\\n"))


def _normalize_tokens(tokens) -> dict | None:
    """只接受 provider 明确给出的输入/输出 token；缺项或非法一律视为未提供。"""
    if not isinstance(tokens, dict):
        return None
    try:
        inp = int(tokens.get("input_tokens"))
        out = int(tokens.get("output_tokens"))
    except (TypeError, ValueError):
        return None
    if inp < 0 or out < 0:
        return None
    return {"input_tokens": inp, "output_tokens": out}


def _agent_label(agent_id: str) -> str:
    """把 agent 标签收敛到目录内的已知能力域（评审 M-22 整改）。

    `agentId` 可由调用方显式指定，若直接作为标签值会出现任意基数。这里只接受
    catalog 中真实存在的 id 以及两个内部值，其余统一归入 `__other__`。
    """
    aid = (agent_id or "unknown").strip()[:64]
    if aid in ("unknown", "prefilter"):
        return aid
    try:
        from backend.agents import catalog
        return aid if catalog.get_agent(aid) is not None else OTHER_AGENT
    except Exception:  # noqa: BLE001 —— 目录不可用时宁可归并，也不放大基数
        return OTHER_AGENT


def record(agent_id: str, channel: str, status: str, duration_ms: float,
           skill_codes=(), tokens=None, reason: str | None = None) -> None:
    """记录一次问答结果；``skill_codes`` 与 ``tokens`` 只在 status=ok 时累计。

    评审 M-25 整改：错误按 `reason` 细分（invalid/forbidden/unavailable/timeout/other），
    避免"用户输入非法"与"模型不可用"混在同一个 error 计数里无法定位。
    """
    global _tokenized_requests
    agent = _agent_label(agent_id)
    ch = channel if channel in ("sync", "stream") else "sync"
    st = status if status in ("ok", "error", "aborted") else "error"
    ms = max(float(duration_ms or 0), 0.0)
    usage = _normalize_tokens(tokens) if st == "ok" else None
    rs = "" if st != "error" else (reason if reason in ERROR_REASONS else "other")
    with _lock:
        _requests[(agent, ch, st)] += 1
        if st == "error":
            _errors[(agent, rs)] += 1
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
            if usage is not None:
                _tokens["input"] += usage["input_tokens"]
                _tokens["output"] += usage["output_tokens"]
                _tokenized_requests += 1


def snapshot() -> dict:
    with _lock:
        return {
            "requests": {f"{a}|{c}|{s}": n for (a, c, s), n in _requests.items()},
            "errors": {f"{a}|{r}": n for (a, r), n in _errors.items()},
            "durations": {c: {"sum_ms": v[0], "count": v[1]}
                          for c, v in _durations.items()},
            "skills": dict(_skill_usage),
            "skill_last_used": dict(_skill_last_used),
            "tokens": {"input": _tokens["input"], "output": _tokens["output"],
                       "requests": _tokenized_requests},
        }


def prometheus_text() -> str:
    with _lock:
        lines = [
            # 评审 M-23：多 worker 时进程内 counter 不可信，先把部署形态暴露出来
            "# HELP medassist_agent_metrics_worker_count 部署侧 worker 进程数（>1 时进程内计数不适用于 rate）",
            "# TYPE medassist_agent_metrics_worker_count gauge",
            f"medassist_agent_metrics_worker_count {_worker_count()}",
            "# HELP medassist_agent_consult_requests_total 统一问答请求次数",
            "# TYPE medassist_agent_consult_requests_total counter",
        ]
        for (agent, ch, st), n in sorted(_requests.items()):
            lines.append(
                f'medassist_agent_consult_requests_total{{agent="{_esc(agent)}",'
                f'channel="{_esc(ch)}",status="{_esc(st)}"}} {n}')
        lines.extend([
            "# HELP medassist_agent_consult_errors_total 统一问答失败次数（按原因细分）",
            "# TYPE medassist_agent_consult_errors_total counter",
        ])
        for (agent, reason), n in sorted(_errors.items()):
            lines.append(
                f'medassist_agent_consult_errors_total{{agent="{_esc(agent)}",'
                f'reason="{_esc(reason)}"}} {n}')
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
        lines.extend([
            "# HELP medassist_agent_consult_tokens_total 成功问答的 token 用量（仅 provider 返回时累计）",
            "# TYPE medassist_agent_consult_tokens_total counter",
            f'medassist_agent_consult_tokens_total{{kind="input"}} {_tokens["input"]}',
            f'medassist_agent_consult_tokens_total{{kind="output"}} {_tokens["output"]}',
            "# HELP medassist_agent_consult_tokenized_requests_total 返回了 token 用量的成功问答数",
            "# TYPE medassist_agent_consult_tokenized_requests_total counter",
            f'medassist_agent_consult_tokenized_requests_total {_tokenized_requests}',
        ])
        return "\n".join(lines) + "\n"


def reset() -> None:
    global _tokenized_requests
    with _lock:
        _requests.clear()
        _errors.clear()
        _durations.clear()
        _skill_usage.clear()
        _skill_last_used.clear()
        _tokens["input"] = _tokens["output"] = 0
        _tokenized_requests = 0
