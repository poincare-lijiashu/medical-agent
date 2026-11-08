"""第 82 批：`GET /review/pending` 分页/角色收窄/关键字/计数下推 PG（假池注入）。

背景：该端点是唯一仍"读全量 review_queue 再过滤切片"的高频接口——侧栏角标每 60s 调
`limit=0` 只为拿一个数字，实测 600+ 条队列要整表解码。本批把 status/agent 收窄/关键字/
分页/计数全部下推 PG，内存 `pending()` 仅作 JSON 兜底（无池/离线/异常/空表）。

锁定语义：
- 顺序 = ts, id 升序（与 `_pg_load_queue`/JSON 追加顺序一致，保持队列"先进先出"）；
- qc 角色剔除 `agent='drug'`（NULL agent 保留，与内存 `i.get("agent") != "drug"` 一致）；
- 关键字与 `_match_query` 同构：四字段空格拼接后大小写不敏感子串匹配；
- total = 过滤后总数（与切片无关）；空表 → None 回落内存实现。
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
    _row(id="q1", ts="2026-01-01T00:00:00+00:00", status="pending", agent="drug",
         question="华法林剂量", submitted_by="doctor01", risk_reason="高危", images=None),
    _row(id="q2", ts="2026-01-02T00:00:00+00:00", status="pending", agent="imaging",
         question="胸部CT解读", submitted_by="doctor02", risk_reason="影像异常",
         images=json.dumps(["img-a", "img-b"])),
    _row(id="q3", ts="2026-01-03T00:00:00+00:00", status="approved", agent="qc",
         question="已归档", submitted_by="doctor01", risk_reason="", images=None),
    _row(id="q4", ts="2026-01-04T00:00:00+00:00", status="pending", agent="literature",
         question="他汀相互作用", submitted_by="doctor01", risk_reason="证据不足", images=None),
]


# ---------- 假 asyncpg 池：只实现本批下发的 SQL 形态 ----------

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
        if part == "status = 'pending'":
            if item.get("status") != "pending":
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
        assert s.startswith("SELECT id,ts"), s[:80]
        where = ""
        if " WHERE " in s:
            where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        args = list(a)
        limit = offset = None
        if " LIMIT $" in s:  # 末两位参数是 limit/offset
            limit, offset = args[-2], args[-1]
            args = args[:-2]
        rows = [r for r in self.rows if not where or _where_match(r, where, args)]
        rows.sort(key=lambda r: (r.get("ts") or "", r.get("id") or ""))
        if limit is not None:
            rows = rows[offset:offset + limit]
        return rows


def _use_pool(monkeypatch, rows=ROWS):
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))


# ---------- 域层：分页/收窄/关键字 ----------

def test_page_default_all_pending_asc(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_pending()
    assert [i["id"] for i in out["items"]] == ["q1", "q2", "q4"]  # ts 升序，不含 approved
    assert out["total"] == 3


def test_page_slice_and_total(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_pending(limit=1, offset=1)
    assert [i["id"] for i in out["items"]] == ["q2"] and out["total"] == 3


def test_page_qc_excludes_drug(monkeypatch):
    _use_pool(monkeypatch)
    out = review.page_pending(exclude_drug=True)
    assert [i["id"] for i in out["items"]] == ["q2", "q4"] and out["total"] == 2


def test_page_keyword_matches_four_fields(monkeypatch):
    _use_pool(monkeypatch)
    assert [i["id"] for i in review.page_pending(keyword="他汀")["items"]] == ["q4"]
    # 大小写不敏感（submitted_by 命中），且与内存 _match_query 同字段集
    out = review.page_pending(keyword="DOCTOR01")
    assert [i["id"] for i in out["items"]] == ["q1", "q4"] and out["total"] == 2


def test_page_decodes_images_jsonb(monkeypatch):
    _use_pool(monkeypatch)
    item = review.page_pending(limit=1, offset=1)["items"][0]
    assert item["images"] == ["img-a", "img-b"]


def test_empty_table_returns_none(monkeypatch):
    _use_pool(monkeypatch, [])
    assert review.page_pending() is None


def test_no_pool_returns_none(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert review.page_pending() is None


def test_pg_failure_returns_none(monkeypatch):
    class _Boom(_FakeConn):
        async def fetchval(self, sql, *a):
            raise RuntimeError("boom")

    monkeypatch.setattr(pg_store, "_pool", _FakePool(ROWS))
    pg_store._pool.conn = _Boom(ROWS)
    assert review.page_pending() is None


# ---------- 路由层：PG 可用时不再调用内存 pending ----------

def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def test_route_uses_pg_page(monkeypatch, tmp_path):
    """PG 路径命中时走 SQL 分页；内存 pending() 设为炸弹证明未被调用。"""
    _use_pool(monkeypatch)

    def _boom(*a, **k):
        raise AssertionError("PG 可用时不应再读全量 pending()")

    monkeypatch.setattr(review, "pending", _boom)
    c, log = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/pending", headers=_h("qc01", "qc"),
              params={"limit": 1})
    assert r.status_code == 200, r.text
    d = r.json()
    assert [i["id"] for i in d["pending"]] == ["q2"]  # qc 剔除 drug(q1)，首条是 q2
    assert d["total"] == 2  # qc 视角：q2、q4
    ev = [e for e in log.entries if e["payload"].get("action") == "list_pending"]
    assert ev and ev[-1]["payload"]["total"] == 2


def test_route_compact_uses_pg_items(monkeypatch, tmp_path):
    """compact 瘦身依赖 images 解码结果（imageCount），验证 PG 行解码正确。"""
    _use_pool(monkeypatch)
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/pending", headers=_h("admin01", "admin"),
              params={"compact": 1, "limit": 1, "offset": 1})
    item = r.json()["pending"][0]
    assert item["id"] == "q2"
    assert "answer" not in item and "images" not in item
    assert item["imageCount"] == 2 and "answerPreview" in item
