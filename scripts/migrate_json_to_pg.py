"""B1 手动迁移：JSON 存量（users/departments/review_queue/llm_providers）→ PG 幂等导入。

lifespan 启动时已自动触发（仅 PG 表空且 JSON 有数据时导入，ON CONFLICT DO NOTHING）；
本脚本用于手动补跑/验证迁移结果（并在迁移后按 PG 真源重建临床域 JSON 镜像）：
    python scripts/migrate_json_to_pg.py
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core import pg_store  # noqa: E402


async def main() -> int:
    ok = await pg_store.init()
    if not ok:
        print("PG 不可用：未执行迁移（服务将以 JSON 真源模式运行，数据仍写入 JSON 兜底）")
        return 1
    try:
        migrated = await pg_store.migrate_json_to_pg()
        # 评测 P0-4：迁移把 JSON 独有行补进 PG 后，再按 PG 重建镜像，双向消除历史分叉
        resynced = await pg_store.resync_clinical_mirrors()
        counts = await pg_store.counts()
        print("迁移结果（各表导入条数，{} 表示表非空跳过/无数据）:".format("空"), migrated)
        print("镜像对账（PG 行数 / JSON 修正前条数 / 增删改）:", resynced or "无（PG 空表或非主模式）")
        print("PG 当前行数:", counts)
        return 0
    finally:
        await pg_store.close()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
