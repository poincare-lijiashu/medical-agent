"""问题2：遗留 drug 相互作用审核记录清理（两药快查已下线）。

背景：两药快查（drug 视图）已删除，其经 /drug/ask 入队的 review queue 记录对所有人
隐藏（前端所有角色历史均过滤 agent==='drug'）且需数据清理。本脚本：

1. 备份 data/review/queue.json → queue.json.bak-<时间戳>（先备份再动手）；
2. 移除 queue.json 中 agent==='drug' 的全部条目（tmp + os.replace 原子写回）；
3. PG 在线时 best-effort 同步清理 review_queue 表 agent='drug' 行（镜像一致性，
   与 JSON 同纪律；PG 不可用/离线跳过并如实报告，不影响 JSON 清理结果）；
4. 审计 jsonl 一律不动——合规留痕（review_enqueued/resolved 等事件保留，
   这些条目的完整生命周期仍可回溯）。

输出：移除 N 项 / 剩余 M（JSON）+ PG 删除行数（或跳过原因）。幂等：重复运行
第二次移除 0 项。用法：
    python scripts/purge_drug_review_items.py
"""
import asyncio
import json
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core import pg_store  # noqa: E402

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUEUE_FILE = os.path.join(BASE_DIR, "data", "review", "queue.json")


def purge_json_queue(path: str, backup_dir: str | None = None) -> dict:
    """移除 queue.json 中 agent==='drug' 的条目（先备份、原子写回）。

    返回 {removed, remaining, backup}；文件不存在 → {removed:0, remaining:0, backup:''}。
    幂等：无 drug 项时不写盘（保持原文件字节不变），仅返回计数。
    """
    if not os.path.isfile(path):
        return {"removed": 0, "remaining": 0, "backup": ""}
    with open(path, "r", encoding="utf-8") as f:
        items = json.load(f)
    if not isinstance(items, list):  # 损坏/异形文件：不动，交由服务端既有兜底语义处理
        return {"removed": 0, "remaining": 0, "backup": ""}
    keep = [i for i in items if isinstance(i, dict) and i.get("agent") != "drug"]
    removed = len(items) - len(keep)
    backup = ""
    if removed:
        stamp = time.strftime("%Y%m%d-%H%M%S")
        backup = os.path.join(backup_dir or os.path.dirname(path),
                              os.path.basename(path) + ".bak-" + stamp)
        shutil.copyfile(path, backup)  # 先备份再动手
        # 原子写回（tmp + os.replace，与 pg_store._write_json_atomic 同纪律，
        # 防并发读恰逢写入窗口读到截断内容）
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(keep, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    return {"removed": removed, "remaining": len(keep), "backup": backup}


async def purge_pg_drug_rows() -> int | None:
    """PG 镜像一致性清理（best-effort）：删除 review_queue 中 agent='drug' 行。
    返回删除行数；PG 不可用/异常返回 None（如实报告，不影响 JSON 结果）。"""
    if not await pg_store.init():
        return None
    try:
        st = await pg_store.purge_drug_reviews()
        return int(str(st).split()[-1]) if st else 0
    except Exception:  # noqa: BLE001 —— 清理失败不阻断，如实报告
        return None
    finally:
        await pg_store.close()


def main() -> int:
    r = purge_json_queue(QUEUE_FILE)
    print(f"[JSON] {QUEUE_FILE}")
    print(f"[JSON] 移除 {r['removed']} 项 / 剩余 {r['remaining']} 项")
    print(f"[JSON] 备份：{r['backup'] or '（无 drug 项，未写盘未备份）'}")
    pg = asyncio.run(purge_pg_drug_rows())
    if pg is None:
        print("[PG]   跳过（PG 不可用/离线模式；JSON 已清理，服务以 JSON 兜底时同样生效）")
    else:
        print(f"[PG]   review_queue 删除 agent='drug' 行：{pg}")
    print("[审计] 审计 jsonl 未做任何改动（合规留痕：入队/签发/驳回事件全保留）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
