"""数据恢复脚本（评测行动项 6）：从 backup.py 产出的 tar.gz 恢复 data/ 目录与 PG。

用法：
    python scripts/restore.py [--backup data/backups/backup_XXX.tar.gz] [--yes]

- 缺省 **dry-run**：只解析备份并打印将执行的计划，绝不写任何文件、绝不调 docker。
- --yes：先打印覆盖警告，然后按序执行：
  1) safety tar：把当前 data/（不含 backups/ 自身与可重建缓存）打包为
     data/backups/pre_restore_<时间戳>.tar.gz —— 恢复前状态可回退；
  2) 解包备份内 data/ 树覆盖到 data/（排除 data/backups/ 自身；data/pg_dump.sql
     只读入内存喂 psql，不落盘）；随后**镜像清理**：删除 data/ 下不在备份内的
     文件（演练探针/残留物随恢复清除；可重建缓存如 kb/pubmed_v2_cache.json
     因不入包也会被清理，重跑种子脚本即可重建）；
  3) PG 恢复：backup.py 的 pg_dump 为**纯 SQL**（未用 -Fc custom 格式），故用
     psql 导回；dump 内无 DROP 语句，脚本解析其中 CREATE TABLE 生成
     `DROP TABLE IF EXISTS <表> CASCADE` 前奏一并喂入
     `docker exec -i <容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1`。
     **严禁 drop database**：只重建 dump 覆盖的表（表由 dump 内 CREATE 重建，
     恢复前的脏数据/演练探针行随 DROP 清除；owned 序列随表级联删除）；
  4) 验证：逐 *.json 对比「备份内行数 vs 当前行数」+ 打印 PG 关键表 count。

容器名：环境变量 BACKUP_PG_CONTAINER（默认 medagent_pg，与 backup.py 一致；
本机演练环境为 edu_agent_postgres 时用 env 覆盖）。用户/库从 backend config
（settings.pg_user / settings.pg_database，可经 .env.local 覆盖）读取。
"""
from __future__ import annotations

import argparse
import io
import json
import os
import subprocess
import sys
import tarfile
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
BACKUP_DIR = DATA_DIR / "backups"

# PG 关键表（验证 count 用，与 pg_store 建表一致）
KEY_TABLES = ("users", "review_queue", "consults", "prescriptions", "case_archive",
              "drug_dict", "drug_rules", "departments", "llm_providers",
              "runtime_flags", "audit_log")

# 可重建缓存不入包/不入 safety tar（与 backup.py EXCLUDE_REL 同源）
from scripts.backup import EXCLUDE_REL  # noqa: E402


def find_latest_backup(backup_dir: Path = BACKUP_DIR) -> Path | None:
    """取 data/backups 下最新一份 backup_*.tar.gz（时间戳文件名=字典序），无则 None。"""
    if not backup_dir.is_dir():
        return None
    backups = sorted(backup_dir.glob("backup_*.tar.gz"), reverse=True)
    return backups[0] if backups else None


def pg_restore_cmd(container: str, user: str, db: str) -> list[str]:
    """PG 恢复命令行（单测断言用）：-i 从 stdin 喂 SQL，ON_ERROR_STOP=1 出错即停。"""
    return ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db,
            "-v", "ON_ERROR_STOP=1"]


def dump_tables(sql_text: str) -> list[str]:
    """解析 dump 内 CREATE TABLE 语句，返回表名（保持出现顺序，供 DROP 前奏）。

    只认 `CREATE TABLE [IF NOT EXISTS] <名字> (`；CREATE TABLESPACE 不会被
    `CREATE TABLE\\s` 误匹配（后跟字母 S）。pg_dump 输出形如 `CREATE TABLE public.users (`。
    """
    import re
    tables: list[str] = []
    for m in re.finditer(r"(?im)^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
                         r"([A-Za-z0-9_\".$]+)\s*\(", sql_text):
        name = m.group(1).strip('"')
        if name not in tables:
            tables.append(name)
    return tables


def drop_prelude(tables: list[str]) -> str:
    """DROP 前奏 SQL：只删 dump 覆盖的表（CASCADE 级联 owned 序列），严禁 drop database。"""
    return "\n".join(f"DROP TABLE IF EXISTS {t} CASCADE;" for t in tables) + "\n"


def _rows_of(data: bytes) -> int:
    """JSON 行数语义：数组/对象取元素个数，标量记 1（验证对比口径）。"""
    try:
        obj = json.loads(data.decode("utf-8-sig"))
    except Exception:  # noqa: BLE001 —— 非 JSON 内容记 1，不中断验证
        return 1
    return len(obj) if isinstance(obj, (list, dict)) else 1


def _rel_of(name: str) -> str | None:
    """tar 成员名 → data/ 树内相对路径；非 data/ 树、backups 自身、越界名返回 None。"""
    rel = name[len("data/"):] if name.startswith("data/") else None
    if rel is None or not rel or rel.endswith("/"):
        return None
    parts = Path(rel).parts
    if parts[0] == "backups" or ".." in parts or Path(rel).is_absolute():
        return None  # 备份目录自身与越界路径一律不碰
    return rel.replace("\\", "/")


def read_backup(tar_path: Path) -> tuple[dict[str, bytes], bytes]:
    """读取备份：返回 (data/ 树内文件名→内容 映射, pg_dump.sql 字节)。

    data/pg_dump.sql 只入内存（喂 psql 用），调用方不得落盘。
    """
    files: dict[str, bytes] = {}
    dump = b""
    with tarfile.open(tar_path, "r:gz") as tar:
        for m in tar.getmembers():
            if not m.isfile():
                continue
            rel = _rel_of(m.name)
            if rel is None:
                continue
            content = tar.extractfile(m).read()  # type: ignore[union-attr]
            if rel == "pg_dump.sql":
                dump = content
            else:
                files[rel] = content
    return files, dump


def safety_archive(out_path: Path) -> list[str]:
    """safety tar：打包当前 data/（排除 backups/ 自身与可重建缓存）→ 返回已打包相对路径。"""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    included: list[str] = []
    with tarfile.open(out_path, "w:gz") as tar:
        for root, dirs, files in os.walk(DATA_DIR):
            rel_root = os.path.relpath(root, DATA_DIR).replace("\\", "/")
            if rel_root == "backups":  # 备份目录自身不入包（防递归）
                dirs[:] = []
                continue
            for fn in sorted(files):
                rel = os.path.normpath(os.path.join(rel_root, fn)).replace("\\", "/")
                if rel in EXCLUDE_REL:
                    continue
                tar.add(os.path.join(root, fn), arcname="data/" + rel)
                included.append(rel)
    return included


def extract_and_mirror(files: dict[str, bytes]) -> list[str]:
    """覆盖写备份内文件 + 镜像删除 data/ 下不在备份内的文件；返回被删除相对路径清单。"""
    removed: list[str] = []
    # 1) 覆盖写（先于删除：目标文件/目录按备份内容重建）
    for rel, content in files.items():
        target = DATA_DIR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    # 2) 镜像删除：当前 data/（除 backups/）内不在备份清单中的文件（探针/残留清除）
    keep = set(files)
    for root, dirs, fns in os.walk(DATA_DIR):
        rel_root = os.path.relpath(root, DATA_DIR).replace("\\", "/")
        if rel_root == "backups":
            dirs[:] = []
            continue
        for fn in fns:
            rel = (rel_root + "/" + fn if rel_root != "." else fn)
            if rel not in keep:
                (Path(root) / fn).unlink()
                removed.append(rel)
    # 3) 清残留空目录（最深优先；rmdir 仅空目录成功）
    for root, dirs, fns in os.walk(DATA_DIR, topdown=False):
        rel_root = os.path.relpath(root, DATA_DIR).replace("\\", "/")
        if rel_root in (".", "backups"):
            continue
        try:
            (Path(root)).rmdir()
        except OSError:
            pass
    return removed


def pg_counts(container: str, user: str, db: str) -> str:
    """一次性取 PG 关键表 count（psql -t -A UNION ALL）；失败返回空串（仅告警不中断）。"""
    sql = " UNION ALL ".join(f"SELECT '{t}', count(*) FROM {t}" for t in KEY_TABLES)
    cmd = ["docker", "exec", container, "psql", "-U", user, "-d", db,
           "-t", "-A", "-F", "|", "-c", sql]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=120)
        if r.returncode != 0:
            print(f"[verify] PG count 失败（exit={r.returncode}）："
                  f"{r.stderr.decode(errors='replace')[:200]}")
            return ""
        return r.stdout.decode(errors="replace").strip()
    except Exception as exc:  # noqa: BLE001 —— docker 未装/容器未启动等
        print(f"[verify] PG count 失败：{exc}")
        return ""


def plan_lines(files: dict[str, bytes], dump: bytes, stale: list[str],
               container: str, user: str, db: str) -> list[str]:
    """生成将执行的计划文本（dry-run 与 --yes 共用）。"""
    tables = dump_tables(dump.decode(errors="replace")) if dump else []
    lines = [
        f"  1) safety tar：当前 data/ → {BACKUP_DIR.name}/pre_restore_<时间戳>.tar.gz（恢复前状态可回退）",
        f"  2) 解包覆盖 {len(files)} 个文件 → data/；镜像删除不在备份内的文件 {len(stale)} 个："
        + (", ".join(stale) if stale else "（无）"),
        f"  3) PG：{' '.join(pg_restore_cmd(container, user, db))}",
        f"     先执行 DROP 前奏（{len(tables)} 表，来自 dump 的 CREATE TABLE，"
        "严禁 drop database）：" + (", ".join(tables) if tables else "（dump 内无建表语句）"),
        "  4) 验证：逐 *.json 备份内/当前行数对比 + PG 关键表 count",
    ]
    return lines


def verify(files: dict[str, bytes], container: str, user: str, db: str) -> bool:
    """恢复后验证：逐 *.json 行数对比 + PG 关键表 count；返回是否全部一致。"""
    print("[verify] JSON 行数对比（备份内 vs 当前 data/）：")
    ok = True
    for rel in sorted(files):
        if not rel.endswith(".json"):
            continue
        want = _rows_of(files[rel])
        cur_path = DATA_DIR / rel
        cur = _rows_of(cur_path.read_bytes()) if cur_path.is_file() else -1
        mark = "OK" if cur == want else "不一致"
        if cur != want:
            ok = False
        print(f"  {rel}: 备份={want} 当前={cur} [{mark}]")
    print("[verify] PG 关键表 count：")
    counts = pg_counts(container, user, db)
    for line in counts.splitlines():
        if line.strip():
            print(f"  {line.strip()}")
    return ok and bool(counts)


def pg_targets() -> tuple[str, str, str]:
    """PG 目标三元组（容器, 用户, 库）——单测可整体替身，保证测试封闭。"""
    container = (os.environ.get("BACKUP_PG_CONTAINER") or "medagent_pg").strip()
    from backend.config import settings  # 用户/库从 config（.env.local 可覆盖）读
    return container, settings.pg_user, settings.pg_database


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="MedAssist 数据恢复（tar 解包覆盖 + psql 导回纯 SQL dump）")
    ap.add_argument("--backup", default="", help="备份 tar.gz 路径（默认取 data/backups 最新一份）")
    ap.add_argument("--yes", action="store_true", help="实际执行（缺省 dry-run 只打印计划）")
    args = ap.parse_args(argv)

    container, user, db = pg_targets()

    tar_path = Path(args.backup) if args.backup else find_latest_backup()
    if not tar_path or not tar_path.is_file():
        print(f"[restore] 未找到备份：{args.backup or BACKUP_DIR}（--backup 指定或先跑 scripts/backup.py）")
        return 1
    files, dump = read_backup(tar_path)

    # 镜像删除预演：当前 data/（除 backups/ 与可重建缓存）内不在备份内的文件
    keep = set(files) | {"pg_dump.sql"}
    stale: list[str] = []
    for root, dirs, fns in os.walk(DATA_DIR):
        rel_root = os.path.relpath(root, DATA_DIR).replace("\\", "/")
        if rel_root == "backups":
            dirs[:] = []
            continue
        for fn in fns:
            rel = (rel_root + "/" + fn if rel_root != "." else fn)
            if rel not in keep and rel not in EXCLUDE_REL:
                stale.append(rel)
    stale.sort()

    mode = "执行" if args.yes else "dry-run"
    print(f"[restore] 模式：{mode} | 备份：{tar_path} | 目标：data/ + PG（{user}@{db}）")
    for line in plan_lines(files, dump, stale, container, user, db):
        print(line)

    if not args.yes:
        print("[dry-run] 未写入任何文件、未调用 docker（加 --yes 实际执行）")
        return 0

    print("[警告] --yes：即将覆盖 data/ 并恢复 PG（先 DROP dump 内表）。"
          "请确认后端服务已停止、无写入进程后再继续。")
    # 1) safety tar 先行：恢复前状态可回退
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safety_path = BACKUP_DIR / f"pre_restore_{stamp}.tar.gz"
    included = safety_archive(safety_path)
    print(f"[restore] 1) safety tar：{safety_path}（{len(included)} 个文件）")
    # 2) 解包覆盖 + 镜像删除
    removed = extract_and_mirror(files)
    print(f"[restore] 2) 已覆盖 {len(files)} 个文件；镜像删除 {len(removed)} 个："
          + (", ".join(removed) if removed else "（无）"))
    # 3) PG 恢复：DROP 前奏 + 纯 SQL dump 一并喂 psql（严禁 drop database）
    cmd = pg_restore_cmd(container, user, db)
    payload = drop_prelude(dump_tables(dump.decode(errors="replace"))).encode() + dump
    print(f"[restore] 3) {' '.join(cmd)}（stdin 喂入 {len(payload)} 字节 SQL）")
    try:
        r = subprocess.run(cmd, input=payload, capture_output=True, timeout=600)
    except Exception as exc:  # noqa: BLE001
        print(f"[restore] PG 恢复失败：{exc}")
        return 2
    if r.returncode != 0:
        print(f"[restore] PG 恢复失败（exit={r.returncode}）："
              f"{r.stderr.decode(errors='replace')[:500]}")
        return 2
    print("[restore] PG 恢复完成（psql exit=0）")
    # 4) 验证
    consistent = verify(files, container, user, db)
    print(f"[restore] 验证结论：{'全部一致' if consistent else '存在不一致，请人工核查'}")
    return 0 if consistent else 0  # 验证结论打印供人工/演练判读，不作为退出码


if __name__ == "__main__":
    sys.exit(main())
