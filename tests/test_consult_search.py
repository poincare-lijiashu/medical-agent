"""第 111 批：会诊关键字检索（收件箱 + 我的会诊，服务端过滤）。

语义：
- 匹配面：question / ai_analysis / initiator / target_depts / 已填意见正文；
- 大小写不敏感子串（与处方检索 `prescriptions.matches_query` 同口径，不引入第二套规则）；
- 收件箱：dept 隔离之后、分页之前过滤，total / pendingForMe 同步按过滤后集合计算；
- 角标轮询（summary=1）不传 q，口径保持"整箱待我填"；
- 审计只记"是否带关键字"（`q: bool`），**不记录关键字内容**（避免病例内容进审计）。

说明：本文件 monkeypatch `consults.inbox_for` 直接返回固定集合（绕过 dept 隔离），
dept 隔离语义由 tests/test_consult_inbox_paging.py 覆盖。
"""
import asyncio
import json

from fastapi.testclient import TestClient

from backend.core import consults, pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

ROWS = [
    {"id": "c1", "status": "open", "question": "反复胸痛三月，考虑冠心病", "initiator": "doctor01",
     "target_depts": ["心内科", "内分泌科"], "opinions": []},
    {"id": "c2", "status": "open", "question": "糖尿病足溃疡", "initiator": "doctor02",
     "target_depts": ["外科"],
     "opinions": [{"dept": "外科", "doctor": "waike01", "content": "建议清创引流"}]},
    {"id": "c3", "status": "open", "question": "头痛伴视物模糊",
     "ai_analysis": "神经内科会诊意见：需排除颅内占位",
     "initiator": "doctor01", "target_depts": ["心内科"], "opinions": []},
]


def _client(monkeypatch, tmp_path, dept="心内科"):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    # 第 116 批：q 由 consults 内部下推 PG；此处的桩模拟"PG 已过滤"的结果
    # 第 122 批：路由改调 inbox_page，返回 (页, total, pending)
    def _fake_page(d, q="", limit=None, offset=0):
        items = [dict(i) for i in ROWS if d and consults.matches_query(i, q)]
        total = len(items)
        pending = sum(1 for c in items
                      if not any((o or {}).get("dept") == d for o in (c.get("opinions") or [])))
        if limit is not None:
            items = items[offset:offset + limit]
        return items, total, pending

    monkeypatch.setattr(consults, "inbox_page", _fake_page)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": dept})
    return TestClient(app), log


def _h():
    return {"Authorization": "Bearer " + create_access_token("doctor01", "doctor")}


def _stub_mine_pages(monkeypatch):
    """桩：`/consults/mine` 的两侧分页域函数（第 121 批起路由改调 `*_page`）。
    这里复刻过滤 → total → 切片三步，语义与 PG 下推一致。"""
    def _fake(pool, u, q="", limit=None, offset=0):
        items = [dict(i) for i in pool if consults.matches_query(i, q)]
        total = len(items)
        if limit is not None:
            items = items[offset:offset + limit]
        return items, total

    monkeypatch.setattr(consults, "by_initiator_page",
                        lambda u, q="", limit=None, offset=0: _fake(ROWS, u, q, limit, offset))
    monkeypatch.setattr(consults, "participated_page",
                        lambda u, q="", limit=None, offset=0: _fake(
                            [ROWS[1]], u, q, limit, offset))


def test_matches_query_covers_all_fields():
    """病例 / AI 意见 / 发起人 / 受邀科室 / 已填意见 五个面都可命中；空关键字恒 True。"""
    assert consults.matches_query(ROWS[0], "胸痛")
    assert consults.matches_query(ROWS[0], "DOCTOR01")
    assert consults.matches_query(ROWS[0], "内分泌")
    assert consults.matches_query(ROWS[1], "清创")
    assert consults.matches_query(ROWS[2], "颅内占位")
    assert not consults.matches_query(ROWS[0], "糖尿病")
    assert consults.matches_query(ROWS[0], "   ")


def test_inbox_q_filters_total_and_pending(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "胸痛"}).json()
    assert [i["id"] for i in d["items"]] == ["c1"]
    assert d["total"] == 1 and d["pendingForMe"] == 1

    d2 = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "心内科"}).json()
    assert [i["id"] for i in d2["items"]] == ["c1", "c3"]  # 受邀科室命中两单
    assert d2["total"] == 2

    d3 = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "清创"}).json()
    assert [i["id"] for i in d3["items"]] == ["c2"]  # 命中已填意见正文


def test_inbox_q_then_pages(monkeypatch, tmp_path):
    """q 先过滤、再切片：offset 是过滤后的偏移（不能用全局序号）。"""
    c, _ = _client(monkeypatch, tmp_path)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h(),
              params={"q": "心内科", "limit": 1, "offset": 1}).json()
    assert [i["id"] for i in d["items"]] == ["c3"]
    assert d["total"] == 2


def test_inbox_q_no_match(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "不存在的病"}).json()
    assert d["items"] == [] and d["total"] == 0 and d["pendingForMe"] == 0


def test_inbox_summary_with_q_counts_only(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/consults/inbox", headers=_h(),
              params={"summary": 1, "q": "胸痛"})
    d = r.json()
    assert d["items"] == [] and d["total"] == 1 and d["pendingForMe"] == 1
    assert "opinions" not in r.text


def test_inbox_audit_records_flag_not_keyword(monkeypatch, tmp_path):
    """审计只记"是否带关键字"，关键字本身（病例内容）绝不进审计。"""
    c, log = _client(monkeypatch, tmp_path)
    c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "胸痛"})
    ev = [e for e in log.entries if e["payload"].get("action") == "list_inbox"]
    assert ev and ev[-1]["payload"]["q"] is True
    assert "胸痛" not in json.dumps(ev[-1]["payload"], ensure_ascii=False)


def test_mine_q_filters_both_sides(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    _stub_mine_pages(monkeypatch)
    d = c.get("/api/v1/medical/consults/mine", headers=_h(), params={"q": "胸痛"}).json()
    assert [i["id"] for i in d["initiated"]] == ["c1"]
    assert d["participated"] == []

    d2 = c.get("/api/v1/medical/consults/mine", headers=_h()).json()
    assert [i["id"] for i in d2["initiated"]] == ["c1", "c2", "c3"]
    assert [i["id"] for i in d2["participated"]] == ["c2"]
    ev = [e for e in log.entries if e["payload"].get("action") == "list_mine"]
    assert ev[-1]["payload"]["q"] is False


def test_q_over_length_rejected(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"q": "x" * 101})
    assert r.status_code == 422


# ---------- 第 116 批：会诊检索下推 PG ----------

def test_consult_search_condition_shape():
    """条件必须对五个检索面做 lower + LIKE ESCAPE，且参数走 $n（防注入）。"""
    cond = pg_store.consult_search_cond(2)
    assert "LIKE ('%' || $2 || '%') ESCAPE '!'" in cond
    for field in ("question", "ai_analysis", "initiator", "target_depts"):
        assert f"data->>'{field}'" in cond
    assert "jsonb_array_elements" in cond and "e->>'content'" in cond


def test_inbox_pushes_q_to_pg(monkeypatch):
    """有 q 时 SQL 带三个条件（open / 科室包含 / 关键字），参数依次为 dept、转义关键字。"""
    seen = {}

    def fake_list(where, params):
        seen["where"], seen["params"] = where, params
        return [{"id": "c1"}]

    monkeypatch.setattr(pg_store, "consult_list", fake_list)
    out = consults.inbox_for("心内科", "胸痛")
    assert out == [{"id": "c1"}]
    assert "status = 'open'" in seen["where"]
    assert "data->'target_depts' @> $1::jsonb" in seen["where"]
    assert "$2" in seen["where"]
    assert seen["params"] == ('["心内科"]', "胸痛")

    # 通配符必须转义（否则用户输入 % 会变成全表匹配）
    consults.inbox_for("心内科", "100%_x")
    assert seen["params"][1] == "100!%!_x"


def test_inbox_without_q_keeps_old_sql(monkeypatch):
    seen = {}

    def fake_list(where, params):
        seen["where"], seen["params"] = where, params
        return []

    monkeypatch.setattr(pg_store, "consult_list", fake_list)
    consults.inbox_for("心内科", "  ")
    assert seen["where"] == "status = 'open' AND data->'target_depts' @> $1::jsonb"
    assert seen["params"] == ('["心内科"]',)


def test_inbox_fallback_filters_in_memory(monkeypatch):
    """PG 不可用（None）时回落内存过滤，且与 matches_query 同口径。"""
    monkeypatch.setattr(pg_store, "consult_list", lambda *a, **k: None)
    monkeypatch.setattr(consults, "_load", lambda: [dict(i) for i in ROWS])
    out = consults.inbox_for("心内科", "胸痛")
    assert [i["id"] for i in out] == ["c1"]

    mine = consults.by_initiator("doctor01", "颅内占位")
    assert [i["id"] for i in mine] == ["c3"]
    part = consults.participated("waike01", "清创")
    assert [i["id"] for i in part] == ["c2"]
    assert consults.participated("waike01", "不存在") == []


# ---------- 第 119 批：我的会诊服务端分页 ----------

def test_mine_paging_slices_both_sides(monkeypatch, tmp_path):
    """/consults/mine：两侧共用 limit/offset，total 为过滤后全量；不传 limit 保持旧的全量行为。"""
    c, log = _client(monkeypatch, tmp_path)
    _stub_mine_pages(monkeypatch)

    d = c.get("/api/v1/medical/consults/mine", headers=_h(),
              params={"limit": 2, "offset": 0}).json()
    assert [i["id"] for i in d["initiated"]] == ["c1", "c2"]
    assert [i["id"] for i in d["participated"]] == ["c2"]
    assert d["initiatedTotal"] == 3 and d["participatedTotal"] == 1
    assert d["limit"] == 2 and d["offset"] == 0

    d2 = c.get("/api/v1/medical/consults/mine", headers=_h(),
               params={"limit": 2, "offset": 2}).json()
    assert [i["id"] for i in d2["initiated"]] == ["c3"]
    assert d2["participated"] == []  # 该侧不足一页
    assert d2["initiatedTotal"] == 3 and d2["participatedTotal"] == 1

    d3 = c.get("/api/v1/medical/consults/mine", headers=_h(),
               params={"limit": 2, "offset": 0, "q": "胸痛"}).json()
    assert [i["id"] for i in d3["initiated"]] == ["c1"] and d3["initiatedTotal"] == 1

    ev = [e for e in log.entries if e["payload"].get("action") == "list_mine"]
    assert ev[-1]["payload"]["initiatedTotal"] == 1
    assert ev[-1]["payload"]["limit"] == 2 and ev[-1]["payload"]["q"] is True

    d4 = c.get("/api/v1/medical/consults/mine", headers=_h()).json()
    assert len(d4["initiated"]) == 3 and d4["initiatedTotal"] == 3
    assert d4["limit"] is None


# ---------- 第 121 批：我的会诊分页下推 PG（LIMIT/OFFSET + 同口径 COUNT） ----------

class _CaptureConn:
    """记录 SQL 与参数的假连接：count(*) 走 fetchval，取数走 fetch（返回0行即可）。"""

    def __init__(self, total=2, table_rows=3):
        self.total = total
        self.table_rows = table_rows
        self.sqls: list[str] = []
        self.args: list[tuple] = []

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        self.sqls.append(s)
        if s == "SELECT count(*) FROM consults":
            return self.table_rows
        return self.total

    async def fetch(self, sql, *a):
        self.sqls.append(" ".join(sql.split()))
        self.args.append(a)
        return []


class _CaptureAcquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _CapturePool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _CaptureAcquire(self.conn)


def test_consult_page_sql_pushes_limit_and_counts(monkeypatch):
    """LIMIT/OFFSET 参数化下推；COUNT 用同一 where、同一组参数（total 与页内同口径）。"""
    conn = _CaptureConn(total=7)
    monkeypatch.setattr(pg_store, "_pool", _CapturePool(conn))
    out = asyncio.run(pg_store._pg_consult_page_impl("initiator = $1", ["doctor01"], 10, 20))
    assert out == {"items": [], "total": 7}
    assert "SELECT count(*) FROM consults WHERE initiator = $1" in conn.sqls
    sel = [s for s in conn.sqls if s.startswith("SELECT data FROM consults WHERE")]
    assert sel and sel[0].endswith("LIMIT $2 OFFSET $3"), sel
    assert conn.args[0] == ("doctor01", 10, 20)


def test_consult_page_without_limit_keeps_full_sql(monkeypatch):
    """limit=None 保持旧的整表语义（无 LIMIT 子句），但仍返回总数。"""
    conn = _CaptureConn(total=5)
    monkeypatch.setattr(pg_store, "_pool", _CapturePool(conn))
    out = asyncio.run(pg_store._pg_consult_page_impl("initiator = $1", ["doctor01"], None, 0))
    assert out == {"items": [], "total": 5}
    sel = [s for s in conn.sqls if s.startswith("SELECT data FROM consults WHERE")]
    assert sel and "LIMIT" not in sel[0]
    assert conn.args[0] == ("doctor01",)


def test_consult_page_empty_table_falls_back(monkeypatch):
    """空表 → None（JSON 才是真源），调用方回落内存而非返回空页。"""
    conn = _CaptureConn(table_rows=0)
    monkeypatch.setattr(pg_store, "_pool", _CapturePool(conn))
    assert asyncio.run(pg_store._pg_consult_page_impl("initiator = $1", ["x"], 10, 0)) is None


def test_by_initiator_page_uses_pg_page(monkeypatch):
    """域层把 limit/offset 与过滤条件原样交给 pg_store.consult_page，并回传其 total。"""
    seen = {}

    def _fake_page(where, params, limit=None, offset=0):
        seen.update(where=where, params=params, limit=limit, offset=offset)
        return {"items": [{"id": "c1"}], "total": 42}

    monkeypatch.setattr(pg_store, "consult_page", _fake_page)
    items, total = consults.by_initiator_page("doctor01", "胸痛", 10, 20)
    assert items == [{"id": "c1"}] and total == 42
    assert seen["where"] == "initiator = $1 AND " + pg_store.consult_search_cond(2)
    assert seen["params"] == ("doctor01", "胸痛")
    assert seen["limit"] == 10 and seen["offset"] == 20

    consults.participated_page("doctor01", "", 5, 0)
    assert seen["where"] == "data->'opinions' @> $1::jsonb"
    assert json.loads(seen["params"][0]) == [{"doctor": "doctor01"}]


def test_page_fallback_filters_then_slices_in_memory(monkeypatch):
    """PG 不可用 → 内存过滤 + 切片，total 仍是过滤后全量（不是页长度）。"""
    monkeypatch.setattr(pg_store, "consult_page", lambda *a, **k: None)
    monkeypatch.setattr(consults, "_load", lambda: [dict(i) for i in ROWS])
    # ROWS 按文件顺序（c1,c2,c3）；内存路径为 _load()[::-1] → 倒序
    items, total = consults.by_initiator_page("doctor01", "", 1, 1)
    assert total == 2 and [i["id"] for i in items] == ["c1"]

    items2, total2 = consults.by_initiator_page("doctor01", "胸痛")
    assert total2 == 1 and [i["id"] for i in items2] == ["c1"]  # limit=None 全量

    part, part_total = consults.participated_page("waike01", "清创", 5, 0)
    assert part_total == 1 and [i["id"] for i in part] == ["c2"]
    assert consults.participated_page("waike01", "不存在")[1] == 0


# ---------- 第 122 批：收件箱 total / pendingForMe / 切片下推 PG ----------

class _InboxConn:
    """收件箱分页的三次查询（总数 / 待填数 / 取数）分别返回可区分结果。"""

    def __init__(self, total=3, pending=1, table_rows=4):
        self.total = total
        self.pending = pending
        self.table_rows = table_rows
        self.sqls: list[str] = []
        self.args: list[tuple] = []

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        self.sqls.append(s)
        self.args.append(a)
        if s == "SELECT count(*) FROM consults":
            return self.table_rows
        if "NOT EXISTS" in s:
            return self.pending
        return self.total

    async def fetch(self, sql, *a):
        self.sqls.append(" ".join(sql.split()))
        self.args.append(a)
        return []


def _inbox_pool(monkeypatch, conn):
    monkeypatch.setattr(pg_store, "_pool", _CapturePool(conn))
    return conn


def test_inbox_page_sql_counts_pending_with_not_exists(monkeypatch):
    """无关键字：pending 的 dept 用 $2（独立语句），取数按本语句编号 LIMIT $2/OFFSET $3。"""
    conn = _inbox_pool(monkeypatch, _InboxConn(total=3, pending=1))
    out = asyncio.run(pg_store._pg_consult_inbox_page_impl(
        "status = 'open' AND data->'target_depts' @> $1::jsonb", ["[\"心内科\"]"], 10, 0,
        "心内科"))
    assert out == {"items": [], "total": 3, "pending": 1}
    pend = [s for s in conn.sqls if "NOT EXISTS" in s]
    assert pend and "e->>'dept' = $2" in pend[0]
    sel = [s for s in conn.sqls if s.startswith("SELECT data FROM consults WHERE")]
    assert sel and sel[0].endswith("LIMIT $2 OFFSET $3"), sel
    assert conn.args[-1] == ("[\"心内科\"]", 10, 0)
    assert ("[\"心内科\"]", "心内科") in conn.args


def test_inbox_page_sql_keyword_shifts_placeholders(monkeypatch):
    """带关键字：where 2 个参数 → pending 用 $3，取数 LIMIT $3/OFFSET $4（各自独立编号）。"""
    conn = _inbox_pool(monkeypatch, _InboxConn())
    asyncio.run(pg_store._pg_consult_inbox_page_impl(
        "status = 'open' AND data->'target_depts' @> $1::jsonb AND x LIKE $2", 
        ["[\"心内科\"]", "胸痛"], 5, 10, "心内科"))
    pend = [s for s in conn.sqls if "NOT EXISTS" in s]
    assert pend and "e->>'dept' = $3" in pend[0]
    sel = [s for s in conn.sqls if s.startswith("SELECT data FROM consults WHERE")]
    assert sel and sel[0].endswith("LIMIT $3 OFFSET $4"), sel
    assert conn.args[-1] == ("[\"心内科\"]", "胸痛", 5, 10)


def test_inbox_page_limit_zero_returns_no_rows_but_counts(monkeypatch):
    """summary 场景：limit=0 只算两个计数（不搬运正文）。"""
    conn = _inbox_pool(monkeypatch, _InboxConn(total=9, pending=4))
    out = asyncio.run(pg_store._pg_consult_inbox_page_impl(
        "status = 'open' AND data->'target_depts' @> $1::jsonb", ["[\"外科\"]"], 0, 0, "外科"))
    assert out == {"items": [], "total": 9, "pending": 4}
    sel = [s for s in conn.sqls if s.startswith("SELECT data FROM consults WHERE")]
    assert sel and sel[0].endswith("LIMIT $2 OFFSET $3")


def test_inbox_page_empty_table_falls_back(monkeypatch):
    conn = _inbox_pool(monkeypatch, _InboxConn(table_rows=0))
    assert asyncio.run(pg_store._pg_consult_inbox_page_impl(
        "status = 'open' AND data->'target_depts' @> $1::jsonb", ["[\"外科\"]"], 10, 0,
        "外科")) is None


def test_inbox_page_memory_fallback_matches_pg_semantics(monkeypatch):
    """PG 不可用：total 是过滤后全量、pending 也按全量算、切片只影响 items。"""
    monkeypatch.setattr(pg_store, "consult_inbox_page", lambda *a, **k: None)
    monkeypatch.setattr(consults, "_load", lambda: [dict(i) for i in ROWS])
    items, total, pending = consults.inbox_page("心内科", "", 1, 0)
    assert [i["id"] for i in items] == ["c3"] and total == 2 and pending == 2
    items2, total2, pending2 = consults.inbox_page("外科", "", None, 0)
    assert [i["id"] for i in items2] == ["c2"] and total2 == 1 and pending2 == 0
    assert consults.inbox_page("", "", None, 0) == ([], 0, 0)


def test_inbox_summary_passes_limit_zero_to_domain(monkeypatch, tmp_path):
    """summary=1：路由以 limit=0 调用域层（计数照常，行不搬运）。"""
    c, _ = _client(monkeypatch, tmp_path)
    seen = {}

    def _fake(d, q="", limit=None, offset=0):
        seen.update(limit=limit, offset=offset)
        return [], 3, 2

    monkeypatch.setattr(consults, "inbox_page", _fake)
    d = c.get("/api/v1/medical/consults/inbox", headers=_h(), params={"summary": 1}).json()
    assert seen["limit"] == 0
    assert d["items"] == [] and d["total"] == 3 and d["pendingForMe"] == 2
