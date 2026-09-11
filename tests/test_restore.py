"""评测行动项 6：恢复脚本轻测（scripts/restore.py）——全程 monkeypatch subprocess/docker，
绝不触碰真实 data/ 与真实 PG。

- 命令序列断言：docker exec -i <容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1；
- dump 表解析：只认 CREATE TABLE（CREATE TABLESPACE/INSERT 不误匹配）；
- dry-run：只打印计划——零 subprocess 调用、零写文件（内容/探针文件原样、无 safety tar）；
- --yes：命令序列正确——safety tar 先行（psql 调用时 safety tar 已存在且含恢复前状态）、
  解包覆盖、镜像删除探针文件、psql stdin 为 DROP 前奏+dump 全文、dump 不落盘；
- 无备份/路径非法：返回 1 且不做任何事。
"""
import io
import json
import tarfile
from pathlib import Path

import pytest

from scripts import restore


DUMP_SQL = """\
-- pg_dump 纯 SQL 样例
CREATE TABLE public.users (username text primary key);
CREATE TABLE public.runtime_flags (key text primary key);
CREATE TABLESPACE foo OWNER TO bar;
INSERT INTO pg_catalog.pg_proc VALUES ('not a table');
CREATE SEQUENCE public.drug_dict_id_seq;
ALTER SEQUENCE public.drug_dict_id_seq OWNED BY public.drug_dict.id;
CREATE TABLE public.drug_dict (id serial primary key, name text);
""".encode()


def _make_backup(tmp_path: Path, files: dict[str, bytes], dump: bytes = DUMP_SQL) -> Path:
    """造一份 backup.py 结构的假备份（data/ 树 + data/pg_dump.sql）。"""
    tar_path = tmp_path / "backup_20260911_000000.tar.gz"
    with tarfile.open(tar_path, "w:gz") as tar:
        for rel, data in files.items():
            info = tarfile.TarInfo("data/" + rel)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        info = tarfile.TarInfo("data/pg_dump.sql")
        info.size = len(dump)
        tar.addfile(info, io.BytesIO(dump))
    return tar_path


class _FakeRun:
    """subprocess.run 替身：记录 (命令, stdin)；并记录调用时刻已存在的 safety tar 清单。"""

    def __init__(self):
        self.calls: list[tuple[list[str], bytes]] = []
        self.safety_at_call: list[list[str]] = []

    def __call__(self, cmd, input=None, **kwargs):  # noqa: A002 —— 对齐 subprocess 签名
        self.calls.append((list(cmd), bytes(input or b"")))
        self.safety_at_call.append(
            sorted(p.name for p in (restore.BACKUP_DIR).glob("pre_restore_*.tar.gz")))
        import types
        return types.SimpleNamespace(returncode=0, stdout=b"users|1\nruntime_flags|0\n",
                                     stderr=b"")


@pytest.fixture()
def iso(tmp_path, monkeypatch):
    """隔离 data 目录（绝不触碰真实 data/）+ PG 目标替身 + subprocess 替身。"""
    data = tmp_path / "data"
    (data / "kb").mkdir(parents=True)
    backup_dir = data / "backups"
    backup_dir.mkdir()
    (data / "drug_dict.json").write_bytes(b'[{"name":"A"}]')
    monkeypatch.setattr(restore, "DATA_DIR", data)
    monkeypatch.setattr(restore, "BACKUP_DIR", backup_dir)
    monkeypatch.setattr(restore, "pg_targets",
                        lambda: ("medagent_pg", "medagent", "medagent"))
    fake = _FakeRun()
    monkeypatch.setattr(restore.subprocess, "run", fake)
    return data, fake


def test_pg_restore_cmd_sequence():
    """命令序列：docker exec -i <容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1。"""
    assert restore.pg_restore_cmd("medagent_pg", "medagent", "medagent") == [
        "docker", "exec", "-i", "medagent_pg", "psql", "-U", "medagent",
        "-d", "medagent", "-v", "ON_ERROR_STOP=1"]


def test_dump_tables_parses_create_table_only():
    """dump 表解析：仅 CREATE TABLE；TABLESPACE/INSERT/SEQUENCE 不误匹配；去重保序。"""
    tables = restore.dump_tables(DUMP_SQL.decode())
    assert tables == ["public.users", "public.runtime_flags", "public.drug_dict"]
    prelude = restore.drop_prelude(tables)
    assert prelude.count("DROP TABLE IF EXISTS") == 3
    assert "DROP DATABASE" not in prelude.upper().replace(
        "DROP TABLE IF EXISTS", ""), "绝不允许出现 drop database"


def test_dry_run_makes_no_writes(iso, capsys):
    """dry-run：打印计划但零 subprocess、零写文件——探针文件保留、内容不变、无 safety tar。"""
    data, fake = iso
    probe = data / "_restore_probe.json"
    probe.write_bytes(b'{"probe": true}')
    before = (data / "drug_dict.json").read_bytes()
    tar_path = _make_backup(data.parent, {"drug_dict.json": b'[{"name":"A"},{"name":"B"}]'})

    assert restore.main(["--backup", str(tar_path)]) == 0
    out = capsys.readouterr().out
    assert "dry-run" in out
    assert "docker exec -i medagent_pg psql -U medagent -d medagent" in out
    assert "public.runtime_flags" in out, "DROP 前奏的表清单应在计划中可见"
    assert "_restore_probe.json" in out, "将被镜像删除的探针文件应在计划中列出"
    assert fake.calls == [], "dry-run 不得执行任何 subprocess"
    assert probe.exists(), "dry-run 不得删除探针文件"
    assert (data / "drug_dict.json").read_bytes() == before, "dry-run 不得覆盖现有文件"
    assert not list((data / "backups").glob("pre_restore_*.tar.gz")), "dry-run 不得生成 safety tar"


def test_yes_full_sequence_and_probe_cleanup(iso, tmp_path):
    """--yes：safety tar 先行（含恢复前状态）→ 覆盖+镜像删探针 → psql stdin=DROP 前奏+dump，
    dump 不落盘；命令序列唯一且正确。"""
    data, fake = iso
    probe = data / "_restore_probe.json"
    probe.write_bytes(b'{"probe": true}')
    (data / "drug_dict.json").write_bytes(b'[{"stale":true}]')
    new_rows = json.dumps([{"name": "A"}, {"name": "B"}, {"name": "C"}]).encode()
    tar_path = _make_backup(data.parent, {"drug_dict.json": new_rows})

    assert restore.main(["--backup", str(tar_path), "--yes"]) == 0

    # 命令序列：恰好两次 subprocess——psql 恢复在前、PG 关键表 count 验证在后
    assert len(fake.calls) == 2
    assert fake.calls[0][0] == [
        "docker", "exec", "-i", "medagent_pg", "psql", "-U", "medagent",
        "-d", "medagent", "-v", "ON_ERROR_STOP=1"]
    count_cmd = fake.calls[1][0]
    assert count_cmd[:8] == ["docker", "exec", "medagent_pg", "psql", "-U", "medagent",
                             "-d", "medagent"], "第二次调用应为 PG count 验证"
    cmd, payload = fake.calls[0]
    # safety tar 先行：psql 调用时刻 safety tar 已存在
    assert len(fake.safety_at_call[0]) == 1
    # psql stdin：DROP 前奏在前、dump 全文在后；只 DROP dump 内表，无 drop database
    assert payload.startswith(b"DROP TABLE IF EXISTS public.users CASCADE;\n")
    assert b"DROP TABLE IF EXISTS public.runtime_flags CASCADE;" in payload
    assert b"CREATE TABLE public.users" in payload, "dump 全文应跟在 DROP 前奏之后"
    assert b"drop database" not in payload.lower()
    # 文件系统效果：覆盖 + 探针清除 + dump 不落盘
    assert json.loads((data / "drug_dict.json").read_bytes()) == json.loads(new_rows)
    assert not probe.exists(), "备份内不存在的探针文件应被镜像删除"
    assert not (data / "pg_dump.sql").exists(), "dump 只入内存喂 psql，不落盘"
    # safety tar 内容：恢复前状态（旧 drug_dict + 探针），可回退
    safety = next((data / "backups").glob("pre_restore_*.tar.gz"))
    with tarfile.open(safety, "r:gz") as tar:
        names = set(tar.getnames())
    assert "data/_restore_probe.json" in names
    assert "data/drug_dict.json" in names
    assert not any(n.startswith("data/backups") for n in names), "safety tar 不递归打包 backups/"


def test_missing_backup_returns_1(iso, capsys):
    """备份路径不存在：返回 1、不执行任何 subprocess。"""
    data, fake = iso
    assert restore.main(["--backup", "nope.tar.gz"]) == 1
    assert fake.calls == []
