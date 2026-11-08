"""第 107 批：审计流过滤（actor / event_type / payload.action）。
第 108 批：过滤后总数 + 专用分页端点 /admin/audit + 读路径计量。

语义：
- 过滤在 SQL 侧完成，`audit_offset` 是**过滤后**的偏移；
- 三个参数均为空时 SQL 与旧版一致（无 WHERE）；
- PG 不可用时回落 JSONL：先过滤、再倒序、最后切片（与 PG 同口径）；
- 第 108 批的 total 是「过滤后」总条数（PG 侧 count(*)；回落时 len(matched)），
  前端据此显示「共 N 条」并精确禁用下一页。
"""
import asyncio

from fastapi.testclient import TestClient

from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.main import app

ENTRIES = [
    # 本地时区（+08:00）中午：日期区间按本地日历日解释，测试不依赖运行机器时区
    {"ts": "2026-01-01T12:00:00+08:00", "event_type": "review", "actor": "admin01",
     "payload": {"action": "resolved_approved"}},
    {"ts": "2026-01-02T12:00:00+08:00", "event_type": "prescription", "actor": "pharm01",
     "payload": {"action": "approved"}},
    {"ts": "2026-01-03T12:00:00+08:00", "event_type": "review", "actor": "qc01",
     "payload": {"action": "resolved_rejected"}},
    {"ts": "2026-01-04T12:00:00+08:00", "event_type": "review", "actor": "admin01",
     "payload": {"action": "reopened"}},
]


class _Audit:
    def __init__(self):
        self.entries = [dict(e) for e in ENTRIES]

    def write(self, *a, **k):
        pass

    def recent(self, n=8, offset=0):
        return self.entries[::-1][offset:offset + n]


class _Acq:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _Conn:
    def __init__(self, count_value=0):
        self.sql = ""
        self.args = ()
        self.count_value = count_value

    async def fetch(self, sql, *a):
        self.sql, self.args = " ".join(sql.split()), a
        return []

    async def fetchval(self, sql, *a):
        self.sql, self.args = " ".join(sql.split()), a
        return self.count_value


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acq(self.conn)


def _client(monkeypatch):
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", _Audit)
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return c, {"Authorization": "Bearer " + r.json()["access_token"]}


def test_pg_sql_shape_with_filters(monkeypatch):
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    asyncio.run(pg_store._audit_recent_pg_impl(50, 10, "admin01", "review", "reopened"))
    assert ("WHERE actor = $1 AND event_type = $2 AND payload->>'action' = $3" in conn.sql)
    assert conn.args == ("admin01", "review", "reopened", 50, 10)


def test_pg_sql_unchanged_without_filters(monkeypatch):
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    asyncio.run(pg_store._audit_recent_pg_impl(50, 0))
    assert "WHERE" not in conn.sql
    assert conn.args == (50, 0)


def test_pg_count_sql_shape_with_filters(monkeypatch):
    """第 108 批：总数复用同一 WHERE 片段（条件列名固定、值走 $n）。"""
    conn = _Conn(count_value=2149)
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    assert asyncio.run(pg_store._audit_count_pg_impl("admin01", "review", "")) == 2149
    assert conn.sql == "SELECT count(*) FROM audit_log WHERE actor = $1 AND event_type = $2"
    assert conn.args == ("admin01", "review")

    asyncio.run(pg_store._audit_count_pg_impl())
    assert conn.sql == "SELECT count(*) FROM audit_log"
    assert conn.args == ()


def test_route_passes_filters_to_pg(monkeypatch):
    seen = {}

    async def _fake(limit, offset=0, actor="", event_type="", action="", start=None, end=None):
        seen.update(limit=limit, offset=offset, actor=actor,
                    event_type=event_type, action=action)
        return []

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)

    async def _count(actor="", event_type="", action="", start=None, end=None):
        return 0

    monkeypatch.setattr(pg_store, "audit_count_pg", _count)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/data?audit_offset=50&audit_actor=%20admin01%20"
              "&audit_event=review&audit_action=reopened", headers=h)
    assert r.status_code == 200, r.text
    assert seen == {"limit": 50, "offset": 50, "actor": "admin01",
                    "event_type": "review", "action": "reopened"}


def test_jsonl_fallback_filters_then_pages(monkeypatch):
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _none)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/data?audit_actor=admin01", headers=h).json()
    acts = [e["action"] for e in d["audit_recent"]]
    assert acts == ["reopened", "resolved_approved"]  # 仅 admin01，且新→旧
    assert d["audit_total"] == 2  # 第 108 批：过滤后总数（JSONL 侧算得）

    d2 = c.get("/api/v1/medical/admin/data?audit_event=review&audit_offset=1", headers=h).json()
    assert [e["action"] for e in d2["audit_recent"]] == ["resolved_rejected", "resolved_approved"]
    assert d2["audit_total"] == 3


# ---------- 第 108 批：专用分页端点 /admin/audit ----------

def test_admin_audit_route_pages_with_jsonl_fallback(monkeypatch):
    """PG 不可用：/admin/audit 与 /admin/data 同口径（过滤→倒序→切片），total=过滤后条数。"""
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _none)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/audit?audit_event=review", headers=h)
    assert r.status_code == 200, r.text
    d = r.json()
    assert [e["action"] for e in d["items"]] == ["reopened", "resolved_rejected",
                                                 "resolved_approved"]
    assert d["total"] == 3
    assert d["offset"] == 0 and d["limit"] == 50
    assert d["filters"] == {"actor": "", "event": "review", "action": "",
                            "from": "", "to": ""}

    d2 = c.get("/api/v1/medical/admin/audit?audit_event=review&audit_offset=2", headers=h).json()
    assert [e["action"] for e in d2["items"]] == ["resolved_approved"]
    assert d2["total"] == 3  # total 与偏移无关

    d3 = c.get("/api/v1/medical/admin/audit?audit_offset=-5", headers=h).json()
    assert d3["offset"] == 0  # 负数按 0 处理，绝不 500


def test_admin_audit_route_uses_pg_total(monkeypatch):
    """PG 可用：total 取 count(*)（不是本页长度），空过滤透传空串。"""
    rows = [{"ts": "2026-01-04T00:00:00+00:00", "event_type": "review",
             "actor": "admin01", "payload": {"action": "reopened"}}]

    async def _fake(limit, offset=0, actor="", event_type="", action="", start=None, end=None):
        return rows

    async def _count(actor="", event_type="", action="", start=None, end=None):
        return 2149

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    monkeypatch.setattr(pg_store, "audit_count_pg", _count)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit?audit_actor=admin01", headers=h).json()
    assert len(d["items"]) == 1 and d["total"] == 2149


# ---------- 第 109 批：时间区间（半开窗）+ 全量导出 ----------

def test_pg_time_window_sql_is_half_open(monkeypatch):
    """时间区间以半开 [start, end) 下发；截止日由调用方换算为次日 00:00。"""
    from datetime import datetime, timezone
    conn = _Conn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    s = datetime(2026, 11, 1, tzinfo=timezone.utc)
    e = datetime(2026, 11, 8, tzinfo=timezone.utc)
    asyncio.run(pg_store._audit_recent_pg_impl(50, 0, "admin01", "", "", s, e))
    assert ("WHERE actor = $1 AND ts >= $2 AND ts < $3" in conn.sql)
    assert conn.args[:3] == ("admin01", s, e)
    assert conn.args[3:] == (50, 0)

    asyncio.run(pg_store._audit_count_pg_impl("", "", "", s, e))
    assert conn.sql == "SELECT count(*) FROM audit_log WHERE ts >= $1 AND ts < $2"
    assert conn.args == (s, e)


def test_jsonl_fallback_time_window(monkeypatch):
    """JSONL 回落同样按半开窗过滤（ENTRIES 为 01-01 ~ 01-04，含首尾日语义）。"""
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _none)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit?audit_from=2026-01-02&audit_to=2026-01-03",
              headers=h).json()
    assert [e["action"] for e in d["items"]] == ["resolved_rejected", "approved"]
    assert d["total"] == 2
    assert d["filters"]["from"] == "2026-01-02" and d["filters"]["to"] == "2026-01-03"

    # 只给起始日：>= 当日 00:00 → 01-03、01-04 两天
    d2 = c.get("/api/v1/medical/admin/audit?audit_from=2026-01-03", headers=h).json()
    assert [e["action"] for e in d2["items"]] == ["reopened", "resolved_rejected"]
    # 只给截止日：< 次日 00:00 → 01-01 当天也包含（按本地时区解释）
    d3 = c.get("/api/v1/medical/admin/audit?audit_to=2026-01-01", headers=h).json()
    assert len(d3["items"]) == 1 and d3["items"][0]["action"] == "resolved_approved"


def test_audit_window_rejects_bad_input(monkeypatch):
    """非法日期/起止倒置 → 400（不静默忽略，避免导出范围与预期不符）。"""
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/audit?audit_from=2026/01/01", headers=h)
    assert r.status_code == 400 and "YYYY-MM-DD" in r.json()["detail"]
    r2 = c.get("/api/v1/medical/admin/audit?audit_from=2026-02-01&audit_to=2026-01-01",
               headers=h)
    assert r2.status_code == 400 and "不能晚于" in r2.json()["detail"]


def test_csv_cell_escapes_formula_and_quotes():
    """CSV 单元格：引号转义 + Excel 公式注入防护（= + - @ 前置单引号）。"""
    from backend.api.v1.medical.audit import _csv_cell
    assert _csv_cell('a"b') == '"a""b"'
    assert _csv_cell("=1+1") == "\"'=1+1\""
    assert _csv_cell("+SUM(A1)") == "\"'+SUM(A1)\""
    assert _csv_cell("-2") == "\"'-2\""
    assert _csv_cell("@x") == "\"'@x\""
    assert _csv_cell(None) == '""'
    assert _csv_cell("张三") == '"张三"'


def test_export_csv_shape_and_headers(monkeypatch):
    """全量导出：BOM + 表头 + 行数头；payload 原样（JSON）随行下发。"""
    rows = [
        {"ts": "2026-01-04T00:00:00+00:00", "event_type": "admin",
         "actor": "=cmd|' /C calc'!A0", "payload": {"action": "user_created", "n": 2}},
        {"ts": "2026-01-03T00:00:00+00:00", "event_type": "review",
         "actor": "qc01", "payload": {"action": "resolved", "note": 'he said "no"'}},
    ]

    async def _fake(limit, offset=0, actor="", event_type="", action="", start=None, end=None):
        assert limit == 10001  # 多取 1 行用于判断是否截断
        return rows

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/audit/export?audit_actor=admin01", headers=h)
    assert r.status_code == 200, r.text
    assert r.headers["content-type"].startswith("text/csv")
    assert "attachment" in r.headers["content-disposition"]
    assert r.headers["x-export-rows"] == "2"
    assert r.headers["x-export-truncated"] == "0"
    body = r.content.decode("utf-8-sig")
    lines = body.strip().split("\r\n")
    assert lines[0] == '"时间","事件","动作","操作者","payload"'
    assert "\"'=cmd|' /C calc'!A0\"" in lines[1]  # 公式注入被前置单引号
    assert "he said" in lines[2]                  # payload JSON 随行
    assert lines[2].startswith('"2026-01-03T00:00:00+00:00","review","resolved","qc01"')


def test_export_truncates_at_limit(monkeypatch):
    """超过上限：只返回 10000 行并以 X-Export-Truncated: 1 显式告知。"""
    rows = [{"ts": "2026-01-04T00:00:00+00:00", "event_type": "review",
             "actor": "a", "payload": {}}] * 10001

    async def _fake(limit, offset=0, actor="", event_type="", action="", start=None, end=None):
        return rows

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    c, h = _client(monkeypatch)
    r = c.get("/api/v1/medical/admin/audit/export", headers=h)
    assert r.status_code == 200
    assert r.headers["x-export-rows"] == "10000"
    assert r.headers["x-export-truncated"] == "1"


def test_export_writes_audit_trail(monkeypatch):
    """导出是合规敏感操作：必须留痕（action=audit_export，含过滤条件与行数）。"""
    writes = []

    class _SpyAudit:
        def __init__(self):
            self.entries = []

        def write(self, *a, **k):
            writes.append(k)

        def recent(self, n=8, offset=0):
            return []

    async def _fake(limit, offset=0, actor="", event_type="", action="", start=None, end=None):
        return []

    async def _mirror(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_recent_pg", _fake)
    monkeypatch.setattr(pg_store, "mirror_audit", _mirror)
    c, h = _client(monkeypatch)
    # 注意：_client 会 patch get_audit_logger，spy 必须在它之后安装
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", _SpyAudit)
    r = c.get("/api/v1/medical/admin/audit/export?audit_actor=doctor01&audit_from=2026-01-01",
              headers=h)
    assert r.status_code == 200
    assert writes, "导出必须写审计"
    k = writes[-1]
    assert k["event_type"] == "admin" and k["action"] == "audit_export"
    assert k["payload"]["filters"]["actor"] == "doctor01"
    assert k["payload"]["filters"]["from"] == "2026-01-01"
    assert k["payload"]["rows"] == 0 and k["payload"]["truncated"] is False


def test_admin_audit_route_requires_admin(monkeypatch):
    """只读端点同样仅 admin（qc/doctor 一律 403）。"""
    seed_default_users()
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    r2 = c.get("/api/v1/medical/admin/audit",
               headers={"Authorization": "Bearer " + r.json()["access_token"]})
    assert r2.status_code == 403


# ---------- 第 110 批：业务时区 + 过滤框候选值（facets） ----------

def test_audit_window_respects_business_timezone(monkeypatch):
    """日期窗口按 settings.audit_timezone 解释：UTC→+0，Asia/Shanghai→+8，非法→回落本地不抛。"""
    from datetime import timedelta

    from backend.api.v1.medical.audit import _audit_tz, _audit_window
    from backend.config import settings as cfg

    monkeypatch.setattr(cfg, "audit_timezone", "UTC")
    s, e = _audit_window("2026-10-30", "2026-10-30")
    assert s.utcoffset() == timedelta(0) and e.utcoffset() == timedelta(0)
    assert (s.date().isoformat(), e.date().isoformat()) == ("2026-10-30", "2026-10-31")

    monkeypatch.setattr(cfg, "audit_timezone", "Asia/Shanghai")
    s2, _ = _audit_window("2026-10-30", "")
    assert s2.utcoffset() == timedelta(hours=8)
    assert _audit_tz()[1] == "Asia/Shanghai"

    monkeypatch.setattr(cfg, "audit_timezone", "Not/AZone")
    tz3, name3 = _audit_tz()
    assert tz3 is not None and "invalid" in name3  # 配置错误不 500，回落本地并标注


class _FacetConn:
    def __init__(self):
        self.calls = []

    async def fetch(self, sql, *a):
        self.calls.append((" ".join(sql.split()), a))
        return [{"v": "admin01", "n": 3}]


def test_pg_facets_sql_shape(monkeypatch):
    """facets 三条 GROUP BY 复用时间窗；空值/空串排除；前 100、次数倒序。"""
    from datetime import datetime, timezone
    conn = _FacetConn()
    monkeypatch.setattr(pg_store, "_pool", _Pool(conn))
    s = datetime(2026, 10, 1, tzinfo=timezone.utc)
    e = datetime(2026, 11, 1, tzinfo=timezone.utc)
    out = asyncio.run(pg_store._audit_facets_pg_impl(s, e))
    assert out == {"actors": [{"v": "admin01", "n": 3}],
                   "events": [{"v": "admin01", "n": 3}],
                   "actions": [{"v": "admin01", "n": 3}]}
    assert len(conn.calls) == 3
    for (sql, args), expr in zip(conn.calls, ("actor", "event_type", "payload->>'action'")):
        assert sql.startswith(f"SELECT {expr} AS v, count(*) AS n FROM audit_log"
                              f" WHERE ts >= $1 AND ts < $2 AND {expr} IS NOT NULL"
                              f" AND {expr} <> ''")
        assert "GROUP BY 1 ORDER BY 2 DESC, 1 ASC LIMIT 100" in sql
        assert args == (s, e)


def test_admin_audit_facets_route_jsonl_fallback(monkeypatch):
    """PG 不可用：facets 从 JSONL 聚合（次数倒序、同次数按字典序），并回显时区。"""
    async def _none(*a, **k):
        return None

    monkeypatch.setattr(pg_store, "audit_facets_pg", _none)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit/facets", headers=h).json()
    assert [x["v"] for x in d["actors"]] == ["admin01", "pharm01", "qc01"]
    assert [x["n"] for x in d["actors"]] == [2, 1, 1]
    assert [x["v"] for x in d["events"]] == ["review", "prescription"]
    assert [x["n"] for x in d["events"]] == [3, 1]
    assert [x["v"] for x in d["actions"]] == ["approved", "reopened",
                                              "resolved_approved", "resolved_rejected"]
    assert d["limit"] == 100 and d["tz"]


def test_admin_audit_facets_route_uses_pg(monkeypatch):
    async def _fake(start=None, end=None):
        return {"actors": [{"v": "a", "n": 9}], "events": [], "actions": []}

    monkeypatch.setattr(pg_store, "audit_facets_pg", _fake)
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit/facets", headers=h).json()
    assert d["actors"] == [{"v": "a", "n": 9}] and d["events"] == [] and d["actions"] == []


def test_admin_audit_response_echoes_timezone(monkeypatch):
    """分页响应回显业务时区名（前端「时间（Asia/Shanghai）」提示的数据源）。"""
    from backend.config import settings as cfg
    monkeypatch.setattr(cfg, "audit_timezone", "UTC")
    c, h = _client(monkeypatch)
    d = c.get("/api/v1/medical/admin/audit", headers=h).json()
    assert d["tz"] == "UTC"

