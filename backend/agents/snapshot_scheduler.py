"""日均指标快照的自动触发器（2026-11-06 评审 M-28 整改）。

整改前：``skill_trends.snapshot()`` 只有管理端按钮会调用，所谓"趋势"实际依赖
人工每天点一次；漏点一天，趋势就断档。

本模块提供两个动作：
1. ``catch_up()``：启动时若**今天还没有快照**，立即补一条（按日期主键幂等）；
2. ``run_daily()``：常驻协程，每天到点生成一次，直到 stop 事件被置位。

边界：
- 只写 ``skill_metrics_snapshots``（聚合指标），不写业务数据、不含 PHI；
- 多实例同时启动都调用 ``catch_up`` 也安全——``snapshot`` 按日期主键行级 upsert；
- 任一次失败只记日志，不阻塞服务；下一天继续尝试（不做无限重试）。
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta

from backend.agents import skill_trends
from backend.core.logger import get_logger

logger = get_logger(__name__)

SCHEDULER_ACTOR = "scheduler"


def _today() -> str:
    return datetime.now().astimezone().date().isoformat()


def _next_run(now: datetime, hour: int, minute: int) -> datetime:
    """下一次执行时间：今天的点已过则顺延到明天。"""
    target = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=1)
    return target


def catch_up(actor: str = SCHEDULER_ACTOR) -> dict | None:
    """今天没有快照就补一条；已存在则返回 None（幂等）。"""
    today = _today()
    try:
        existing = {s.get("date") for s in skill_trends.list_snapshots(days=2)}
    except Exception as exc:  # noqa: BLE001 —— 读失败不阻断启动，交给定时任务重试
        logger.warning("snapshot.catch_up.read_failed", error=type(exc).__name__)
        return None
    if today in existing:
        return None
    try:
        item = skill_trends.snapshot(actor)
        logger.info("snapshot.catch_up.created", date=item.get("date"))
        return item
    except Exception as exc:  # noqa: BLE001
        logger.warning("snapshot.catch_up.failed", error=type(exc).__name__)
        return None


async def run_daily(stop: asyncio.Event, hour: int, minute: int = 10) -> None:
    """每天 ``hour:minute`` 生成一次快照；stop 置位后立即退出。"""
    while not stop.is_set():
        now = datetime.now().astimezone()
        target = _next_run(now, hour, minute)
        delay = max(1.0, (target - now).total_seconds())
        try:
            await asyncio.wait_for(stop.wait(), timeout=delay)
            return  # stop 已置位
        except asyncio.TimeoutError:
            pass
        try:
            # 快照内部是同步 IO（可能触发 PG），放线程池避免阻塞事件循环
            item = await asyncio.to_thread(skill_trends.snapshot, SCHEDULER_ACTOR)
            logger.info("snapshot.scheduled.created", date=item.get("date"))
        except Exception as exc:  # noqa: BLE001 —— 单日失败不影响服务，次日再试
            logger.warning("snapshot.scheduled.failed", error=type(exc).__name__)
        # 防止同一分钟内重复触发（时钟回拨/极短 delay）
        try:
            await asyncio.wait_for(stop.wait(), timeout=60)
            return
        except asyncio.TimeoutError:
            pass
