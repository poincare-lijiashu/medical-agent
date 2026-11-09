"""会诊意见子表回填（第 124 批）：从 `consults.data->'opinions'` 重建 `consult_opinions`。

为什么需要：子表是"待本科室填意见"等**取反**查询的索引载体（§127 实测 jsonb 取反无法走
GIN）。双写自第 124 批起生效，**历史会诊**需要本脚本回填一次；脚本幂等，可重复执行。

安全设计：
- **默认 dry-run**：只打印"将写入多少单/多少条意见、当前子表多少行、差异单号"，不写；
- `--apply`：单事务内 `DELETE` + 分批 `executemany`，异常自动回滚（不会半新半旧）；
- 映射口径复用 `pg_store.consult_opinion_rows`（与双写同一份代码，绝不出现两套规则）；
- 回填后再做一次对账：逐单比较 jsonb 意见数与子表行数，打印不一致单号（正常应为 0）。

用法：
    python scripts/backfill_consult_opinions.py            # dry-run
    python scripts/backfill_consult_opinions.py --check    # 只对账（不一致退出码 1）
    python scripts/backfill_consult_opinions.py --apply    # 重建子表
"""
import argparse
import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import asyncpg  # noqa: E402

from backend.config import settings  # noqa: E402
from backend.core import pg_store  # noqa: E402

BATCH = 1000


def _data_of(row) -> dict | None:
    d = row["data"]
    if isinstance(d, dict):
        return d
    try:
        d = json.loads(d or "{}")
    except (TypeError, ValueError):
        return None
    return d if isinstance(d, dict) else None


def plan(rows) -> dict:
    """统计"将从 jsonb 生成多少子表行"。

    返回 {consults, opinions, skipped, perConsult}；skipped = 无 doctor 的意见条数
    （子表主键 (consult_id, doctor) 无法容纳，与双写口径一致地跳过）。
    """
    stats = {"consults": 0, "opinions": 0, "skipped": 0, "perConsult": {}}
    for r in rows:
        data = _data_of(r)
        if data is None:
            continue
        stats["consults"] += 1
        ops = data.get("opinions") or []
        if not isinstance(ops, list):
            continue
        rows_out = pg_store.consult_opinion_rows(data)
        stats["perConsult"][str(data.get("id") or r["id"])] = len(rows_out)
        stats["opinions"] += len(rows_out)
        stats["skipped"] += max(0, len(ops) - len(rows_out))
    return stats


def diff_by_consult(expected: dict[str, int], actual: dict[str, int]) -> list[str]:
    """逐单对账：返回"期望行数 != 实际行数"的单号（含只在一边出现的单）。"""
    bad = []
    for cid in sorted(set(expected) | set(actual)):
        if int(expected.get(cid, 0)) != int(actual.get(cid, 0)):
            bad.append(cid)
    return bad


def classify(expected: dict[str, int], actual: dict[str, int]) -> dict:
    """把差异分成两类（评测 A4），避免运维误判：

    - ``mismatch``：主单存在、但子表行数与 jsonb 不符（双写漏写/多写），可 --apply 修复；
    - ``orphans``：子表有记录、consults 主单已不存在（删除路径未级联），回填修不了——
      初始化时 `_CONSULT_OPINIONS_FK_DDL` 会先清孤儿再加外键（ON DELETE CASCADE）。
    原实现用 diff_by_consult 把两者混成"不一致"，孤儿行被误报为双写漏写。"""
    mismatch = [cid for cid in sorted(expected)
                if int(expected.get(cid, 0)) != int(actual.get(cid, 0))]
    orphans = sorted(set(actual) - set(expected))
    return {"mismatch": mismatch, "orphans": orphans}


def exit_code(bad: list[str], *, apply: bool, check: bool) -> int:
    """退出码：`--check` 不允许忽略差异；`--apply` 之后仍有差异也算失败（供 CronJob 告警）。"""
    if apply or check:
        return 1 if bad else 0
    return 0


async def _table_counts(conn) -> dict[str, int]:
    rows = await conn.fetch(
        "SELECT consult_id, count(*) AS n FROM consult_opinions GROUP BY 1")
    return {str(r["consult_id"]): int(r["n"]) for r in rows}


async def _run(apply: bool, check: bool, dsn: str) -> int:
    conn = await asyncpg.connect(dsn)
    try:
        # 幂等建表：本脚本可能先于应用启动执行（此时 init() 还没跑过）
        await conn.execute(pg_store._CONSULT_OPINIONS_DDL)
        rows = await conn.fetch("SELECT id, data FROM consults")
        stats = plan(rows)
        actual = await _table_counts(conn)
        cls = classify(stats["perConsult"], actual)
        bad = cls["mismatch"] + cls["orphans"]
        print(f"会诊单 {stats['consults']} 单；jsonb 意见 {stats['opinions']} 条"
              f"（跳过无 doctor {stats['skipped']} 条）")
        print(f"子表现状 {sum(actual.values())} 行 / {len(actual)} 单；"
              f"数量不符单数 {len(cls['mismatch'])}，孤儿行单数 {len(cls['orphans'])}")
        if cls["mismatch"]:
            print("  数量不符单号（前 10）：", ", ".join(cls["mismatch"][:10]))
        if cls["orphans"]:
            print("  孤儿行单号（前 10，主单已不存在）：", ", ".join(cls["orphans"][:10]))
        if not apply:
            if check:
                print("对账结果：" + ("不一致（退出码 1）" if bad else "一致"))
                return exit_code(bad, apply=False, check=True)
            print("dry-run：未写入。加 --apply 重建子表。")
            return 0

        all_rows = []
        for r in rows:
            data = _data_of(r)
            if data is not None:
                all_rows.extend(pg_store.consult_opinion_rows(data))
        async with conn.transaction():
            await conn.execute("DELETE FROM consult_opinions")
            for i in range(0, len(all_rows), BATCH):
                await conn.executemany(pg_store._CONSULT_OPINIONS_UPSERT,
                                       all_rows[i:i + BATCH])
        # 评测 A4：重建后补外键（先清孤儿），从根上防止"删主单留孤儿"再次发生
        try:
            await conn.execute(pg_store._CONSULT_OPINIONS_FK_DDL)
        except Exception as e:  # noqa: BLE001 —— 权限不足等只提示，不影响已完成的回填
            print(f"（外键创建跳过：{type(e).__name__}: {str(e)[:80]}）")
        after = await _table_counts(conn)
        bad2 = diff_by_consult(stats["perConsult"], after)
        print(f"已重建：{sum(after.values())} 行 / {len(after)} 单；残留不一致 {len(bad2)}")
        return exit_code(bad2, apply=True, check=False)
    finally:
        await conn.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="会诊意见子表回填（默认 dry-run）")
    ap.add_argument("--apply", action="store_true", help="真正执行重建（默认只统计）")
    ap.add_argument("--check", action="store_true",
                    help="只对账：不一致退出码 1（供 CronJob/CI 告警），不写入")
    ap.add_argument("--dsn", default="", help="覆盖 DSN（默认取 settings）")
    args = ap.parse_args(argv)
    dsn = args.dsn or settings.async_database_url.replace("postgresql+asyncpg://",
                                                          "postgresql://")
    return asyncio.run(_run(args.apply, args.check, dsn))


if __name__ == "__main__":
    raise SystemExit(main())
