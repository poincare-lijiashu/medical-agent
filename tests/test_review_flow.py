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


def test_low_risk_not_enqueued():
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    # 中危（非禁忌/高危）不强制双人核对
    r = c.post("/api/v1/medical/drug/ask", headers={"Authorization": "Bearer " + doc},
               json={"question": "氯吡格雷和奥美拉唑能一起吃吗"})
    d = r.json()
    assert d["needs_human_review"] is False
    assert d["review_id"] is None
    assert d["confidence"] >= 0.75  # 规则命中，真实校准为中高，不再"一律低置信"
