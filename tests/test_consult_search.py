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

from backend.core import consults
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

ROWS = [
    {"id": "c1", "question": "反复胸痛三月，考虑冠心病", "initiator": "doctor01",
     "target_depts": ["心内科", "内分泌科"], "opinions": []},
    {"id": "c2", "question": "糖尿病足溃疡", "initiator": "doctor02",
     "target_depts": ["外科"],
     "opinions": [{"dept": "外科", "doctor": "waike01", "content": "建议清创引流"}]},
    {"id": "c3", "question": "头痛伴视物模糊",
     "ai_analysis": "神经内科会诊意见：需排除颅内占位",
     "initiator": "doctor01", "target_depts": ["心内科"], "opinions": []},
]


def _client(monkeypatch, tmp_path, dept="心内科"):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(consults, "inbox_for",
                        lambda d: [dict(i) for i in ROWS] if d else [])
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
    monkeypatch.setattr(consults, "by_initiator", lambda u: [dict(i) for i in ROWS])
    monkeypatch.setattr(consults, "participated", lambda u: [dict(ROWS[1])])
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
