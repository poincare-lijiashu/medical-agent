"""第 97 批：关键字子串检索改 LIKE + pg_trgm（语义等价 + 通配符按字面量）。

背景：待核/历史的关键字此前用 `POSITION($n IN lower(拼接串))`——语义正确但**不可索引**。
本批改为 `LIKE ('%' || $n || '%') ESCAPE '!'`，并建 pg_trgm 表达式 GIN 索引；用户输入的
`%` `_` `!` 必须仍是字面量（不能变成通配符）。

锁定语义：
- 与内存 `_match_query` 完全一致：四字段空格拼接、lower、子串包含（大小写不敏感）；
- 转义规则：`!`→`!!`、`%`→`!%`、`_`→`!_`；
- 索引/DOS 防护：关键词原样走参数（不拼 SQL），无注入面。
"""
import asyncio

from backend.core import pg_store

COLS = list(pg_store._REVIEW_COLS)


def _row(**kw):
    base = {k: None for k in COLS}
    base.update({"meta": None, "sources": None, "images": None, "confidence": 0.9})
    base.update(kw)
    return base


ROWS = [
    _row(id="q1", ts="2026-01-01T00:00:00+00:00", status="pending", agent="drug",
         question="缓解率 50% 有效", submitted_by="doctor01", risk_reason="!"),
    _row(id="q2", ts="2026-01-02T00:00:00+00:00", status="pending", agent="imaging",
         question="胸部CT解读", submitted_by="doctor02", risk_reason="影像异常"),
    _row(id="q3", ts="2026-01-03T00:00:00+00:00", status="pending", agent="literature",
         question="他汀相互作用", submitted_by="doctor01", risk_reason="证据不足"),
]


def _unescape(raw):
    buf, i = [], 0
    while i < len(raw):
        if raw[i] == "!" and i + 1 < len(raw):
            buf.append(raw[i + 1])
            i += 2
        else:
            buf.append(raw[i])
            i += 1
    return "".join(buf)


def _where_match(item, where, args):
    for part in where.split(" AND "):
        if part == "status = 'pending'":
            if item.get("status") != "pending":
                return False
        elif part == "agent IS DISTINCT FROM 'drug'":
            if item.get("agent") == "drug":
                return False
        elif part.startswith("lower(coalesce(agent"):
            idx = int(part.split("$")[1].split(" ")[0]) - 1
            kw = _unescape(args[idx])
            blob = " ".join(str(item.get(k) or "") for k in
                            ("agent", "question", "submitted_by", "risk_reason")).lower()
            if kw not in blob:
                return False
        else:
            raise AssertionError(f"未实现的 where 片段: {part}")
    return True


class _Acquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class _Conn:
    def __init__(self, rows):
        self.rows = list(rows)

    async def fetchval(self, sql, *a):
        s = " ".join(sql.split())
        if s == "SELECT count(*) FROM review_queue":
            return len(self.rows)
        if s.startswith("SELECT count(*) FROM review_queue WHERE "):
            where = s.split(" WHERE ", 1)[1]
            return sum(1 for r in self.rows if _where_match(r, where, a))
        raise AssertionError(f"未实现 fetchval: {s[:80]}")

    async def fetch(self, sql, *a):
        s = " ".join(sql.split())
        assert s.startswith("SELECT id,ts"), s[:60]
        where = s.split(" WHERE ", 1)[1].split(" ORDER BY ", 1)[0]
        rows = [r for r in self.rows if _where_match(r, where, a)]
        rows.sort(key=lambda r: (r.get("ts") or "", r.get("id") or ""))
        return [dict(r) for r in rows]


class _Pool:
    def __init__(self, rows):
        self.conn = _Conn(rows)

    def acquire(self):
        return _Acquire(self.conn)


def _search(keyword: str):
    pg_store._pool = _Pool(ROWS)
    try:
        return asyncio.run(pg_store._pg_review_pending_impl(False, keyword, None, 0))
    finally:
        pg_store._pool = None


def test_like_escape_helper_maps_wildcards_literally():
    assert pg_store._like_contains("50%_!") == "50!%!_!!"
    assert "ESCAPE '!'" in pg_store._search_cond(3)


def test_percent_is_literal_not_wildcard():
    out = _search("%")
    assert out["total"] == 1
    assert [i["id"] for i in out["items"]] == ["q1"]


def test_underscore_and_bang_are_literal():
    assert _search("!")["total"] == 1          # 只有 q1 的 risk_reason 含 "!"
    assert _search("50%")["total"] == 1        # 含 % 的写法按字面量匹配
    assert _search("_")["total"] == 0          # 没有下划线；不能被当成"任意单字符"


def test_case_insensitive_substring():
    assert [i["id"] for i in _search("胸部CT")["items"]] == ["q2"]   # 数据大写、关键词混合大小写
    assert [i["id"] for i in _search("imaging")["items"]] == ["q2"]  # 英文字段同样不区分大小写
    assert [i["id"] for i in _search("他汀")["items"]] == ["q3"]
