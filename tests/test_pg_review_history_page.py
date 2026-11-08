"""第 83 批：`GET /review/history` 分页/筛选/计数下推 PG（假池注入）。

语义红线（与内存实现逐条一致）：
- doctor/pharmacist：基础集合 = 本人参与（submitted_by=me OR reviewed_by=me）最新 200 条；
- qc/admin：基础集合 = 全表最新 50 条；
- **先截断再过滤**：hide_pending/hide_drug/q 只作用于基础集合，total=过滤后条数；
- 顺序新→旧（ts DESC, id DESC，与内存 `_load()[::-1]` 一致）；
- 空表/无池/异常 → None（路由回落内存实现，行为不变）。
"""
import json

from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

COLS = list(pg_store._REVIEW_COLS)


def _row(**kw):
    base = {k: None for k in COLS}
    base.update({"meta": None, "sources": None})
    base.update(kw)
    base["images"] = kw.get("images")
    return base


ROWS = [
    _row(id="h1", ts="2026-01-01T00:00:00+00:00", status="pending", agent="drug",
         question="华法林剂量", submitted_by="doctor01", reviewed_by=None, images=None),
    _row(id="h2", ts="2026-01-02T00:00:00+00:00", status="approved", agent="qc",
         question="胸部CT", submitted_by="doctor02", reviewed_by="AI·阈值自动(规则库v2)",
         images=json.dumps(["x"])),
    _row(id="h3", ts="2026-01-03T00:00:00+00:00", status="approved", agent="drug",
         question="药物相互作用", submitted_by="doctor01", reviewed_by="pharm01", images=None),
    _row(id="h4", ts="2026-01-04T00:00:00+00:00", status="approved", agent="qc",
         question="胸部CT复查", submitted_by="doctor01", reviewed_by="AI·阈值自动(规则库v2)",
         images=json.dumps(["y"])),
    _row(id="h5", ts="2026-01-05T00:00:00+00:00", status="pending", agent="qc",
         question="影像", submitted_by="doctor03", reviewed_by=None, images=None),
]


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


def _where_match(item, where, args):
    for part in where.split(" AND "):
        if part == "id = ANY($1)":
            if item.get("id") not in args[0]:
                return False
        elif part == "status <> 'pending'":
            if item.get("status") == "pending":
                return False
        elif part == "agent IS DISTINCT FROM 'drug'":
            if item.get("agent") == "drug":
                return False
        elif part.startswith("POSITION($"):
            idx = int(part.split("$")[1].split(" ")[0]) - 1
            kw = args[idx]
            blob = " ".join(str(item.get(k) or "") for k in
                            ("agent", "question", "submitted_by", "risk_reason")).lower()
            if kw not in blob:
                return False
        else:
            raise AssertionError(f"假池未实现的 where 片段: {part}")
    return True


def _desc(rows):
    return sorted(rows, key=lambda r: (r.get("ts") or "", r.get("id") or ""), reverse=True)


class _FakeConn:
    def __init__(self, rows):
        self.rows = list(rows)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM review_queue":
            return len(self.rows)
        if s.startswith("SELECT count(*) FROM review_queue WHERE "):
            where = s.split(" WHERE ", 1)[1]
            return sum(1 for r in self.rows if _where_match(r, where, a))
        raise AssertionError(f"假池未实现的 fetchval: {s[:100]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        if s.startswith("SELECT id FROM review_queue"):
            args = list(a)
            if "WHERE (submitted_by = $1 OR reviewed_by = $1)" in s:
                me = args[0]
                rows = [r for r in self.rows
                        if r.get("submitted_by") == me or r.get("reviewed_by") == me]
                cap = args[1]
            else:
                rows = list(self.rows)
                cap = args[0]
            return [{"id": r["id"]} for r in _desc(rows)[:cap]]
        assert s.startswith("SELECT id,ts"), s[:80]
        where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        args = list(a)
        limit = offset = None
        if " LIMIT $" in s:
            limit, offset = args[-2], args[-1]
            args = args[:-2]
        rows = [r for r in self.rows if _where_match(r, where, args)]
        rows = _desc(rows)
        if limit is not None:
            rows = rows[offset:offset + limit]
        return rows


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 域层 ----------

def test_involved_set_newest_first(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_history(involves="doctor01", base_limit=200)
    assert [i["id"] for i in out["items"]] == ["h4", "h3", "h1"]
    assert out["total"] == 3


def test_admin_set_all_newest_first(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_history(None, 50)
    assert [i["id"] for i in out["items"]] == ["h5", "h4", "h3", "h2", "h1"]
    assert out["total"] == 5


def test_truncate_before_filter(monkeypatch):
    """base_limit=2 时基础集合只有 h4,h3；h1（华法林）在截断外，过滤后看不到（旧语义）。"""
    _use_pool(monkeypatch)
    out = review.page_history("doctor01", 2, keyword="华法林")
    assert out["items"] == [] and out["total"] == 0
    # 放开截断后同条件能看到 h1（证明差异来自"先截断"，而非过滤失效）
    out2 = review.page_history("doctor01", 200, keyword="华法林")
    assert [i["id"] for i in out2["items"]] == ["h1"]


def test_hide_flags_and_keyword(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_history(None, 50, hide_pending=True, hide_drug=True)
    assert [i["id"] for i in out["items"]] == ["h4", "h2"] and out["total"] == 2
    out2 = review.page_history(None, 50, keyword="复查")
    assert [i["id"] for i in out2["items"]] == ["h4"] and out2["total"] == 1
    # 大小写不敏感（submitted_by 命中）
    out3 = review.page_history(None, 50, keyword="DOCTOR02")
    assert [i["id"] for i in out3["items"]] == ["h2"]


def test_paging_and_total(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_history(None, 50, limit=2, offset=1)
    assert [i["id"] for i in out["items"]] == ["h4", "h3"] and out["total"] == 5
    out0 = review.page_history(None, 50, limit=0)
    assert out0["items"] == [] and out0["total"] == 5


def test_no_involved_rows_returns_empty_page(monkeypatch):
    """表非空但本人无参与记录 → 空页 total=0（由 PG 回答，不回落内存）。"""
    _use_pool(monkeypatch)
    out = review.page_history("nobody", 200)
    assert out == {"items": [], "total": 0}


def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert review.page_history(None, 50) is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert review.page_history("doctor01", 200) is None


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert review.page_history(None, 50) is None


# ---------- 路由层 ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def test_route_doctor_uses_pg_and_image_rules(monkeypatch, tmp_path):
    """PG 路径命中；图片规则仍由 _history_view 在当页执行（人工签发剥图、自动签发保留）。"""
    _use_pool(monkeypatch)

    def _boom(*a, **k):
        raise AssertionError("PG 可用时不应再读全量 list_involved")

    monkeypatch.setattr(review, "list_involved", _boom)
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/history", headers=_h("doctor01", "doctor"))
    assert r.status_code == 200, r.text
    d = r.json()
    assert [i["id"] for i in d["items"]] == ["h4", "h3", "h1"] and d["total"] == 3
    by_id = {i["id"]: i for i in d["items"]}
    assert by_id["h4"]["images"] == ["y"]  # AI 自动签发 + 本人提交 → 保留
    assert "images" not in by_id["h3"]     # 人工签发 → 剥离


def test_route_admin_uses_pg_with_filters(monkeypatch, tmp_path):
    _use_pool(monkeypatch)

    def _boom(*a, **k):
        raise AssertionError("PG 可用时不应再读全量 list_all")

    monkeypatch.setattr(review, "list_all", _boom)
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/history", headers=_h("admin01", "admin"),
              params={"hide_pending": 1, "hide_drug": 1, "limit": 1})
    d = r.json()
    assert [i["id"] for i in d["items"]] == ["h4"] and d["total"] == 2
