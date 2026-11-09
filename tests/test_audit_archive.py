"""第 117 批：审计日志归档脚本（按月冷存 + 热表清理）。

锁定：
- 默认 dry-run：只统计、不写不删；
- `--before` 必填且校验格式；截止点按本地时区当天 00:00（半开窗，与审计日期筛选同口径）；
- `--apply`：单事务 `DELETE ... RETURNING` → 按月追加写 JSONL → 提交；写文件异常则回滚；
- 分组/序列化口径：月份键为本地时区 `YYYY-MM`，payload 兼容 dict / JSON 字符串 / 非法值。
"""
import asyncio
import importlib.util
import json
from datetime import datetime
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent


def _load():
    spec = importlib.util.spec_from_file_location(
        "archive_audit_log", ROOT / "scripts" / "archive_audit_log.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_cutoff_from_local_midnight():
    mod = _load()
    c = mod.cutoff_from("2026-01-01")
    assert c.year == 2026 and c.month == 1 and c.day == 1
    assert (c.hour, c.minute, c.second) == (0, 0, 0)
    assert c.utcoffset() is not None  # 本地时区（aware）


def test_cutoff_invalid_exits():
    mod = _load()
    for bad in ("2026/01/01", "2026-13-01", ""):
        try:
            mod.cutoff_from(bad)
        except SystemExit:
            continue
        raise AssertionError(f"应拒绝非法日期：{bad!r}")


def test_before_days_avoids_gnu_date_dependency():
    """评测 06 P1-2：CronJob 用 `--before-days`，由脚本算「今天 - N 天」。"""
    mod = _load()
    from datetime import date, timedelta

    expect = (datetime.now().astimezone().date() - timedelta(days=365)).isoformat()
    assert mod.cutoff_date_from_days(365) == expect
    assert mod.cutoff_date_from_days(0) == date.today().isoformat()
    assert mod.cutoff_from(mod.cutoff_date_from_days(1)).date() < date.today()
    try:
        mod.cutoff_date_from_days(-1)
    except SystemExit:
        pass
    else:
        raise AssertionError("负数天数应被拒绝")


def test_before_and_before_days_are_mutually_exclusive():
    """`--before` 与 `--before-days` 必须且只能给一个（argparse 直接退出）。"""
    mod = _load()
    for argv in ([], ["--before", "2026-01-01", "--before-days", "30"]):
        with pytest.raises(SystemExit):
            mod.main(argv)


def test_group_and_summary_by_month():
    mod = _load()
    rows = [{"ts": datetime(2026, 1, 5, 10), "n": 2},
            {"ts": datetime(2026, 1, 31, 23), "n": 1},
            {"ts": datetime(2026, 2, 1, 0), "n": 4}]
    assert mod._summarize(rows) == {"2026-01": 3, "2026-02": 4}
    grouped = mod.group_by_month([
        {"id": 1, "ts": datetime(2026, 1, 5), "event_type": "a", "actor": "u", "payload": {}},
        {"id": 2, "ts": datetime(2026, 2, 5), "event_type": "b", "actor": "u", "payload": {}},
        {"id": 3, "ts": datetime(2026, 2, 9), "event_type": "c", "actor": "u", "payload": {}},
    ])
    assert [len(v) for v in grouped.values()] == [1, 2]
    assert list(grouped) == ["2026-01", "2026-02"]


def test_row_to_json_payload_variants():
    mod = _load()
    base = {"id": 7, "ts": datetime(2026, 1, 5, 8, 30), "event_type": "admin.x", "actor": "u"}
    d = json.loads(mod.row_to_json({**base, "payload": {"a": 1}}))
    assert d["payload"] == {"a": 1} and d["id"] == 7 and d["event_type"] == "admin.x"
    s = json.loads(mod.row_to_json({**base, "payload": '{"b": 2}'}))
    assert s["payload"] == {"b": 2}
    bad = json.loads(mod.row_to_json({**base, "payload": object()}))
    assert "_raw" in bad["payload"]


def test_main_requires_before():
    mod = _load()
    try:
        mod.main(["--apply"])
    except SystemExit as e:
        assert int(e.code or 0) != 0
        return
    raise AssertionError("缺 --before 应退出")


class _FakeTx:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        self.conn.rolled_back = exc_type is not None
        return False


class _FakeConn:
    def __init__(self, rows):
        self.rows = rows
        self.deleted = False
        self.rolled_back = False
        self.closed = False
        self.statements = []  # 评测 04 P1-8：记录 SET LOCAL 等非查询语句

    def transaction(self):
        return _FakeTx(self)

    async def execute(self, sql, *args):
        self.statements.append(sql)
        return "OK"

    async def fetch(self, sql, *args):
        if sql.lstrip().startswith("SELECT ts, count(*)"):
            return [{"ts": r["ts"], "n": 1} for r in self.rows]
        self.deleted = True
        return self.rows

    async def fetchval(self, sql, *args):
        return 0

    async def close(self):
        self.closed = True


def _row(i, month):
    return {"id": i, "ts": datetime(2026, month, 10, 9), "event_type": "admin.x",
            "actor": "admin01", "payload": {"k": i}}


def test_apply_writes_files_and_deletes(monkeypatch, tmp_path):
    mod = _load()
    conn = _FakeConn([_row(1, 1), _row(2, 2), _row(3, 2)])

    async def _connect(dsn):
        return conn

    monkeypatch.setattr(mod.asyncpg, "connect", _connect)
    rc = asyncio.run(mod._run("2026-03-01", True, tmp_path, "postgresql://x/y"))
    assert rc == 0 and conn.deleted and not conn.rolled_back and conn.closed
    # 评测 04 P1-8：DELETE 之前必须先在同一事务内声明归档意图（append-only 白名单）
    assert any("SET LOCAL medassist.audit_archive = 'on'" in s for s in conn.statements)
    jan = (tmp_path / "audit-2026-01.jsonl").read_text(encoding="utf-8").splitlines()
    feb = (tmp_path / "audit-2026-02.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(jan) == 1 and len(feb) == 2
    assert json.loads(feb[0])["payload"] == {"k": 2}


def test_dry_run_writes_nothing(monkeypatch, tmp_path):
    mod = _load()
    conn = _FakeConn([_row(1, 1)])

    async def _connect(dsn):
        return conn

    monkeypatch.setattr(mod.asyncpg, "connect", _connect)
    rc = asyncio.run(mod._run("2026-03-01", False, tmp_path, "postgresql://x/y"))
    assert rc == 0 and not conn.deleted
    assert not (tmp_path / "audit-2026-01.jsonl").exists()


def test_apply_rolls_back_when_write_fails(monkeypatch, tmp_path):
    """写文件失败必须回滚（不能出现"删了但没存档"）。"""
    mod = _load()
    conn = _FakeConn([_row(1, 1)])

    async def _connect(dsn):
        return conn

    monkeypatch.setattr(mod.asyncpg, "connect", _connect)

    def _boom(self, *a, **k):
        raise OSError("disk full")

    monkeypatch.setattr(Path, "open", _boom)
    try:
        asyncio.run(mod._run("2026-03-01", True, tmp_path / "x", "postgresql://x/y"))
    except OSError:
        pass
    assert conn.rolled_back is True
