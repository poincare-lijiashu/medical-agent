"""第 81 批：处方列表分页/计数下推 PG（假池注入，绝不连真实 PG）。

背景：第 75 批的 `limit/offset/summary` 是"读全量再内存切片"——处方随使用年限无界增长，
角标轮询（每 60s 一次 summary）仍要全表解码。本批把 WHERE/ORDER/LIMIT/OFFSET/COUNT
全部下推到 PG，JSON/无池路径保留既有内存切片作兜底（行为完全不变）。

锁定语义：
- 排序新→旧（data->>'created_at' DESC, id DESC，与内存 `_load()[::-1]` 一致）；
- total = 过滤后总数（与切片无关）；summary 按 status 分组且 total=计数之和；
- 空表/无池/异常 → None（路由回落内存实现，旧调用方零影响）。
"""
import json

from fastapi.testclient import TestClient

from backend.core import pg_store
from backend.core import prescriptions as rx
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

ITEMS = [
    {"id": "rx-a", "doctor": "doctor01", "status": "pending_pharm",
     "created_at": "2026-01-01T00:00:00+00:00", "case_text": "A"},
    {"id": "rx-b", "doctor": "doctor01", "status": "approved",
     "created_at": "2026-02-01T00:00:00+00:00", "case_text": "B",
     "pharm_reviewer": "pharm01"},
    {"id": "rx-c", "doctor": "doctor01", "status": "rejected",
     "created_at": "2026-03-01T00:00:00+00:00", "case_text": "C",
     "pharm_reviewer": "pharm01"},
    {"id": "rx-d", "doctor": "doctor02", "status": "pending_pharm",
     "created_at": "2026-04-01T00:00:00+00:00", "case_text": "D"},
]


# ---------- 假 asyncpg 池：只实现本批下发的 SQL 形态（白名单式） ----------

class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, items):
        self._conn = _FakeConn(items)

    def acquire(self):
        return _FakeAcquire(self._conn)


def _match(item, cond, args):
    """按本批域层下发的固定 where 片段做过滤（非通用 SQL 解析）。"""
    if cond == "doctor = $1":
        return item.get("doctor") == args[0]
    if cond == "doctor = $1 AND status = $2":
        return item.get("doctor") == args[0] and item.get("status") == args[1]
    if cond == "status = 'pending_pharm'":
        return item.get("status") == "pending_pharm"
    if cond == "data->>'pharm_reviewer' = $1 AND status IN ('approved', 'rejected')":
        return (item.get("pharm_reviewer") == args[0]
                and item.get("status") in ("approved", "rejected"))
    raise AssertionError(f"假池未实现的 where: {cond}")


class _FakeConn:
    def __init__(self, items):
        self.items = list(items)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM prescriptions":
            return len(self.items)
        if s.startswith("SELECT count(*) FROM prescriptions WHERE "):
            cond = s.split(" WHERE ", 1)[1]
            return sum(1 for i in self.items if _match(i, cond, a))
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        cond = ""
        if " WHERE " in s:
            head, tail = s.split(" WHERE ", 1)
            cond = tail.split(" ORDER BY ", 1)[0].split(" GROUP BY ", 1)[0]
        if "GROUP BY status" in s:
            counts = {}
            for i in self.items:
                if not cond or _match(i, cond, a):
                    st = str(i.get("status") or "unknown")
                    counts[st] = counts.get(st, 0) + 1
            return [{"status": k, "n": v} for k, v in counts.items()]
        if s.startswith("SELECT data FROM prescriptions"):
            args = list(a)
            limit = offset = None
            if " LIMIT $" in s:  # 末两位参数是 limit/offset
                limit, offset = args[-2], args[-1]
                args = args[:-2]
            rows = [i for i in self.items if not cond or _match(i, cond, args)]
            rows.sort(key=lambda i: (i.get("created_at") or "", i["id"]), reverse=True)
            if limit is not None:
                rows = rows[offset:offset + limit]
            return [{"data": json.dumps(i, ensure_ascii=False)} for i in rows]
        raise AssertionError(f"假池未实现的 fetch: {s[:100]}")


def _use_pool(monkeypatch, items=ITEMS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(items))


# ---------- 域层：分页/计数/排序 ----------

def test_page_mine_paging_desc_and_total(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_mine("doctor01", 2, 0)
    assert [i["id"] for i in out["items"]] == ["rx-c", "rx-b"]  # 新→旧
    assert out["total"] == 3  # 过滤后总数，与切片无关
    out2 = rx.page_mine("doctor01", 2, 2)
    assert [i["id"] for i in out2["items"]] == ["rx-a"] and out2["total"] == 3


def test_page_mine_no_limit_returns_full(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_mine("doctor01", None, 0)
    assert [i["id"] for i in out["items"]] == ["rx-c", "rx-b", "rx-a"]
    assert out["total"] == 3


def test_page_mine_status_filter(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_mine("doctor01", None, 0, status="rejected")
    assert [i["id"] for i in out["items"]] == ["rx-c"] and out["total"] == 1


def test_page_mine_summary_counts(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_mine("doctor01", None, 0, summary=True)
    assert out["items"] == []  # summary 不取正文
    assert out["total"] == 3 == sum(out["counts"].values())
    assert out["counts"] == {"pending_pharm": 1, "approved": 1, "rejected": 1}


def test_page_pending(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_pending(1, 0)
    assert [i["id"] for i in out["items"]] == ["rx-d"]  # 新→旧，两条 pending 取最新
    assert out["total"] == 2


def test_page_reviewed_by(monkeypatch):
    _use_pool(monkeypatch)
    out = rx.page_reviewed_by("pharm01", 1, 1)
    assert [i["id"] for i in out["items"]] == ["rx-b"]
    assert out["total"] == 2


def test_empty_table_returns_none(monkeypatch):
    """空表 → None（与 _pg_load_prescriptions 同语义：JSON 兜底真源）。"""
    _use_pool(monkeypatch, [])
    assert rx.page_mine("doctor01", None, 0) is None
    assert rx.page_pending(None, 0) is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert rx.page_mine("doctor01", 1, 0) is None
    assert rx.page_pending(1, 0) is None
    assert rx.page_reviewed_by("pharm01", 1, 0) is None


def test_pg_failure_returns_none(monkeypatch):
    """查询异常必须降级 None（路由回落内存），绝不影响列表可用性。"""
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ITEMS))
    pg_store._pool._conn = _Boom(ITEMS)
    assert rx.page_mine("doctor01", 1, 0) is None


# ---------- 路由层：PG 可用时不再调用内存 list_* ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def test_route_mine_uses_pg_page(monkeypatch, tmp_path):
    """PG 路径命中时走 SQL 分页；内存 list_mine 被显式设为炸弹证明未被调用。"""
    _use_pool(monkeypatch)

    def _boom(*a, **k):
        raise AssertionError("PG 可用时不应再读全量 list_mine")

    monkeypatch.setattr(rx, "list_mine", _boom)
    c, log = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/prescriptions/mine", headers=_h("doctor01", "doctor"),
              params={"limit": 2, "offset": 0})
    assert r.status_code == 200, r.text
    d = r.json()
    assert [i["id"] for i in d["items"]] == ["rx-c", "rx-b"]
    assert d["total"] == 3 and d["me"] == "doctor01"
    ev = [e for e in log.entries if e["payload"].get("action") == "list"]
    assert ev and ev[-1]["payload"]["total"] == 3


def test_route_mine_summary_uses_pg_counts(monkeypatch, tmp_path):
    _use_pool(monkeypatch)
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/prescriptions/mine", headers=_h("doctor01", "doctor"),
              params={"summary": 1})
    d = r.json()
    assert d["items"] == [] and d["total"] == 3
    assert d["counts"] == {"pending_pharm": 1, "approved": 1, "rejected": 1}
    assert "case_text" not in r.text
