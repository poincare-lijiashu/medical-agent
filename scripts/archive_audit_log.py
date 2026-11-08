"""审计日志归档（第 117 批）：把早于指定日期的 audit_log 行按月导出后删除。

为什么需要：审计表只增不减（本机已 2.2k 行，生产会到千万级），合规要求"可追溯"不等于
"必须一直放在热表里"。归档 = 冷存 JSONL（按月一份）+ 热表清理，保留期由运维按院内制度定。

安全设计：
- **默认 dry-run**：只统计"按月多少行、将写哪些文件"，不写不删；
- `--apply` 才执行：单事务内 `DELETE ... RETURNING`，**先落盘成功再 COMMIT**，
  落盘异常自动 ROLLBACK（不会出现"删了但没存档"）；
- 必须显式给 `--before YYYY-MM-DD`（按服务端本地时区解释，口径与审计日期筛选一致）；
- 按月分文件、追加写：同一月多次归档不会覆盖历史文件。

用法：
    python scripts/archive_audit_log.py --before 2026-01-01            # dry-run
    python scripts/archive_audit_log.py --before 2026-01-01 --apply    # 归档并删除
"""
import argparse
import asyncio
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import asyncpg  # noqa: E402

from backend.config import settings  # noqa: E402

DEFAULT_OUT = Path("data") / "audit_archive"


def cutoff_from(before: str) -> datetime:
    """`--before` 日期 → 当天 00:00（本地时区，半开窗：只归档 ts < cutoff）。"""
    try:
        d = datetime.strptime(before.strip(), "%Y-%m-%d").date()
    except ValueError:
        raise SystemExit("--before 需为 YYYY-MM-DD（如 2026-01-01）") from None
    return datetime.combine(d, datetime.min.time()).astimezone()


def month_key(ts: datetime) -> str:
    """归档文件名月份键（本地时区）：2026-01。"""
    return ts.astimezone().strftime("%Y-%m")


def group_by_month(rows) -> dict[str, list]:
    """按月份分组（保持时间正序；输入行需含 ts/payload）。"""
    out: dict[str, list] = defaultdict(list)
    for r in rows:
        out[month_key(r["ts"])].append(r)
    return dict(out)


def row_to_json(r) -> str:
    payload = r["payload"]
    if not isinstance(payload, dict):
        try:
            payload = json.loads(payload or "{}")
        except (TypeError, ValueError):
            payload = {"_raw": str(payload)}
    ts = r["ts"]
    return json.dumps({"id": r["id"], "ts": ts.isoformat() if ts else "",
                       "event_type": r["event_type"], "actor": r["actor"],
                       "payload": payload}, ensure_ascii=False)


async def _stats(conn, cutoff: datetime) -> list:
    return await conn.fetch(
        "SELECT ts, count(*) AS n FROM audit_log WHERE ts < $1 GROUP BY 1 ORDER BY 1", cutoff)


def _summarize(stats) -> dict[str, int]:
    out: dict[str, int] = defaultdict(int)
    for r in stats:
        out[month_key(r["ts"])] += int(r["n"])
    return dict(sorted(out.items()))


async def _run(before: str, apply: bool, out_dir: Path, dsn: str) -> int:
    cutoff = cutoff_from(before)
    conn = await asyncpg.connect(dsn)
    try:
        stats = await _stats(conn, cutoff)
        plan = _summarize(stats)
        total = sum(plan.values())
        print(f"截止 {cutoff.date().isoformat()}（不含）：{total} 行 / {len(plan)} 个月")
        for m, n in plan.items():
            print(f"  {m}: {n} 行 → {out_dir / ('audit-' + m + '.jsonl')}")
        if not total:
            print("无需归档")
            return 0
        if not apply:
            print("dry-run：未写入、未删除。加 --apply 执行。")
            return 0

        out_dir.mkdir(parents=True, exist_ok=True)
        async with conn.transaction():
            rows = await conn.fetch(
                "DELETE FROM audit_log WHERE ts < $1 "
                "RETURNING id, ts, event_type, actor, payload", cutoff)
            groups = group_by_month(rows)
            for month, items in groups.items():
                path = out_dir / f"audit-{month}.jsonl"
                with path.open("a", encoding="utf-8", newline="\n") as f:
                    for r in items:
                        f.write(row_to_json(r) + "\n")
                print(f"  已写 {path}（{len(items)} 行）")
        left = await conn.fetchval("SELECT count(*) FROM audit_log WHERE ts < $1", cutoff)
        print(f"已删除 {len(rows)} 行；剩余早于截止点 {left} 行")
        return 0
    finally:
        await conn.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="审计日志归档（默认 dry-run）")
    ap.add_argument("--before", required=True, help="归档该日期之前的行（YYYY-MM-DD）")
    ap.add_argument("--apply", action="store_true", help="真正执行（默认只统计）")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="归档目录（默认 data/audit_archive）")
    ap.add_argument("--dsn", default="", help="覆盖 DSN（默认取 settings）")
    args = ap.parse_args(argv)
    dsn = args.dsn or settings.async_database_url.replace("postgresql+asyncpg://", "postgresql://")
    return asyncio.run(_run(args.before, args.apply, Path(args.out), dsn))


if __name__ == "__main__":
    raise SystemExit(main())
