"""第 63 批：`GET /review/pending` 的 limit/offset/q 与 `total`。

背景：该端点实测 76 条 = 229KB，而侧边角标每 60s 轮询只为拿一个数字；
前端表格也是"整表下发 + 浏览器 slice"。本批新增可选分页/筛选参数，
**缺省仍返回全部**（旧调用方与既有测试零影响）。

这里直接 monkeypatch 域层 `review.pending`，不依赖真实队列数据。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

ITEMS = [
    {"id": "r1", "agent": "drug", "question": "阿司匹林作用",
     "submitted_by": "doctor01", "risk_reason": "高危相互作用", "status": "pending"},
    {"id": "r2", "agent": "literature", "question": "他汀类药物目标值",
     "submitted_by": "doctor02", "risk_reason": "低置信或未支持论断", "status": "pending"},
    {"id": "r3", "agent": "imaging", "question": "肺部结节复查",
     "submitted_by": "doctor03", "risk_reason": "影像高危征象", "status": "pending"},
]


def _client(monkeypatch, tmp_path):
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    monkeypatch.setattr(review, "pending", lambda: [dict(i) for i in ITEMS])
    return TestClient(app), log


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


def _ids(resp):
    assert resp.status_code == 200, resp.text
    return [i["id"] for i in resp.json()["pending"]]


def test_default_returns_all_items_with_total(monkeypatch, tmp_path):
    """不传参数 = 旧行为（全量），只多一个 total 字段。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    r = c.get("/api/v1/medical/review/pending", headers=H)
    assert _ids(r) == ["r1", "r2", "r3"]
    assert r.json()["total"] == 3


def test_limit_zero_returns_count_only(monkeypatch, tmp_path):
    """角标/清空确认只取计数：响应体里没有任何条目。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    r = c.get("/api/v1/medical/review/pending", headers=H, params={"limit": 0})
    assert r.status_code == 200
    assert r.json()["pending"] == []
    assert r.json()["total"] == 3


def test_limit_and_offset_page(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H,
                      params={"limit": 1})) == ["r1"]
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H,
                      params={"limit": 1, "offset": 1})) == ["r2"]
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H,
                      params={"limit": 5, "offset": 2})) == ["r3"]
    # offset 越界 → 空页但 total 仍是真值（前端据此纠偏页码）
    r = c.get("/api/v1/medical/review/pending", headers=H,
              params={"limit": 12, "offset": 99})
    assert r.json()["pending"] == [] and r.json()["total"] == 3


def test_q_filters_server_side_and_total_follows_filter(monkeypatch, tmp_path):
    """关键字在服务端过滤：分页时不会把匹配项漏到别的页。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    r = c.get("/api/v1/medical/review/pending", headers=H, params={"q": "他汀"})
    assert _ids(r) == ["r2"] and r.json()["total"] == 1
    # 命中 submitted_by / risk_reason（与前端旧过滤字段一致）
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H,
                      params={"q": "doctor03"})) == ["r3"]
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H,
                      params={"q": "影像高危"})) == ["r3"]
    # 大小写不敏感 + 无匹配
    r0 = c.get("/api/v1/medical/review/pending", headers=H, params={"q": "NOT-EXIST"})
    assert r0.json()["pending"] == [] and r0.json()["total"] == 0


def test_qc_total_excludes_drug(monkeypatch, tmp_path):
    """问题4 语义：qc 视角看不到 drug 项，total 必须是角色过滤后的数（角标同源）。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "qc01")
    r = c.get("/api/v1/medical/review/pending", headers=H, params={"limit": 0})
    assert r.json()["total"] == 2
    assert _ids(c.get("/api/v1/medical/review/pending", headers=H)) == ["r2", "r3"]


def test_invalid_query_params_rejected(monkeypatch, tmp_path):
    """非法分页参数 422（limit 上限 500 / offset 非负 / q 长度 100）。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    assert c.get("/api/v1/medical/review/pending", headers=H,
                 params={"limit": 501}).status_code == 422
    assert c.get("/api/v1/medical/review/pending", headers=H,
                 params={"offset": -1}).status_code == 422
    assert c.get("/api/v1/medical/review/pending", headers=H,
                 params={"q": "x" * 101}).status_code == 422


def test_audit_records_page_and_total(monkeypatch, tmp_path):
    """审计留痕补 total/limit/offset，便于回溯"谁翻到第几页"。"""
    c, log = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    c.get("/api/v1/medical/review/pending", headers=H, params={"limit": 1, "offset": 1})
    ev = [e for e in log.entries if e["payload"].get("action") == "list_pending"]
    assert ev, "应写 list_pending 审计"
    payload = ev[-1]["payload"]
    assert payload["n"] == 1 and payload["total"] == 3
    assert payload["limit"] == 1 and payload["offset"] == 1
