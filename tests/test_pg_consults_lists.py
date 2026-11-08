"""第 89 批：会诊列表下推 PG（收件箱 / 我发起 / 我参与）。

原实现：三个列表都是 `_load()[::-1]` 全表扫描后内存过滤；收件箱还被侧栏角标每 60s 拉一次。
本批改为固定 where 常量的 PG 条件查询（jsonb 数组包含匹配），排序 `created_at DESC, id DESC`
（与内存 `[::-1]` 同序）；空表/无池/离线/异常回落既有内存实现，行为不变。
"""
import json

from fastapi.testclient import TestClient

from backend.core import consults as consult_mod
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

INBOX_WHERE = "status = 'open' AND data->'target_depts' @> $1::jsonb"
INITIATOR_WHERE = "initiator = $1"
PARTICIPATED_WHERE = "data->'opinions' @> $1::jsonb"

C1 = {"id": "c1", "initiator": "doctor01", "target_depts": ["心内科"], "opinions": [],
      "status": "open", "created_at": "2026-01-01T00:00:00+00:00"}
C2 = {"id": "c2", "initiator": "doctor02", "target_depts": ["心内科", "骨科"],
      "opinions": [{"doctor": "doctor01", "dept": "心内科", "content": "建议查心电图"}],
      "status": "open", "created_at": "2026-01-02T00:00:00+00:00"}
C3 = {"id": "c3", "initiator": "doctor01", "target_depts": ["心内科"], "opinions": [],
      "status": "closed", "created_at": "2026-01-03T00:00:00+00:00"}
C4 = {"id": "c4", "initiator": "doctor01", "target_depts": ["骨科"], "opinions": [],
      "status": "open", "created_at": "2026-01-04T00:00:00+00:00"}
ROWS = [C1, C2, C3, C4]


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


def _contains(item, key, probe):
    """jsonb @> 语义：目标数组/对象必须包含 probe 中的每个元素/键值。"""
    val = item.get(key)
    if isinstance(probe, list):
        if not isinstance(val, list):
            return False
        for want in probe:
            if isinstance(want, dict):
                if not any(isinstance(x, dict)
                           and all(x.get(k) == v for k, v in want.items()) for x in val):
                    return False
            elif want not in val:
                return False
        return True
    return False


def _match(item, where, args):
    if where == INBOX_WHERE:
        probe = json.loads(args[0])
        return item.get("status") == "open" and _contains(item, "target_depts", probe)
    if where == INITIATOR_WHERE:
        return item.get("initiator") == args[0]
    if where == PARTICIPATED_WHERE:
        return _contains(item, "opinions", json.loads(args[0]))
    raise AssertionError(f"假池未实现的 where: {where}")


class _FakeConn:
    def __init__(self, rows):
        self.rows = list(rows)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM consults":
            return len(self.rows)
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        assert s.startswith("SELECT data FROM consults WHERE"), s[:100]
        where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        rows = [r for r in self.rows if _match(r, where, a)]
        return [{"data": json.dumps(r, ensure_ascii=False)}
                for r in sorted(rows, key=lambda r: (r.get("created_at") or "", r.get("id") or ""),
                                reverse=True)]


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 域层 ----------

def test_inbox_open_and_target_dept(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in consult_mod.inbox_for("心内科")] == ["c2", "c1"]
    assert [c["id"] for c in consult_mod.inbox_for("骨科")] == ["c4", "c2"]


def test_inbox_empty_dept_returns_empty(monkeypatch):
    _use_pool(monkeypatch)
    assert consult_mod.inbox_for("") == []


def test_by_initiator_desc(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in consult_mod.by_initiator("doctor01")] == ["c4", "c3", "c1"]
    assert consult_mod.by_initiator("nobody") == []


def test_participated(monkeypatch):
    _use_pool(monkeypatch)
    assert [c["id"] for c in consult_mod.participated("doctor01")] == ["c2"]
    assert consult_mod.participated("doctor02") == []


def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert pg_store.consult_list(INITIATOR_WHERE, ("doctor01",)) is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert pg_store.consult_list(INITIATOR_WHERE, ("doctor01",)) is None


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert pg_store.consult_list(INITIATOR_WHERE, ("doctor01",)) is None


def test_domain_falls_back_to_json(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(consult_mod, "_load", lambda: [dict(r) for r in ROWS])
    assert [c["id"] for c in consult_mod.inbox_for("心内科")] == ["c2", "c1"]
    assert [c["id"] for c in consult_mod.by_initiator("doctor01")] == ["c4", "c3", "c1"]


# ---------- 路由层 ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app)


def test_route_mine_uses_pg(monkeypatch, tmp_path):
    """PG 命中时 /consults/mine 不再读全表（_load 设炸弹）。"""
    _use_pool(monkeypatch)

    def _boom():
        raise AssertionError("PG 可用时不应再读全量 consults")

    monkeypatch.setattr(consult_mod, "_load", _boom)
    c = _client(monkeypatch, tmp_path)
    tok = create_access_token("doctor01", "doctor")
    r = c.get("/api/v1/medical/consults/mine", headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 200, r.text
    d = r.json()
    assert [i["id"] for i in d["initiated"]] == ["c4", "c3", "c1"]
    assert [i["id"] for i in d["participated"]] == ["c2"]
