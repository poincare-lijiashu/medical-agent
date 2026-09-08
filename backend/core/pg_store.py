"""Postgres 持久层（合规镜像，best-effort 双写）。

设计：JSON 仍是热读模型（零回归）；PG 作为**可查询的持久镜像**（users/audit/review_queue）。
所有 mirror 写：PG 不可用→静默 no-op；异常→仅告警，绝不冒泡影响请求。lifespan 建表。
"""
from __future__ import annotations

import json
from datetime import datetime, timezone

import asyncpg

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)
_pool = None


def _dsn() -> str:
    return settings.async_database_url.replace("postgresql+asyncpg://", "postgresql://")


async def init() -> bool:
    global _pool
    try:
        _pool = await asyncpg.create_pool(_dsn(), min_size=1, max_size=5, timeout=5)
        async with _pool.acquire() as c:
            await c.execute("""CREATE TABLE IF NOT EXISTS audit_log(
                id bigserial primary key, ts timestamptz default now(),
                event_type text, actor text, payload jsonb)""")
            await c.execute("""CREATE TABLE IF NOT EXISTS users(
                username text primary key, role text, password_hash text,
                created_at timestamptz default now())""")
            await c.execute("""CREATE TABLE IF NOT EXISTS review_queue(
                id text primary key, ts text, agent text, question text, answer text,
                confidence double precision, risk_reason text, status text,
                submitted_by text, reviewed_by text, review_note text, resolved_at text,
                updated_at timestamptz default now())""")
        logger.info("pg_store.ready")
        return True
    except Exception as e:  # noqa: BLE001
        logger.warning("pg_store.unavailable", error=str(e)[:150])
        _pool = None
        return False


async def close() -> None:
    global _pool
    if _pool:
        try:
            await _pool.close()
        except Exception:  # noqa: BLE001
            pass
    _pool = None


def available() -> bool:
    return _pool is not None


async def ping() -> bool:
    """只读连通性探针（admin 运维卡片用）：SELECT 1，任何异常/未建池一律 False。
    绝不写库、绝不重试（面板展示需快速失败），与 mirror_* 的 best-effort 写路径互不影响。"""
    if not _pool:
        return False
    try:
        async with _pool.acquire() as c:
            return bool(await c.fetchval("SELECT 1"))
    except Exception:  # noqa: BLE001 —— 探针只报状态，绝不冒泡
        return False


async def mirror_audit(event_type: str, actor: str, payload: dict) -> None:
    if not _pool:
        return
    try:
        async with _pool.acquire() as c:
            await c.execute("INSERT INTO audit_log(event_type,actor,payload) VALUES($1,$2,$3::jsonb)",
                            event_type, actor, json.dumps(payload, ensure_ascii=False))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_audit_failed", error=str(e)[:120])


async def mirror_user(username: str, role: str, pw_hash: str) -> None:
    if not _pool:
        return
    try:
        async with _pool.acquire() as c:
            await c.execute("INSERT INTO users(username,role,password_hash) VALUES($1,$2,$3) "
                            "ON CONFLICT(username) DO UPDATE SET role=EXCLUDED.role, password_hash=EXCLUDED.password_hash",
                            username, role, pw_hash)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_user_failed", error=str(e)[:120])


async def mirror_review(item: dict | None) -> None:
    if not _pool or not item:
        return
    try:
        async with _pool.acquire() as c:
            await c.execute(
                """INSERT INTO review_queue(id,ts,agent,question,answer,confidence,risk_reason,
                   status,submitted_by,reviewed_by,review_note,resolved_at)
                   VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
                   ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status, reviewed_by=EXCLUDED.reviewed_by,
                   review_note=EXCLUDED.review_note, resolved_at=EXCLUDED.resolved_at, updated_at=now()""",
                item.get("id"), item.get("ts"), item.get("agent"), item.get("question"),
                item.get("answer"), item.get("confidence"), item.get("risk_reason"),
                item.get("status", "pending"), item.get("submitted_by"),
                item.get("reviewed_by"), item.get("review_note"), item.get("resolved_at"))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_review_failed", error=str(e)[:120])


async def counts() -> dict:
    """验证用：返回各表行数（PG 不可用返回空）。"""
    if not _pool:
        return {}
    out = {}
    async with _pool.acquire() as c:
        for t in ("audit_log", "users", "review_queue"):
            try:
                out[t] = await c.fetchval(f"SELECT count(*) FROM {t}")  # noqa: S608 (固定表名)
            except Exception:  # noqa: BLE001
                out[t] = -1
    return out


async def purge_pending_reviews() -> str:
    """镜像一致性：清空 review_queue 中 pending 行（与 review.purge_pending 配套）。best-effort。"""
    if not _pool:
        return ""
    try:
        async with _pool.acquire() as c:
            return await c.execute("DELETE FROM review_queue WHERE status='pending'")
    except Exception:  # noqa: BLE001
        return ""
