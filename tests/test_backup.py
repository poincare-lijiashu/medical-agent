"""轮 A3：数据备份脚本轻测（scripts/backup.py）。

- pg_dump 命令序列断言（docker exec 容器名/用户/库）；
- pg_dump 失败不中断：main 仍完成 tar 打包（部分备份好过没有备份）；
- 排除清单：data/backups 自身与可重建缓存（kb/pubmed_v2_cache.json）不入包；
- 轮转：9 份假备份断言删到保留最近 --keep=7 份；
- --dry-run 只打印步骤不落盘。
"""
import json

import pytest

from scripts import backup


@pytest.fixture()
def iso(monkeypatch, tmp_path):
    """隔离 data 目录与 dump 文件（绝不触碰真实 data/）。"""
    data = tmp_path / "data"
    (data / "kb").mkdir(parents=True)
    (data / "kb" / "pubmed_v2_cache.json").write_text("{}", encoding="utf-8")
    (data / "drug_dict.json").write_text("[]", encoding="utf-8")
    monkeypatch.setattr(backup, "DATA_DIR", data)
    monkeypatch.setattr(backup, "BACKUP_DIR", data / "backups")
    monkeypatch.setattr(backup, "DUMP_FILE", data / "pg_dump.sql")
    return data


def test_pg_dump_cmd_sequence():
    """命令序列：docker exec <容器> pg_dump -U <用户> <库>。"""
    assert backup.pg_dump_cmd("medagent_pg", "medagent", "medagent") == \
        ["docker", "exec", "medagent_pg", "pg_dump", "-U", "medagent", "medagent"]


class _FakeRun:
    """subprocess.run 替身：记录命令，返回可指定 returncode 的结果。"""

    def __init__(self, returncode=0):
        self.returncode = returncode
        self.stderr = b""
        self.calls: list[list[str]] = []

    def __call__(self, cmd, **kwargs):
        self.calls.append(list(cmd))
        return self


def test_run_pg_dump_success_writes_dump(iso, monkeypatch):
    fake = _FakeRun(0)
    monkeypatch.setattr(backup.subprocess, "run", fake)
    assert backup.run_pg_dump("medagent_pg", "medagent", "medagent") is True
    assert fake.calls == [["docker", "exec", "medagent_pg", "pg_dump",
                           "-U", "medagent", "medagent"]]
    assert (iso / "pg_dump.sql").exists()


def test_run_pg_dump_failure_returns_false(iso, monkeypatch):
    """pg_dump 失败 → 仅返回 False（调用方继续打包 JSON 部分，不中断）。"""
    fake = _FakeRun(1)
    monkeypatch.setattr(backup.subprocess, "run", fake)
    assert backup.run_pg_dump("medagent_pg", "medagent", "medagent") is False


def test_main_pg_dump_failure_still_archives(iso, monkeypatch, capsys):
    """main 全链路：pg_dump 失败不中断 → tar 仍产出且包含普通 JSON、排除清单生效。"""
    fake = _FakeRun(1)
    monkeypatch.setattr(backup.subprocess, "run", fake)
    assert backup.main([]) == 0
    archives = list((iso / "backups").glob("backup_*.tar.gz"))
    assert len(archives) == 1, "pg_dump 失败也必须产出 tar 备份"
    import tarfile
    with tarfile.open(archives[0], "r:gz") as tar:
        names = set(tar.getnames())
    assert "data/drug_dict.json" in names, "普通 JSON 真源应入包"
    assert not any(n.startswith("data/backups") for n in names), "备份目录自身不得入包（防递归）"
    assert "data/kb/pubmed_v2_cache.json" not in names, "可重建缓存不得入包"
    assert not (iso / "pg_dump.sql").exists(), "dump 落盘副本打包后应清理"


def test_main_dry_run_prints_steps_without_output(iso, monkeypatch, capsys):
    """--dry-run：打印将执行步骤（含 pg_dump 命令序列），不产生任何文件。"""
    fake = _FakeRun(0)
    monkeypatch.setattr(backup.subprocess, "run", fake)
    assert backup.main(["--dry-run"]) == 0
    out = capsys.readouterr().out
    assert "docker exec medagent_pg pg_dump -U medagent medagent" in out
    assert "kb/pubmed_v2_cache.json" in out, "排除清单应在 dry-run 输出中注明"
    assert not list((iso / "backups").glob("backup_*.tar.gz")), "dry-run 不得落盘"
    assert fake.calls == [], "dry-run 不得执行 pg_dump"


def test_rotate_backups_keeps_latest_seven(iso):
    """轮转：造 9 份假备份（时间戳文件名字典序=时序），断言删到保留最近 7 份。"""
    bdir = iso / "backups"
    bdir.mkdir(parents=True)
    names = [f"backup_20260901_00000{i}.tar.gz" for i in range(1, 10)]
    for n in names:
        (bdir / n).write_bytes(b"x")
    removed = backup.rotate_backups(7)
    assert sorted(removed) == names[:2], "应删除最旧的 2 份"
    remaining = sorted(p.name for p in bdir.glob("backup_*.tar.gz"))
    assert remaining == names[2:], "应保留最近 7 份"


def test_rotate_backups_fewer_than_keep(iso):
    """备份不足 --keep 份 → 全保留，零删除。"""
    bdir = iso / "backups"
    bdir.mkdir(parents=True)
    (bdir / "backup_20260901_000001.tar.gz").write_bytes(b"x")
    assert backup.rotate_backups(7) == []
