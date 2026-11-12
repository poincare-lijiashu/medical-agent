"""模型调用计数的进程内账本（§290）。

为什么单独放在 core 层
----------------------
计量的触发点是 `backend/core/llm_factory.py` 的 HTTP 响应钩子，而**持久化与上报**
属于编排层（`backend/agents/ai_usage.py`）。若 core 直接 import agents，会违反
`docs/ARCHITECTURE.md` 的依赖方向规则（core 不得反向依赖 agents）。

因此这里只做最小职责：**按业务日累加计数**（无 IO、无配置、无上报）。
`agents.ai_usage` 负责读取快照、落 PG/JSON 台账并推送授权服务。

口径
----
- 由调用方给出业务日（UTC `yyyy-MM-dd`），计 1 次；
- 只保留最近两天的内存键：更早的增量已由 `ai_usage` 落盘，留着只会反复重写；
- 进程重启计数归零是预期行为（重启后由新的进程实例行继续累计，合计不丢不重）。
"""
from __future__ import annotations

import threading
from datetime import date, datetime, timedelta, timezone

_lock = threading.Lock()
_counts: dict[str, int] = {}


def utc_today() -> str:
    """UTC 业务日（与授权服务、上报日期同一口径）。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def record(day: str) -> None:
    """记一次成功的模型调用；``day`` 为空时忽略（调用方参数错误不应污染台账）。"""
    if not day:
        return
    with _lock:
        _counts[day] = _counts.get(day, 0) + 1
        try:
            cutoff = (date.fromisoformat(day) - timedelta(days=1)).isoformat()
        except ValueError:
            return
        for key in [k for k in _counts if k < cutoff]:
            _counts.pop(key, None)


def count(day: str) -> int:
    with _lock:
        return int(_counts.get(day, 0))


def counts() -> dict[str, int]:
    """当前进程全部日的计数快照（调用方不得修改返回的字典）。"""
    with _lock:
        return dict(_counts)


def reset_for_tests() -> None:
    """测试专用：清零（生产不调用）。"""
    with _lock:
        _counts.clear()
