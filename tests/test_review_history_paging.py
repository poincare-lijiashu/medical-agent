"""第 73 批：`GET /review/history` 的服务端分页/筛选/瘦身 + 历史详情合规规则。

背景：qc/admin 历史 50 条 ≈53KB、doctor 历史 200 条 ≈176KB，前端一直是"全量下发 +
浏览器过滤/切片"。本批新增可选 `limit/offset/q/compact/hide_pending/hide_drug`，
**缺省行为与旧版一致**；`GET /review/history/item/{rid}` 提供单条全文，但与待核对详情
不同：**人工签发记录剥 images**（FIND-03a），AI 自动签发才按角色可见。

这里 monkeypatch 域层 list_all/list_involved/get，不依赖真实队列。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

ITEMS = [
    {"id": "h1", "agent": "literature", "status": "approved",
     "submitted_by": "doctor01", "reviewed_by": "AI·留痕模式(自动)",
     "question": "他汀目标值", "risk_reason": "低置信", "confidence": 0.4,
     "answer": "A" * 400, "images": ["data:image/jpeg;base64,AAA"]},
    {"id": "h2", "agent": "imaging", "status": "rejected",
     "submitted_by": "doctor01", "reviewed_by": "qc01",
     "question": "肺结节复查", "risk_reason": "影像高危",
     "answer": "B" * 30, "images": ["data:image/jpeg;base64,BBB"]},
    {"id": "h3", "agent": "drug", "status": "approved",
     "submitted_by": "doctor02", "reviewed_by": "pharm01",
     "question": "华法林联用", "risk_reason": "高危相互作用",
     "answer": "C" * 20, "images": []},
    {"id": "h4", "agent": "qc", "status": "pending",
     "submitted_by": "doctor01", "reviewed_by": None,
     "question": "待处理项", "risk_reason": "低置信",
     "answer": "D" * 10, "images": []},
]


def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "list_all",
                        lambda limit=50: [dict(i) for i in ITEMS])
    monkeypatch.setattr(review, "list_involved",
                        lambda me, limit=200: [dict(i) for i in ITEMS
                                               if me in (i.get("submitted_by"), i.get("reviewed_by"))])
    monkeypatch.setattr(review, "get",
                        lambda rid: next((dict(i) for i in ITEMS if i["id"] == rid), None))
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def _ids(resp):
    assert resp.status_code == 200, resp.text
    return [i["id"] for i in resp.json()["items"]]


def test_default_full_history_unchanged(monkeypatch, tmp_path):
    """不传参数 = 旧行为（全量 4 条）+ 新增 total；人工签发项默认剥 images。"""
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/history", headers=_h("admin01", "admin"))
    assert _ids(r) == ["h1", "h2", "h3", "h4"]
    assert r.json()["total"] == 4
    items = {i["id"]: i for i in r.json()["items"]}
    assert items["h1"]["images"], "AI 自动签发保留影像"
    assert "images" not in items["h2"], "人工签发不留原图（FIND-03a）"


def test_hide_pending_and_drug_filters(monkeypatch, tmp_path):
    """hide_pending/hide_drug 把前端过滤下推服务端（分页前过滤，total 同源）。"""
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/history", headers=_h("admin01", "admin"),
              params={"hide_pending": 1, "hide_drug": 1})
    assert _ids(r) == ["h1", "h2"]
    assert r.json()["total"] == 2


def test_paging_and_out_of_range(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("admin01", "admin")
    assert _ids(c.get("/api/v1/medical/review/history", headers=H,
                      params={"limit": 1, "offset": 1})) == ["h2"]
    r = c.get("/api/v1/medical/review/history", headers=H,
              params={"limit": 12, "offset": 99})
    assert r.json()["items"] == [] and r.json()["total"] == 4


def test_q_filters_before_paging(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("admin01", "admin")
    r = c.get("/api/v1/medical/review/history", headers=H,
              params={"q": "华法林", "limit": 12})
    assert _ids(r) == ["h3"] and r.json()["total"] == 1
    r2 = c.get("/api/v1/medical/review/history", headers=H, params={"q": "none"})
    assert r2.json()["items"] == [] and r2.json()["total"] == 0


def test_compact_strips_answer_and_images(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/history", headers=_h("admin01", "admin"),
              params={"compact": 1, "limit": 12})
    first = r.json()["items"][0]
    assert first["id"] == "h1"
    assert "answer" not in first and "images" not in first
    assert len(first["answerPreview"]) == 160 and first["answerLength"] == 400
    assert first["imageCount"] == 1


def test_history_detail_compliance_rules(monkeypatch, tmp_path):
    """历史详情：人工签发不留原图；AI 自动签发保留；正文始终可取。"""
    c, log = _client(monkeypatch, tmp_path)
    H = _h("admin01", "admin")
    auto = c.get("/api/v1/medical/review/history/item/h1", headers=H).json()["item"]
    assert auto["answer"] == "A" * 400 and auto["images"]
    manual = c.get("/api/v1/medical/review/history/item/h2", headers=H).json()["item"]
    assert manual["answer"] == "B" * 30 and "images" not in manual
    ev = [e for e in log.entries if e["payload"].get("action") == "history_detail"]
    assert {e["payload"]["rid"] for e in ev} == {"h1", "h2"}
    assert all("answer" not in e["payload"] for e in ev), "审计不得记录临床正文"


def test_history_detail_scope_for_doctors(monkeypatch, tmp_path):
    """doctor：本人提交的自动签发记录保留影像；本人提交的人工签发不保留；无关记录 404。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _h("doctor01", "doctor")
    auto = c.get("/api/v1/medical/review/history/item/h1", headers=H).json()["item"]
    assert auto["images"], "提交人本人 + AI 自动签发 → 留痕影像可见"
    manual = c.get("/api/v1/medical/review/history/item/h2", headers=H).json()["item"]
    assert "images" not in manual
    assert c.get("/api/v1/medical/review/history/item/h3",
                 headers=H).status_code == 404, "未参与记录不可见"
    assert c.get("/api/v1/medical/review/history/item/nope", headers=H).status_code == 404


def test_history_list_role_scope(monkeypatch, tmp_path):
    """doctor 列表只含本人参与项（list_involved 语义）；pharmacist 审核过即参与。"""
    c, _ = _client(monkeypatch, tmp_path)
    assert _ids(c.get("/api/v1/medical/review/history", headers=_h("doctor01", "doctor"))) == ["h1", "h2", "h4"]
    assert _ids(c.get("/api/v1/medical/review/history", headers=_h("pharm01", "pharmacist"))) == ["h3"]


def test_audit_records_history_paging(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    c.get("/api/v1/medical/review/history", headers=_h("admin01", "admin"),
          params={"limit": 1, "offset": 1, "compact": 1})
    ev = [e for e in log.entries if e["payload"].get("action") == "history"]
    assert ev, "应写 history 审计"
    p = ev[-1]["payload"]
    assert p["n"] == 1 and p["total"] == 4
    assert p["limit"] == 1 and p["offset"] == 1 and p["compact"] is True
