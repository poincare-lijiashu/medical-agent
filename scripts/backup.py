"""数据备份脚本（轮 A3；G63 收口 §5.2）：pg_dump（容器内执行）+ data/ 目录打包 + 轮转保留。

用法：
    python scripts/backup.py [--keep 7] [--dry-run]

步骤：
1) pg_dump：subprocess 调 `docker exec <容器> pg_dump -U <用户> <库>`。容器名从环境变量
   BACKUP_PG_CONTAINER 读（默认 medagent_pg，与 docker-compose.yml 一致）；用户/库从
   backend config（settings.pg_user / settings.pg_database，可经 .env.local 覆盖）读。

   **G63 起 dump 失败即整体失败（退出码 2）**：不再"仅告警继续打包"。
   理由：旧实现先以 "wb" 截断目标文件，失败后空/半截 dump 仍被打进 tar，随后轮转
   会用这份"没有 PG 数据"的备份挤掉早前完整备份——连续 7 天失败后全部不可恢复，
   而脚本仍返回 0，调度方无从感知。现在的口径：
   - 先写临时文件，校验（非空 + 头尾标记齐全）通过后才原子替换 `data/pg_dump.sql`；
   - 校验失败：不打包、不轮转、退出码 2，上一份完整备份原样保留。
2) tar 打包 data/ → data/backups/backup_YYYYmmdd_HHMMSS.tar.gz，并写同名 `.sha256`
   （恢复演练按此校验产物完整性）。
   排除清单（**可重建缓存不入包**，新增可重建缓存在此登记）：
   - data/backups/                —— 备份目录自身（防递归膨胀）
   - data/kb/pubmed_v2_cache.json —— PubMed 拉取缓存，重跑 seed_kb_pubmed_v2 可重建
3) 轮转：按文件名（backup_YYYYmmdd_HHMMSS 时间戳即字典序）排序，仅保留最近 --keep 份，
   其余删除（连带删除 `.sha256`）。

恢复：解包 tar.gz 覆盖 data/（PG 数据用解包出的 data/pg_dump.sql 导回：
`docker exec -i <容器> psql -U <用户> <库> < data/pg_dump.sql`）。
"""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
import tarfile
from datetime import datetime
from pathlib import Path

# 直跑兜底（与 loadtest.py 同模式）：python scripts/backup.py 直接运行时把项目根
# 加入 sys.path，保证 `from backend.config import settings` 可导入（否则只能 -m 方式跑）。
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
BACKUP_DIR = DATA_DIR / "backups"
DUMP_FILE = DATA_DIR / "pg_dump.sql"

# 可重建缓存不入包（注释即文档：新增可重建缓存在此登记）
EXCLUDE_REL = ("kb/pubmed_v2_cache.json",)

# plain 格式 dump 的最小可信体积与首尾标记（pg_dump 15 会写这两行）
MIN_DUMP_BYTES = 1024
DUMP_HEAD_MARK = b"PostgreSQL database dump"
DUMP_TAIL_MARK = b"PostgreSQL database dump complete"


class BackupError(RuntimeError):
    """备份过程失败（dump 不可用 / 产物校验不通过）。"""


def pg_dump_cmd(container: str, user: str, db: str, schema: str = "public") -> list[str]:
    """容器内 pg_dump 命令行（单测断言用）。

    评测 A5：**必须限定 schema**。共享 PG 实例里本系统只占 public（21 张表），
    其余 9 个 schema 的 211 张表属于院内其他系统；全库 dump 会把它们的 PHI 打进
    备份产物，restore 的 DROP 前奏还会连带回滚别人的生产数据。
    """
    return ["docker", "exec", container, "pg_dump", "-U", user, "--schema", schema, db]


def _looks_complete(path: Path) -> bool:
    """plain dump 完整性粗校验：体积达标 + 头尾标记齐全（挡住空文件/半截输出）。"""
    size = path.stat().st_size
    if size < MIN_DUMP_BYTES:
        return False
    with open(path, "rb") as f:
        head = f.read(4096)
        f.seek(max(0, size - 4096))
        tail = f.read()
    return DUMP_HEAD_MARK in head and DUMP_TAIL_MARK in tail


def run_pg_dump(container: str, user: str, db: str) -> None:
    """执行 pg_dump 并校验；失败抛 BackupError（调用方据此中止整次备份）。

    先写 `.tmp` 再原子替换：任何失败都不会破坏上一次的 `data/pg_dump.sql`。
    """
    cmd = pg_dump_cmd(container, user, db)
    print("[pg_dump] " + " ".join(cmd))
    tmp = DUMP_FILE.with_name(DUMP_FILE.name + ".tmp")
    tmp.unlink(missing_ok=True)
    try:
        with open(tmp, "wb") as f:
            r = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=600)
        if r.returncode != 0:
            raise BackupError(
                f"pg_dump exit={r.returncode}：{r.stderr.decode(errors='replace')[:200]}")
        if not _looks_complete(tmp):
            raise BackupError(
                f"dump 产物校验失败（{tmp.stat().st_size} 字节，缺少 pg_dump 头尾标记）")
        tmp.replace(DUMP_FILE)
        print(f"[pg_dump] 完成：{DUMP_FILE}（{DUMP_FILE.stat().st_size} 字节）")
    except BackupError:
        tmp.unlink(missing_ok=True)
        raise
    except Exception as exc:  # noqa: BLE001 —— docker 未装/容器未启动等，统一转成备份失败
        tmp.unlink(missing_ok=True)
        raise BackupError(f"pg_dump 执行失败：{exc}") from exc


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


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_checksum(archive: Path) -> Path:
    """写 `<archive>.sha256`（`<hex>  <文件名>`，与 sha256sum -c 兼容）。"""
    out = archive.with_name(archive.name + ".sha256")
    out.write_text(f"{sha256_of(archive)}  {archive.name}\n", encoding="utf-8")
    return out


def rotate_backups(keep: int) -> list[str]:
    """轮转：按文件名倒序保留最近 keep 份 backup_*.tar.gz，删除其余（连带校验文件）。"""
    if not BACKUP_DIR.is_dir():
        return []
    backups = sorted(BACKUP_DIR.glob("backup_*.tar.gz"), reverse=True)  # 时间戳文件名=字典序
    removed: list[str] = []
    for old in backups[max(0, keep):]:
        old.unlink()
        old.with_name(old.name + ".sha256").unlink(missing_ok=True)
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

    try:
        run_pg_dump(container, user, db)
    except BackupError as exc:
        print(f"[backup] 失败：{exc}")
        print("[backup] 未生成新备份、未轮转；上一份完整备份保持不动。修复后请重跑。")
        return 2

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    included = create_archive(out_path)
    checksum = write_checksum(out_path)
    print(f"[backup] 已打包 {len(included)} 个文件 → {out_path}")
    print(f"[backup] 校验文件 → {checksum.name}")
    DUMP_FILE.unlink(missing_ok=True)  # dump 已随包归档，落盘副本清掉（下次重新生成）
    removed = rotate_backups(args.keep)
    if removed:
        print("[backup] 轮转删除：" + ", ".join(removed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
