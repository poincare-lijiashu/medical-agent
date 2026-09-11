"""数据备份脚本（轮 A3）：pg_dump（容器内执行）+ data/ 目录打包 + 轮转保留。

用法：
    python scripts/backup.py [--keep 7] [--dry-run]

步骤：
1) pg_dump：subprocess 调 `docker exec <容器> pg_dump -U <用户> <库>`。容器名从环境变量
   BACKUP_PG_CONTAINER 读（默认 medagent_pg，与 docker-compose.yml 一致）；用户/库从
   backend config（settings.pg_user / settings.pg_database，可经 .env.local 覆盖）读。
   dump 落 data/pg_dump.sql 随包归档（打包后删除落盘副本）。**失败不中断**：仅告警，
   继续打包 JSON 部分（可用性优先——部分备份好过没有备份）。
2) tar 打包 data/ → data/backups/backup_YYYYmmdd_HHMMSS.tar.gz。
   排除清单（**可重建缓存不入包**，新增可重建缓存在此登记）：
   - data/backups/                —— 备份目录自身（防递归膨胀）
   - data/kb/pubmed_v2_cache.json —— PubMed 拉取缓存，重跑 seed_kb_pubmed_v2 可重建
3) 轮转：按文件名（backup_YYYYmmdd_HHMMSS 时间戳即字典序）排序，仅保留最近 --keep 份，
   其余删除。

恢复：解包 tar.gz 覆盖 data/（PG 数据用解包出的 data/pg_dump.sql 导回：
`docker exec -i <容器> psql -U <用户> <库> < data/pg_dump.sql`）。
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tarfile
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
BACKUP_DIR = DATA_DIR / "backups"
DUMP_FILE = DATA_DIR / "pg_dump.sql"

# 可重建缓存不入包（注释即文档：新增可重建缓存在此登记）
EXCLUDE_REL = ("kb/pubmed_v2_cache.json",)


def pg_dump_cmd(container: str, user: str, db: str) -> list[str]:
    """容器内 pg_dump 命令行（单测断言用）。"""
    return ["docker", "exec", container, "pg_dump", "-U", user, db]


def run_pg_dump(container: str, user: str, db: str) -> bool:
    """执行 pg_dump 落 data/pg_dump.sql；失败仅告警返回 False（不中断备份流程）。"""
    cmd = pg_dump_cmd(container, user, db)
    print("[pg_dump] " + " ".join(cmd))
    try:
        with open(DUMP_FILE, "wb") as f:
            r = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=600)
        if r.returncode != 0:
            print(f"[pg_dump] 失败（exit={r.returncode}）："
                  f"{r.stderr.decode(errors='replace')[:200]}")
            return False
        print(f"[pg_dump] 完成：{DUMP_FILE}（{DUMP_FILE.stat().st_size} 字节）")
        return True
    except Exception as exc:  # noqa: BLE001 —— docker 未装/容器未启动等，绝不让备份中断
        print(f"[pg_dump] 失败：{exc}")
        return False


def create_archive(out_path: Path) -> list[str]:
    """tar data/ → out_path（w:gz）；排除 data/backups 自身与可重建缓存。返回打包相对路径。"""
    included: list[str] = []
    with tarfile.open(out_path, "w:gz") as tar:
        for root, dirs, files in os.walk(DATA_DIR):
            rel_root = os.path.relpath(root, DATA_DIR).replace("\\", "/")
            if rel_root == "backups":  # 排除备份目录自身（防递归）
                dirs[:] = []
                continue
            for fn in files:
                rel = os.path.normpath(os.path.join(rel_root, fn)).replace("\\", "/")
                if rel in EXCLUDE_REL:
                    continue
                tar.add(os.path.join(root, fn), arcname="data/" + rel)
                included.append(rel)
    return included


def rotate_backups(keep: int) -> list[str]:
    """轮转：按文件名倒序保留最近 keep 份 backup_*.tar.gz，删除其余；返回被删文件名清单。"""
    if not BACKUP_DIR.is_dir():
        return []
    backups = sorted(BACKUP_DIR.glob("backup_*.tar.gz"), reverse=True)  # 时间戳文件名=字典序
    removed: list[str] = []
    for old in backups[max(0, keep):]:
        old.unlink()
        removed.append(old.name)
    return removed


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="MedAssist 数据备份（pg_dump + data 目录打包 + 轮转）")
    ap.add_argument("--keep", type=int, default=7, help="保留最近 N 份备份（默认 7）")
    ap.add_argument("--dry-run", action="store_true", help="只打印将执行的步骤，不实际执行")
    args = ap.parse_args(argv)

    container = (os.environ.get("BACKUP_PG_CONTAINER") or "medagent_pg").strip()
    from backend.config import settings  # 用户/库从 config（.env.local 可覆盖）读
    user, db = settings.pg_user, settings.pg_database

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = BACKUP_DIR / f"backup_{stamp}.tar.gz"
    print(f"[backup] 目标：{out_path}（保留最近 {args.keep} 份）")
    if args.dry_run:
        print("[dry-run] 1) " + " ".join(pg_dump_cmd(container, user, db))
              + " → data/pg_dump.sql")
        print("[dry-run] 2) tar data/ → " + out_path.name
              + f"（排除 backups/ 自身与可重建缓存：{', '.join(EXCLUDE_REL)}）")
        print(f"[dry-run] 3) 轮转保留最近 {args.keep} 份")
        return 0

    if not run_pg_dump(container, user, db):
        print("[backup] pg_dump 失败：继续打包 JSON 部分（部分备份好过没有备份）")
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    included = create_archive(out_path)
    print(f"[backup] 已打包 {len(included)} 个文件 → {out_path}")
    DUMP_FILE.unlink(missing_ok=True)  # dump 已随包归档，落盘副本清掉（下次重新生成）
    removed = rotate_backups(args.keep)
    if removed:
        print("[backup] 轮转删除：" + ", ".join(removed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
