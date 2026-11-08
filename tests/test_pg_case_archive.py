"""第 90 批：病例库列表/统计/详情/软删除下推 PG。

原实现：`/case-archive` 每次读全表后按科室/状态/日期区间内存过滤，统计头再读一遍；
`/case-archive/mine`、`remove`、`get` 也是全表线性查找。本批：
- 列表/我的：固定 where + `$n`（日期用 `COALESCE(substring(archived_at,1,10),'')` 与
  内存字符串比较同口径），排序 `archived_at DESC NULLS LAST, id DESC`；
- 统计：`count(*) FILTER (WHERE status='active')` 一次聚合；
- 详情/软删除：主键直查 + 单行 UPSERT + 镜像就地更新；
- 空表/无池/离线/异常 → `None`，回落既有内存实现（行为不变）。
"""
import json

import pytest
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import case_archive as ca
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

A1 = {"id": "a1", "dept": "内科", "status": "active", "submitted_by": "doctor01",
      "archived_at": "2026-01-01T00:00:00+00:00", "patient_ref": "咳嗽三天",
      "record": {"主诉": "咳嗽三天"}, "labs": {}}
A2 = {"id": "a2", "dept": "内科", "status": "removed", "submitted_by": "doctor01",
      "archived_at": "2026-02-01T00:00:00+00:00", "patient_ref": "发热",
      "removed_by": "qc01", "removed_reason": "重复归档"}
A3 = {"id": "a3", "dept": "外科", "status": "active", "submitted_by": "doctor02",
      "archived_at": "2026-03-01T00:00:00+00:00", "patient_ref": "腹痛"}
ROWS = [A1, A2, A3]


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, rows):
        self.conn = _FakeConn(rows)

    def acquire(self):
        return _FakeAcquire(self.conn)


def _param_idx(part: str) -> int:
    return int(part.split("$")[1].split(" ")[0]) - 1


def _match(item, where, args):
    if where == "TRUE":
        return True
    for part in where.split(" AND "):
        if part == "TRUE":
            continue
        if part == "status = 'active'":
            if item.get("status") != "active":
                return False
        elif part.startswith("dept = $"):
            if item.get("dept") != args[_param_idx(part)]:
                return False
        elif part.startswith("status = $"):
            if item.get("status") != args[_param_idx(part)]:
                return False
        elif part.startswith("data->>'submitted_by' = $"):
            if item.get("submitted_by") != args[_param_idx(part)]:
                return False
        elif part.startswith("id = $"):
            if item.get("id") != args[_param_idx(part)]:
                return False
        elif part.startswith("COALESCE(substring("):
            ts = str(item.get("archived_at") or "")[:10]
            val = args[_param_idx(part)]
            if ">= $" in part and not ts >= val:
                return False
            if "<= $" in part and not ts <= val:
                return False
        else:
            raise AssertionError(f"假池未实现的 where 片段: {part}")
    return True


class _FakeConn:
    def __init__(self, rows):
        self.rows = list(rows)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM case_archive":
            return len(self.rows)
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetchrow(self, sql, *a):
        s = " ".join(sql.split())
        assert "FILTER (WHERE status = 'active')" in s, s[:120]
        total = len(self.rows)
        active = sum(1 for r in self.rows if r.get("status") == "active")
        return {"total": total, "active": active}

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        assert s.startswith("SELECT data FROM case_archive WHERE"), s[:100]
        where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        rows = [r for r in self.rows if _match(r, where, a)]
        return [{"data": json.dumps(r, ensure_ascii=False)}
                for r in sorted(rows,
                                key=lambda r: (r.get("archived_at") or "", r.get("id") or ""),
                                reverse=True)]


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 列表/统计 ----------

def test_list_cases_desc_all(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in ca.list_cases()] == ["a3", "a2", "a1"]


def test_list_cases_filters(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in ca.list_cases(dept="内科")] == ["a2", "a1"]
    assert [c["id"] for c in ca.list_cases(status="removed")] == ["a2"]
    assert [c["id"] for c in ca.list_cases(dept="内科", status="active")] == ["a1"]


def test_list_cases_date_range(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in ca.list_cases(date_from="2026-02-01")] == ["a3", "a2"]
    assert [c["id"] for c in ca.list_cases(date_to="2026-02-01")] == ["a2", "a1"]
    assert [c["id"] for c in ca.list_cases(date_from="2026-02-01",
                                          date_to="2026-02-01")] == ["a2"]


def test_list_mine_active_only(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in ca.list_mine("doctor01")] == ["a1"]
    assert ca.list_mine("doctor02") == [A3]


def test_stats_aggregate(monkeypatch):
    _use_pool(monkeypatch)
    assert ca.stats() == {"total": 3, "active": 2, "removed": 1}


def test_get_by_pk(monkeypatch):
    _use_pool(monkeypatch)
    monkeypatch.setattr(ca, "_load", _boom_load)
    assert ca.get("a3")["dept"] == "外科"
    assert ca.get("nope") is None


def _boom_load():
    raise AssertionError("PG 命中时不应再读全表")


# ---------- 写入 ----------

def test_remove_single_row(monkeypatch):
    calls = {"upsert": [], "mirror": []}
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_case_archive_row",
                        lambda aid: (True, dict(A1)) if aid == "a1" else (True, None))
    monkeypatch.setattr(pg_store, "upsert_case_archive_row",
                        lambda item: calls["upsert"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_upsert",
                        lambda path, item, key="id": calls["mirror"].append(item))
    monkeypatch.setattr(ca, "_load", _boom_load)
    item = ca.remove("a1", "qc01", "重复归档")
    assert item["status"] == "removed" and item["removed_by"] == "qc01"
    assert len(calls["upsert"]) == 1 and calls["mirror"]
    with pytest.raises(ValueError, match="不存在"):
        ca.remove("nope", "qc01", "x")


def test_remove_repeat_guard(monkeypatch):
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_case_archive_row",
                        lambda aid: (True, dict(A2)))
    monkeypatch.setattr(ca, "_load", _boom_load)
    with pytest.raises(ValueError, match="已移除"):
        ca.remove("a2", "qc01", "再删一次")


def test_archive_from_review_single_row(monkeypatch):
    calls = {"upsert": [], "mirror": []}
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "upsert_case_archive_row",
                        lambda item: calls["upsert"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_upsert",
                        lambda path, item, key="id": calls["mirror"].append(item))
    monkeypatch.setattr(ca, "_load", _boom_load)
    entry = ca.archive_from_review({
        "agent": "qc", "status": "approved", "answer": "质控通过",
        "confidence": 0.95, "reviewed_by": "qc01", "submitted_by": "doctor01",
        "meta": {"record": {"主诉": "咳嗽三天", "科室": "内科"}, "labs": {}},
    })
    assert entry["patient_ref"] == "咳嗽三天" and entry["status"] == "active"
    assert len(calls["upsert"]) == 1 and calls["mirror"]


# ---------- 回落 ----------

def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert pg_store.case_archive_list("TRUE", ()) is None
    assert pg_store.case_archive_stats() is None


def test_no_pool_falls_back(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(ca, "_load", lambda: [dict(r) for r in ROWS])
    assert [c["id"] for c in ca.list_cases(dept="内科")] == ["a2", "a1"]
    assert ca.stats() == {"total": 3, "active": 2, "removed": 1}
    assert [c["id"] for c in ca.list_mine("doctor01")] == ["a1"]


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert pg_store.case_archive_list("TRUE", ()) is None
    assert pg_store.case_archive_stats() is None


# ---------- 路由 ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app)


def test_route_case_archive_uses_pg(monkeypatch, tmp_path):
    _use_pool(monkeypatch)
    monkeypatch.setattr(ca, "_load", _boom_load)
    c = _client(monkeypatch, tmp_path)
    tok = create_access_token("qc01", "qc")
    r = c.get("/api/v1/medical/case-archive", headers={"Authorization": "Bearer " + tok},
              params={"dept": "内科"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert [i["id"] for i in d["items"]] == ["a2", "a1"]
    assert d["stats"] == {"total": 3, "active": 2, "removed": 1}
