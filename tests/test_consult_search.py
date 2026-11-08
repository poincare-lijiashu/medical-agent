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
    monkeypatch.setattr(consults, "inbox_for",
                        lambda d, q="": [dict(i) for i in ROWS
                                         if d and consults.matches_query(i, q)])
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": dept})
    return TestClient(app), log


def _h():
    return {"Authorization": "Bearer " + create_access_token("doctor01", "doctor")}


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
    monkeypatch.setattr(consults, "by_initiator",
                        lambda u, q="": [dict(i) for i in ROWS if consults.matches_query(i, q)])
    monkeypatch.setattr(consults, "participated",
                        lambda u, q="": ([dict(ROWS[1])]
                                         if consults.matches_query(ROWS[1], q) else []))
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
    monkeypatch.setattr(consults, "by_initiator",
                        lambda u, q="": [dict(i) for i in ROWS if consults.matches_query(i, q)])
    monkeypatch.setattr(consults, "participated",
                        lambda u, q="": ([dict(ROWS[1])]
                                         if consults.matches_query(ROWS[1], q) else []))

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
