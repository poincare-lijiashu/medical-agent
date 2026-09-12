"""B2 审计轮转归档测试：大小阈值/跨天触发轮转、归档目录生成 + gzip、主文件重建、
启动清理、主文件本身不被归档、幂等。全部在 tmp_path 隔离，不碰真实 data/audit。"""
import gzip
import os
import time

from backend.core import medical_audit as ma


def _new_log(tmp_path):
    return ma.AuditLog(path=str(tmp_path / "audit.jsonl"))


def _rotated_files(tmp_path):
    return sorted(p for p in os.listdir(tmp_path)
                  if p.startswith("audit-") and p.endswith(".jsonl"))


def test_rotate_on_size_threshold(monkeypatch, tmp_path):
    monkeypatch.setattr(ma, "_ROTATE_BYTES", 256)
    log = _new_log(tmp_path)
    for i in range(20):
        log.write(event_type="t", action="a", actor="u", payload={"i": i})
    rotated = _rotated_files(tmp_path)
    assert rotated, "超阈值应触发轮转并产生 audit-YYYYMMDD-HHMMSS.jsonl"
    # 主文件被重建且当前小于阈值；轮转文件保留了历史内容
    assert (tmp_path / "audit.jsonl").is_file()
    assert os.path.getsize(tmp_path / "audit.jsonl") < 256
    total_lines = 0
    for name in rotated:
        with open(tmp_path / name, encoding="utf-8") as f:
            total_lines += sum(1 for line in f if line.strip())
    assert total_lines + _main_lines(tmp_path) >= 20, "轮转不得丢审计行"
    assert len(log.entries) == 20, "内存有界缓存不受轮转影响"


def _main_lines(tmp_path) -> int:
    p = tmp_path / "audit.jsonl"
    if not p.is_file():
        return 0
    with open(p, encoding="utf-8") as f:
        return sum(1 for line in f if line.strip())


def test_rotate_on_day_rollover(monkeypatch, tmp_path):
    log = _new_log(tmp_path)
    log.write(event_type="t", action="a", actor="u", payload={})
    assert _rotated_files(tmp_path) == []  # 未跨天不轮转
    # 主文件 mtime 回拨到两天前 → 下一次写入应跨天轮转
    old = time.time() - 2 * 86400
    os.utime(tmp_path / "audit.jsonl", (old, old))
    log.write(event_type="t", action="b", actor="u", payload={})
    rotated = _rotated_files(tmp_path)
    assert len(rotated) == 1, "跨天应触发一次轮转"
    with open(tmp_path / rotated[0], encoding="utf-8") as f:
        assert '"action"' in f.read().replace(" ", "") or "a" in f.read()


def test_archive_expired_moves_to_archive_gzip(monkeypatch, tmp_path):
    log = _new_log(tmp_path)
    log.write(event_type="t", action="a", actor="u", payload={})
    # 造一个 31 天前的历史轮转文件
    old_name = tmp_path / "audit-20260101-000000.jsonl"
    old_name.write_text('{"ts": "old", "event_type": "t"}\n', encoding="utf-8")
    past = time.time() - 31 * 86400
    os.utime(old_name, (past, past))
    log._archive_expired()
    gz = tmp_path / "archive" / "audit-20260101-000000.jsonl.gz"
    assert gz.is_file(), "过期轮转文件应 gzip 归档到 archive/"
    assert not old_name.exists(), "归档后原文件应删除"
    with gzip.open(gz, "rt", encoding="utf-8") as f:
        content = f.read()
    assert '"old"' in content, "gzip 归档内容应与原文件一致"
    log._archive_expired()  # 幂等：再次执行不再命中、不报错
    assert gz.is_file()


def test_archive_skips_recent_and_main_file(monkeypatch, tmp_path):
    log = _new_log(tmp_path)
    log.write(event_type="t", action="a", actor="u", payload={})
    fresh = tmp_path / "audit-20990101-000000.jsonl"  # 未来时间戳（一定未过期）
    fresh.write_text('{"ts": "fresh"}\n', encoding="utf-8")
    log._archive_expired()
    assert fresh.exists() and not (tmp_path / "archive").exists()
    assert (tmp_path / "audit.jsonl").exists(), "主文件 audit.jsonl 绝不被归档"


def test_bootstrap_cleans_expired_on_startup(monkeypatch, tmp_path):
    monkeypatch.setattr(ma, "_ARCHIVE_DAYS", 30)
    old_name = tmp_path / "audit-20250101-000000.jsonl"
    old_name.write_text('{"ts": "ancient"}\n', encoding="utf-8")
    past = time.time() - 40 * 86400
    os.utime(old_name, (past, past))
    _new_log(tmp_path)  # 新实例启动（_bootstrap）即清理
    assert not old_name.exists()
    assert (tmp_path / "archive" / "audit-20250101-000000.jsonl.gz").is_file()


def test_rotation_failure_does_not_break_writes(monkeypatch, tmp_path):
    monkeypatch.setattr(ma, "_ROTATE_BYTES", 16)
    log = _new_log(tmp_path)

    def _boom(self):
        raise PermissionError("locked")

    monkeypatch.setattr(ma.AuditLog, "_archive_expired", _boom)
    log.write(event_type="t", action="a", actor="u", payload={"i": 1})
    log.write(event_type="t", action="a", actor="u", payload={"i": 2})  # 第二次写入触发轮转→清理异常路径
    assert (tmp_path / "audit.jsonl").is_file(), "轮转/清理异常不得阻断审计写入"
