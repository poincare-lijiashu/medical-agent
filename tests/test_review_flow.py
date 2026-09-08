"""高危双人核对端到端：药物禁忌→自动入队→提交人自核被拒→第二名药师签发。

用 TestClient，直接 seed 用户，不走 lifespan（避免测试期加载 bge-m3/连 Milvus）。
drug 走纯规则库，无需网络。
"""
from fastapi.testclient import TestClient

from backend.core.auth import seed_default_users
from backend.main import app


def _client():
    seed_default_users()
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_high_risk_dual_check_flow():
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    H = lambda t: {"Authorization": "Bearer " + t}

    # 药物禁忌 → 高风险 → 入队 + 高置信
    r = c.post("/api/v1/medical/drug/ask", headers=H(doc),
               json={"question": "西地那非和硝酸甘油能一起用吗"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is True
    assert d["confidence"] >= 0.85
    rid = d["review_id"]
    assert rid

    # 医生自己不能核对本人提交（双控）
    r2 = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=H(doc),
                json={"decision": "approved", "note": "x"})
    assert r2.status_code == 400  # ReviewError → 400

    # 队列里能看到（作为药师）
    r3 = c.get("/api/v1/medical/review/pending", headers=H(ph))
    assert r3.status_code == 200
    ids = [i["id"] for i in r3.json()["pending"]]
    assert rid in ids

    # 第二名药师签发通过
    r4 = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=H(ph),
                json={"decision": "approved", "note": "核对无误"})
    assert r4.status_code == 200, r4.text
    assert r4.json()["status"] == "approved"
    assert r4.json()["reviewed_by"] == "pharm01"


def test_low_risk_not_enqueued(monkeypatch):
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign", False)  # 自动签发关闭时维持旧语义：中危不入队
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    # 中危（非禁忌/高危）不强制双人核对
    r = c.post("/api/v1/medical/drug/ask", headers={"Authorization": "Bearer " + doc},
               json={"question": "氯吡格雷和奥美拉唑能一起吃吗"})
    d = r.json()
    assert d["needs_human_review"] is False
    assert d["review_id"] is None
    assert d["confidence"] >= 0.75  # 规则命中，真实校准为中高，不再"一律低置信"


# ---- 双控自动签发翻案（reopen）：reviewed_by=「AI·阈值自动」的已签发项可回待核对 ----

def test_reopen_auto_signed_item(monkeypatch, tmp_path):
    from backend.core import medical_review as review
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))  # 队列隔离，不污染真实数据
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    H = lambda t: {"Authorization": "Bearer " + t}
    # 模拟 QC_AUTO_SIGN 的落库形态：提交 + AI·阈值自动 签发
    rid = review.submit(agent="drug", question="中危联合用药提示", answer="提示内容", confidence=0.80,
                        risk_reason="中危相互作用", submitted_by="doctor01")
    review.resolve(rid, "approved", "AI·阈值自动(规则库v2)", "QC_AUTO_SIGN：中危规则库提示自动签发")
    # pending 条目重开 → 400
    rid2 = review.submit(agent="drug", question="另一条", answer="内容", confidence=0.9,
                         risk_reason="药物禁忌", submitted_by="pharm01")
    assert c.post(f"/api/v1/medical/review/{rid2}/reopen", headers=H(ph)).status_code == 400
    # 提交人本人翻案 → 400（双控对称）
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(doc)).status_code == 400
    # 其他人员翻案成功：回到待核对，签署信息清除
    r = c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(ph))
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "pending"
    assert d["reviewed_by"] is None and d["review_note"] is None and d["resolved_at"] is None
    ids = [i["id"] for i in c.get("/api/v1/medical/review/pending", headers=H(ph)).json()["pending"]]
    assert rid in ids
    # 不存在的条目 → 400
    assert c.post("/api/v1/medical/review/rev-nope/reopen", headers=H(ph)).status_code == 400
    # 未认证 → 401
    assert c.post(f"/api/v1/medical/review/{rid}/reopen").status_code in (401, 403)
