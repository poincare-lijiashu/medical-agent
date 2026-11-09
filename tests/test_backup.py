"""轮 A3 / G63：数据备份脚本测试（scripts/backup.py）。

G63 口径变化（评审 §5.2）：
- dump 失败不再"仅告警继续打包"，而是整体失败（退出码 2）、不轮转，保住上一份完整备份；
- dump 先写 .tmp，头尾标记校验通过才原子替换，避免空/半截文件被打进 tar；
- 成功备份附带 `.sha256`（恢复演练据此校验产物）。
"""
import tarfile

import pytest

from scripts import backup

# pg_dump plain 格式的最小可信产物（头尾标记齐全、体积达标）
VALID_DUMP = (
    b"--\n-- PostgreSQL database dump\n--\n\n"
    b"CREATE TABLE t (id int);\n" + b"-- filler\n" * 400 +
    b"--\n-- PostgreSQL database dump complete\n--\n"
)
TRUNCATED_DUMP = b"--\n-- PostgreSQL database dump\n--\n\nCREATE TABLE t (id int);\n"


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
    from backend.config import settings as _settings
    monkeypatch.setattr(_settings, "pg_user", "medagent")
    monkeypatch.setattr(_settings, "pg_database", "medagent")
    return data


def test_pg_dump_cmd_sequence():
    """命令序列：docker exec <容器> pg_dump -U <用户> --schema public <库>。

    评测 A5：必须限定 public schema——共享实例里其余 9 个 schema 的 211 张表
    属于院内其他系统，全库 dump 会把它们的 PHI 混入备份。
    """
    assert backup.pg_dump_cmd("medagent_pg", "medagent", "medagent") == \
        ["docker", "exec", "medagent_pg", "pg_dump", "-U", "medagent",
         "--schema", "public", "medagent"]


class _FakeRun:
    """subprocess.run 替身：记录命令，向 stdout 写 payload，返回可指定 returncode。"""

    def __init__(self, returncode=0, payload=VALID_DUMP):
        self.returncode = returncode
        self.payload = payload
        self.stderr = b"boom"
        self.calls: list[list[str]] = []

    def __call__(self, cmd, **kwargs):
        self.calls.append(list(cmd))
        out = kwargs.get("stdout")
        if out is not None and self.payload is not None:
            out.write(self.payload)
        return self


def test_run_pg_dump_success_writes_valid_dump(iso, monkeypatch):
    fake = _FakeRun(0)
    monkeypatch.setattr(backup.subprocess, "run", fake)

    backup.run_pg_dump("medagent_pg", "medagent", "medagent")

    assert fake.calls == [["docker", "exec", "medagent_pg", "pg_dump",
                           "-U", "medagent", "--schema", "public", "medagent"]]
    assert (iso / "pg_dump.sql").read_bytes() == VALID_DUMP
    assert not (iso / "pg_dump.sql.tmp").exists(), "临时文件必须被原子替换掉"


def test_run_pg_dump_failure_raises(iso, monkeypatch):
    """pg_dump 非 0 → BackupError，且不留下任何 dump 文件（不破坏上一份）。"""
    monkeypatch.setattr(backup.subprocess, "run", _FakeRun(1))

    with pytest.raises(backup.BackupError):
        backup.run_pg_dump("medagent_pg", "medagent", "medagent")
    assert not (iso / "pg_dump.sql").exists()
    assert not (iso / "pg_dump.sql.tmp").exists()


def test_run_pg_dump_rejects_truncated_output(iso, monkeypatch):
    """dump 缺尾标记（半截输出）→ BackupError，不进 tar。"""
    monkeypatch.setattr(backup.subprocess, "run", _FakeRun(0, payload=TRUNCATED_DUMP))

    with pytest.raises(backup.BackupError):
        backup.run_pg_dump("medagent_pg", "medagent", "medagent")


def test_main_pg_dump_failure_exits_nonzero_without_rotation(iso, monkeypatch):
    """main：dump 失败 → 退出码 2、不产新备份、不删旧备份（旧备份不被坏备份挤掉）。"""
    bdir = iso / "backups"
    bdir.mkdir(parents=True)
    good = bdir / "backup_20260101_000000.tar.gz"
    good.write_bytes(b"previous-good-backup")
    monkeypatch.setattr(backup.subprocess, "run", _FakeRun(1))

    assert backup.main([]) == 2
    assert sorted(p.name for p in bdir.glob("backup_*.tar.gz")) == [good.name], \
        "失败时不得新增备份，也不得轮转删除旧备份"


def test_main_success_archives_with_checksum(iso, monkeypatch):
    """main：成功 → tar 含普通 JSON（排除 backups 自身与可重建缓存）+ .sha256 校验文件。"""
    monkeypatch.setattr(backup.subprocess, "run", _FakeRun(0))

    assert backup.main([]) == 0
    archives = list((iso / "backups").glob("backup_*.tar.gz"))
    assert len(archives) == 1
    with tarfile.open(archives[0], "r:gz") as tar:
        names = set(tar.getnames())
    assert "data/drug_dict.json" in names, "普通 JSON 真源应入包"
    assert not any(n.startswith("data/backups") for n in names), "备份目录自身不得入包（防递归）"
    assert "data/kb/pubmed_v2_cache.json" not in names, "可重建缓存不得入包"
    assert not (iso / "pg_dump.sql").exists(), "dump 落盘副本打包后应清理"

    checksum = archives[0].with_name(archives[0].name + ".sha256")
    assert checksum.exists(), "必须写 sha256 供恢复演练校验"
    hex_digest, name = checksum.read_text(encoding="utf-8").split()
    assert name == archives[0].name
    assert hex_digest == backup.sha256_of(archives[0])


def test_main_dry_run_prints_steps_without_output(iso, monkeypatch, capsys):
    """--dry-run：打印将执行步骤（含 pg_dump 命令序列），不产生任何文件。"""
    fake = _FakeRun(0)
    monkeypatch.setattr(backup.subprocess, "run", fake)

    assert backup.main(["--dry-run"]) == 0
    out = capsys.readouterr().out
    assert "docker exec medagent_pg pg_dump -U medagent --schema public medagent" in out
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
    (bdir / (names[0] + ".sha256")).write_text("deadbeef  x\n", encoding="utf-8")

    removed = backup.rotate_backups(7)

    assert sorted(removed) == names[:2], "应删除最旧的 2 份"
    remaining = sorted(p.name for p in bdir.glob("backup_*.tar.gz"))
    assert remaining == names[2:], "应保留最近 7 份"
    assert not (bdir / (names[0] + ".sha256")).exists(), "被删备份的校验文件应一并清理"


def test_rotate_backups_fewer_than_keep(iso):
    """备份不足 --keep 份 → 全保留，零删除。"""
    bdir = iso / "backups"
    bdir.mkdir(parents=True)
    (bdir / "backup_20260901_000001.tar.gz").write_bytes(b"x")
    assert backup.rotate_backups(7) == []
