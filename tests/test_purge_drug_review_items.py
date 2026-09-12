"""问题2：遗留 drug 审核记录清理脚本测试（幂等 + 备份 + 原子写 + 审计不动）。

脚本隔离到 tmp_path 队列文件运行（不碰真实 data/review/queue.json）；
PG 走显式离线模式（PG_OFFLINE=on → purge_pg_drug_rows 返回 None，纯静默不连库）。
"""
import importlib.util
import json
import os
import sys
from pathlib import Path

import pytest

BASE = Path(__file__).resolve().parents[1]

_SPEC = importlib.util.spec_from_file_location(
    "purge_drug_review_items", BASE / "scripts" / "purge_drug_review_items.py")
purge_mod = importlib.util.module_from_spec(_SPEC)
sys.modules.setdefault("purge_drug_review_items", purge_mod)
_SPEC.loader.exec_module(purge_mod)


@pytest.fixture(autouse=True)
def _pg_offline(monkeypatch):
    """测试进程显式离线：PG 分支恒跳过（返回 None），绝不连真实库。"""
    monkeypatch.setenv("PG_OFFLINE", "on")
    monkeypatch.setattr(purge_mod.pg_store, "init", lambda: _noop_false(), raising=False)


def _noop_false():
    async def _f():
        return False
    return _f()


def _write_queue(path: Path, items: list[dict]) -> None:
    path.write_text(json.dumps(items, ensure_ascii=False), encoding="utf-8")


def test_purge_removes_only_drug_and_keeps_audit_semantics(tmp_path):
    """①移除全部 agent==='drug' 项（含 pending/rejected 各状态）；②qc 项原样保留；
    ③先备份（.bak- 时间戳文件内容=原始）；④原子写回后文件可解析且无 .tmp 残留。"""
    q = tmp_path / "queue.json"
    items = [
        {"id": "rev-a1", "agent": "qc", "status": "pending"},
        {"id": "rev-d1", "agent": "drug", "status": "rejected"},
        {"id": "rev-d2", "agent": "drug", "status": "approved"},
        {"id": "rev-a2", "agent": "imaging", "status": "pending"},
    ]
    _write_queue(q, items)
    r = purge_mod.purge_json_queue(str(q))
    assert r["removed"] == 2 and r["remaining"] == 2
    assert r["backup"] and Path(r["backup"]).is_file()
    assert json.loads(Path(r["backup"]).read_text(encoding="utf-8")) == items  # 备份=原始
    kept = json.loads(q.read_text(encoding="utf-8"))
    assert [i["id"] for i in kept] == ["rev-a1", "rev-a2"]  # drug 全清、其余保序保留
    assert not list(tmp_path.glob("*.tmp"))  # 原子写无临时残留


def test_purge_idempotent_second_run_zero(tmp_path, capsys):
    """幂等：首次移除 N 项后，二次运行移除 0 项且不再产生新备份、不改写文件。"""
    q = tmp_path / "queue.json"
    _write_queue(q, [{"id": "rev-d1", "agent": "drug", "status": "pending"},
                     {"id": "rev-a1", "agent": "qc", "status": "approved"}])
    first = purge_mod.purge_json_queue(str(q))
    assert first["removed"] == 1 and first["remaining"] == 1
    snapshot = q.read_text(encoding="utf-8")
    backups = set(tmp_path.glob("*.bak-*"))
    second = purge_mod.purge_json_queue(str(q))
    assert second["removed"] == 0 and second["remaining"] == 1
    assert second["backup"] == ""  # 无 drug 项 → 不写盘不备份（文件字节不变）
    assert q.read_text(encoding="utf-8") == snapshot
    assert set(tmp_path.glob("*.bak-*")) == backups


def test_purge_missing_or_malformed_file_safe(tmp_path):
    """文件不存在 / 非列表内容 → 安全零动作（removed=0），绝不抛异常。"""
    assert purge_mod.purge_json_queue(str(tmp_path / "nope.json")) == \
        {"removed": 0, "remaining": 0, "backup": ""}
    bad = tmp_path / "queue.json"
    bad.write_text('{"broken": true}', encoding="utf-8")
    assert purge_mod.purge_json_queue(str(bad))["removed"] == 0
    assert json.loads(bad.read_text(encoding="utf-8")) == {"broken": True}  # 原样未动


def test_pg_branch_offline_reports_skip(monkeypatch, capsys):
    """PG 离线：purge_pg_drug_rows 返回 None，main 输出如实报告跳过 + 移除/剩余计数
    + 审计 jsonl 未动声明（不连库不抛异常）。"""
    rc = purge_mod.main()
    assert rc == 0
    out = capsys.readouterr().out
    assert "[PG]   跳过" in out
    assert "审计 jsonl 未做任何改动" in out
    assert "移除" in out and "剩余" in out
