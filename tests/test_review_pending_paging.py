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
     "submitted_by": "doctor01", "risk_reason": "高危相互作用", "status": "pending",
     "answer": "A" * 2000, "images": ["data:image/jpeg;base64,AAA"]},
    {"id": "r2", "agent": "literature", "question": "他汀类药物目标值",
     "submitted_by": "doctor02", "risk_reason": "低置信或未支持论断", "status": "pending",
     "answer": "B" * 20, "images": []},
    {"id": "r3", "agent": "imaging", "question": "肺部结节复查",
     "submitted_by": "doctor03", "risk_reason": "影像高危征象", "status": "pending",
     "answer": "C" * 10, "images": []},
]


def _client(monkeypatch, tmp_path):
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    monkeypatch.setattr(review, "pending", lambda: [dict(i) for i in ITEMS])
    monkeypatch.setattr(review, "get",
                        lambda rid: next((dict(i) for i in ITEMS if i["id"] == rid), None))
    # 真实 data/auth/users.json 可能已改密/缺账号：角色维度用**直接签发令牌**验证，
    # 只把 deps 的存在性回查换成恒有记录（角色仍取自令牌 payload）。
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _token(username: str, role: str) -> dict:
    from backend.core.auth import create_access_token
    return {"Authorization": "Bearer " + create_access_token(username, role)}


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
    assert payload["compact"] is False


# ---------- 第 69 批：compact 列表 + 单条详情接口 ----------

def test_compact_omits_answer_and_images(monkeypatch, tmp_path):
    """列表瘦身：不传 answer/images，改回预览/长度/影像张数；分页与 total 语义不变。"""
    c, _ = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    full = c.get("/api/v1/medical/review/pending", headers=H)
    slim = c.get("/api/v1/medical/review/pending", headers=H, params={"compact": 1})
    assert slim.status_code == 200
    items = slim.json()["pending"]
    assert [i["id"] for i in items] == ["r1", "r2", "r3"]
    assert slim.json()["total"] == 3
    first = items[0]
    assert "answer" not in first and "images" not in first
    assert len(first["answerPreview"]) == 160 and first["answerLength"] == 2000
    assert first["imageCount"] == 1
    assert first["risk_reason"] == "高危相互作用", "非内容字段必须原样保留"
    # 响应体确实变小（本夹具里 2000 字回答 + 1 张图；真实队列 212KB → 25KB）
    assert len(slim.content) < len(full.content) / 2


def test_compact_respects_image_visibility(monkeypatch, tmp_path):
    """影像张数按角色可见性计数：提交人本人可见，其他医生看到 0（不泄露存在性）。"""
    c, _ = _client(monkeypatch, tmp_path)
    own = c.get("/api/v1/medical/review/pending", headers=_token("doctor01", "doctor"),
                params={"compact": 1}).json()["pending"]
    assert next(i for i in own if i["id"] == "r1")["imageCount"] == 1
    other = c.get("/api/v1/medical/review/pending", headers=_token("doctor02", "doctor"),
                  params={"compact": 1}).json()["pending"]
    assert next(i for i in other if i["id"] == "r1")["imageCount"] == 0


def test_detail_returns_full_item_and_audits(monkeypatch, tmp_path):
    """详情接口回全文与影像，并写 detail 审计（只记 rid/角色，不记内容）。"""
    c, log = _client(monkeypatch, tmp_path)
    H = _login(c, "admin01")
    r = c.get("/api/v1/medical/review/item/r1", headers=H)
    assert r.status_code == 200, r.text
    item = r.json()["item"]
    assert item["answer"] == "A" * 2000 and item["images"]
    ev = [e for e in log.entries if e["payload"].get("action") == "detail"]
    assert ev and ev[-1]["payload"]["rid"] == "r1"
    assert "answer" not in ev[-1]["payload"], "审计不得记录临床正文"


def test_detail_hidden_for_qc_drug_and_unknown(monkeypatch, tmp_path):
    """qc 的 drug 项与不存在项同样 404（不通过详情反推被列表过滤的条目）。"""
    c, _ = _client(monkeypatch, tmp_path)
    Hq = _login(c, "qc01")
    assert c.get("/api/v1/medical/review/item/r1", headers=Hq).status_code == 404
    assert c.get("/api/v1/medical/review/item/nope", headers=Hq).status_code == 404
    ok = c.get("/api/v1/medical/review/item/r2", headers=Hq)
    assert ok.status_code == 200 and ok.json()["item"]["id"] == "r2"


def test_detail_strips_images_for_other_doctor(monkeypatch, tmp_path):
    """医生查看他人条目：正文可见（审核依据），影像按隐私边界剥离。"""
    c, _ = _client(monkeypatch, tmp_path)
    item = c.get("/api/v1/medical/review/item/r1",
                 headers=_token("doctor02", "doctor")).json()["item"]
    assert item["answer"] == "A" * 2000
    assert "images" not in item
