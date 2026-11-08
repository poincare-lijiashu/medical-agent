"""审计 action 回填（第 112 批）：历史 audit_log 行缺 payload.action。

背景：镜像事件名是"域.动作"（review.approved），旧实现只写 event_type、不写 payload.action，
导致 admin 审计流的"按动作过滤/候选值"在 PG 部署下恒空（第 107–110 批交付的 action 维度
实际不可用）。新写入已自动补 action；本脚本回填历史行。

用法：
    python scripts/backfill_audit_action.py            # dry-run：只报告将要写入什么
    python scripts/backfill_audit_action.py --apply    # 真正执行（事务，幂等）
    python scripts/backfill_audit_action.py --dsn postgresql://...
"""
import argparse
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import asyncpg  # noqa: E402

from backend.config import settings  # noqa: E402
from backend.core.pg_store import _ACTION_ALIASES, audit_action_for  # noqa: E402


def build_update_sql() -> str:
    """幂等 UPDATE：仅补 `payload->>'action' IS NULL` 的行；CASE 与 audit_action_for 同口径。

    无点号事件（如 `login`）→ 取整个 event_type；有点号 → 取第二段。
    """
    whens = " ".join(f"WHEN '{k}' THEN '{v}'" for k, v in sorted(_ACTION_ALIASES.items()))
    return (
        "UPDATE audit_log SET payload = coalesce(payload, '{}'::jsonb) "
        "|| jsonb_build_object('action', CASE event_type "
        f"{whens} "
        "ELSE coalesce(nullif(split_part(event_type, '.', 2), ''), event_type) END) "
        "WHERE payload->>'action' IS NULL"
    )


def planned(rows) -> list[tuple[str, str, int]]:
    """[(event_type, 将写入的 action, 行数)]，与 SQL 的 CASE 口径一致（dry-run 用）。"""
    return [(r["event_type"], audit_action_for(r["event_type"]), int(r["n"])) for r in rows]


async def _run(dsn: str, apply: bool) -> int:
    conn = await asyncpg.connect(dsn)
    try:
        rows = await conn.fetch(
            "SELECT event_type, count(*) AS n FROM audit_log "
            "WHERE payload->>'action' IS NULL GROUP BY 1 ORDER BY 2 DESC, 1 ASC")
        plan = planned(rows)
        total = sum(n for _, _, n in plan)
        print(f"待回填 {total} 行 / {len(plan)} 类事件")
        for ev, act, n in plan:
            print(f"  {ev:36s} -> {act:24s} {n} 行")
        if not total:
            print("无需回填（幂等）")
            return 0
        if not apply:
            print("dry-run：未写入。加 --apply 执行。")
            return 0
        status = await conn.execute(build_update_sql())
        print("已执行:", status)
        left = await conn.fetchval(
            "SELECT count(*) FROM audit_log WHERE payload->>'action' IS NULL")
        print("剩余缺少 action 的行:", left)
        return 0
    finally:
        await conn.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="回填 audit_log.payload.action（默认 dry-run）")
    ap.add_argument("--apply", action="store_true", help="真正执行 UPDATE（默认只报告）")
    ap.add_argument("--dsn", default="", help="覆盖 DSN（默认取 settings）")
    args = ap.parse_args(argv)
    dsn = args.dsn or settings.async_database_url.replace("postgresql+asyncpg://", "postgresql://")
    return asyncio.run(_run(dsn, args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
