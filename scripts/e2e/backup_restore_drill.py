#!/usr/bin/env python3
"""单院备份恢复演练（第 286 批 / ADR-001）。

验证的不是"备份文件存在"，而是**这份备份真的能恢复出同一份数据**：

  1. 读取源库归档事实（archive_mode / wal_level / pg_stat_archiver），判定 PITR 是否生效；
  2. pg_dump -Fc 全量导出 + pg_restore --list 校验产物可读；
  3. 在目标实例创建一次性数据库并按自定义格式恢复（不触碰源库，不加锁、不改数据）；
  4. 逐表比对关键表行数（账号/租户/角色/审计/住院/床位），不一致即失败；
  5. 清理演练库与临时文件，写出可审计的演练报告。

用法（本地 Docker 形态，默认在 tenant-003 的独立 PG 上演练，不碰 tenant-001）：

  python scripts/e2e/backup_restore_drill.py \
      --source-container medagent_pg_tenant003 --database medagent \
      --target-container medagent_pg_tenant003

生产（pgBackRest/PITR）在 Cell 内执行，参数与校验点一致；本脚本覆盖"逻辑全量恢复"，
WAL 回放到时间点需要 pgBackRest 环境，报告会明确标注 pitrReady 而不假装已演练。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "artifacts" / "backup"

# 关键表：跨域抽样，任何一张行数不一致都说明恢复不完整
KEY_TABLES = (
    "auth_svc.app_user",
    "auth_svc.tenant",
    "auth_svc.user_role",
    "auth_svc.change_log",
    "clinical_svc.admission",
    "clinical_svc.bed",
)


def docker(args: list[str], *, timeout: int = 600, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["docker", *args], capture_output=True, text=True,
                            timeout=timeout, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args[:3])} 失败（{result.returncode}）："
                           f"{(result.stderr or result.stdout).strip()[:300]}")
    return result


def psql(container: str, database: str, user: str, sql: str) -> str:
    return docker(["exec", container, "psql", "-U", user, "-d", database, "-Atc", sql]).stdout.strip()


def scalar(container: str, database: str, user: str, sql: str) -> int:
    raw = psql(container, database, user, sql)
    try:
        return int(raw)
    except ValueError:
        return 0


def archive_facts(container: str, database: str, user: str) -> dict:
    mode = psql(container, database, user,
                "select current_setting('archive_mode') || '|' || current_setting('wal_level')")
    mode_parts = (mode or "|").split("|")
    archiver = psql(container, database, user,
                    "select archived_count || '|' || failed_count || '|' || "
                    "coalesce(last_archived_time::text,'') from pg_stat_archiver")
    parts = (archiver or "0|0|").split("|")
    return {
        "archiveMode": mode_parts[0] if mode_parts else "",
        "walLevel": mode_parts[1] if len(mode_parts) > 1 else "",
        "archivedCount": int(parts[0] or 0),
        "failedCount": int(parts[1] or 0),
        "lastArchivedAt": parts[2] if len(parts) > 2 else "",
        "pitrReady": (mode_parts[0] == "on" and mode_parts[1] in ("replica", "logical")),
    }


def run(args: argparse.Namespace) -> int:
    started = time.time()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    drill_db = f"medagent_drill_{time.strftime('%H%M%S')}"
    dump_name = f"drill-{stamp}.dump"
    source_tmp = f"/tmp/{dump_name}"
    host_tmp = DEFAULT_OUT / dump_name
    report: dict = {"at": time.strftime("%Y-%m-%dT%H:%M:%S"), "source": args.source_container,
                    "target": args.target_container, "database": args.database,
                    "drillDb": drill_db, "steps": [], "checks": {}, "tables": {}}
    DEFAULT_OUT.mkdir(parents=True, exist_ok=True)

    def step(name: str, detail: str = "") -> None:
        report["steps"].append({"name": name, "detail": detail,
                                "elapsedMs": int((time.time() - started) * 1000)})
        print(f"  [OK] {name}" + (f" · {detail}" if detail else ""))

    try:
        # ---- 1. 归档事实：先回答"这套库能不能 PITR" ----
        facts = archive_facts(args.source_container, args.database, args.user)
        report["checks"]["archive"] = facts
        step("读取归档事实",
             f"archive_mode={facts['archiveMode']} wal_level={facts['walLevel']} "
             f"pitrReady={facts['pitrReady']} archived={facts['archivedCount']}")

        # ---- 2. 全量导出并校验产物 ----
        docker(["exec", args.source_container, "pg_dump", "-U", args.user, "-Fc",
                "-d", args.database, "-f", source_tmp])
        docker(["exec", args.source_container, "pg_restore", "--list", source_tmp])
        size_raw = docker(["exec", args.source_container, "stat", "-c", "%s", source_tmp]).stdout.strip()
        size = int(size_raw or 0)
        report["checks"]["dumpSizeBytes"] = size
        step("pg_dump -Fc 并校验", f"{round(size / 1024 / 1024, 2)} MB")

        # ---- 3. 复制到目标实例并建演练库 ----
        docker(["cp", f"{args.source_container}:{source_tmp}", str(host_tmp)])
        target_tmp = source_tmp
        if args.target_container != args.source_container:
            target_tmp = f"/tmp/{dump_name}"
            docker(["cp", str(host_tmp), f"{args.target_container}:{target_tmp}"])
        docker(["exec", args.target_container, "psql", "-U", args.user, "-d", "postgres",
                "-c", f'DROP DATABASE IF EXISTS "{drill_db}"'])
        docker(["exec", args.target_container, "psql", "-U", args.user, "-d", "postgres",
                "-c", f'CREATE DATABASE "{drill_db}"'])
        step("创建演练库", drill_db)

        # ---- 4. 恢复 + 逐表比对 ----
        docker(["exec", args.target_container, "pg_restore", "-U", args.user, "-d", drill_db,
                "--no-owner", "--no-privileges", "--exit-on-error", target_tmp], timeout=1800)
        step("pg_restore 完成")

        mismatches = []
        for table in args.tables:
            source_count = scalar(args.source_container, args.database, args.user,
                                  f"select count(*) from {table}")
            restored_count = scalar(args.target_container, drill_db, args.user,
                                    f"select count(*) from {table}")
            report["tables"][table] = {"source": source_count, "restored": restored_count}
            if source_count != restored_count:
                mismatches.append(f"{table}: {source_count} != {restored_count}")
        if mismatches:
            raise RuntimeError("恢复后行数不一致：" + "；".join(mismatches))
        step("关键表行数一致", f"{len(args.tables)} 张表")

        report["ok"] = True
        report["elapsedSeconds"] = round(time.time() - started, 1)
    except Exception as error:  # noqa: BLE001 - 演练脚本把失败写成证据
        report["ok"] = False
        report["error"] = str(error)
        print(f"  [FAIL] {error}")
    finally:
        # 清理：演练库与容器内临时文件；宿主机 dump 默认保留（--keep-dump 时）
        try:
            docker(["exec", args.target_container, "psql", "-U", args.user, "-d", "postgres",
                    "-c", f'DROP DATABASE IF EXISTS "{drill_db}"'], check=False)
            docker(["exec", args.target_container, "rm", "-f", f"/tmp/{dump_name}"], check=False)
            if args.source_container != args.target_container:
                docker(["exec", args.source_container, "rm", "-f", source_tmp], check=False)
            if not args.keep_dump:
                host_tmp.unlink(missing_ok=True)
        except Exception:  # noqa: BLE001 - 清理失败不影响演练结论
            pass

    out = DEFAULT_OUT / f"restore-drill-{stamp}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("=" * 68)
    if report.get("ok"):
        print(f"恢复演练 PASS · {report['elapsedSeconds']}s · 报告 {out}")
    else:
        print(f"恢复演练 FAIL · {report.get('error')} · 报告 {out}")
    print(f"PITR：{'已就绪' if report['checks'].get('archive', {}).get('pitrReady') else '未就绪（仅全量恢复已演练）'}")
    print("=" * 68)
    return 0 if report.get("ok") else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="单院备份恢复演练")
    parser.add_argument("--source-container", default="medagent_pg_tenant003")
    parser.add_argument("--target-container", default=None)
    parser.add_argument("--database", default="medagent")
    parser.add_argument("--user", default="medagent")
    parser.add_argument("--tables", nargs="*", default=list(KEY_TABLES))
    parser.add_argument("--keep-dump", action="store_true", help="保留宿主机 dump 产物")
    args = parser.parse_args()
    if not args.target_container:
        args.target_container = args.source_container
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
