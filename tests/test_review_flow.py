"""高危双人核对端到端：药物禁忌→自动入队→提交人自核被拒→质控员签发。

用 TestClient，直接 seed 用户，不走 lifespan（避免测试期加载 bge-m3/连 Milvus）。
drug 走纯规则库，无需网络。

任务2 收权（行为变更，本文件锁定新语义）：签发/驳回/翻案由 qc/admin 行使
（原 doctor/pharmacist 可调复核端点，现 403）；双控（审核人≠提交人）仍由服务端强制。
本批次任务2 药剂科一票更新：drug 类 resolve 放宽到 pharmacist（pharmacist 可签发/驳回
drug 核对项），reopen 仍仅 qc/admin——相关断言已按新语义更新并注明。
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


def test_high_risk_dual_check_flow(monkeypatch):
    """人工双控端到端（本测试锁定人工双控语义：qc/admin 签发、doctor 调复核 403）。
    任务3 行为变更注记：qc_auto_sign_full 默认已改为 True（留痕模式），本测试显式
    monkeypatch 关闭以维持其锁定的人工双控路径；留痕模式语义由 test_review_permission 锁定。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
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

    # 任务2 收权：doctor 调复核端点 403（角色门先于双控判定）
    r2 = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=H(doc),
                json={"decision": "approved", "note": "x"})
    assert r2.status_code == 403

    # 队列里能看到（作为药师，列表可见权保留）
    r3 = c.get("/api/v1/medical/review/pending", headers=H(ph))
    assert r3.status_code == 200
    ids = [i["id"] for i in r3.json()["pending"]]
    assert rid in ids

    # 本批次任务2 药剂科一票（语义变更，原断言「药师签发 → 403」更新）：drug 类
    # 签发权放宽到 pharmacist——pharm01 ≠ 提交人 doctor01，双控仍成立
    r4 = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=H(ph),
                json={"decision": "approved", "note": "药剂科核对无误"})
    assert r4.status_code == 200, r4.text
    assert r4.json()["reviewed_by"] == "pharm01"

    # 质控员签字权不变：翻案回待核对后 qc 仍可签发（双控：qc01 ≠ 提交人 doctor01）
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(qc)).status_code == 200
    r5 = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=H(qc),
                json={"decision": "approved", "note": "核对无误"})
    assert r5.status_code == 200, r5.text
    assert r5.json()["status"] == "approved"
    assert r5.json()["reviewed_by"] == "qc01"


def test_low_risk_mid_risk_traced_and_auto_signed(monkeypatch):
    """本批次任务2 语义变更（按新语义更新）：drug 类豁免留痕模式——中危规则库命中
    走 QC_AUTO_SIGN 三档，签发人为「AI·阈值自动(规则库v2)」（原断言「AI·留痕模式(自动)」
    随 drug 豁免语义更新）；conf/needs_human_review 校准断言保持不变。"""
    from backend.core import medical_review as review
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    # 中危（非禁忌/高危）→ 规则库命中入队 + AI·阈值自动签发，医生侧不阻塞
    r = c.post("/api/v1/medical/drug/ask", headers={"Authorization": "Bearer " + doc},
               json={"question": "氯吡格雷和奥美拉唑能一起吃吗"})
    d = r.json()
    assert d["needs_human_review"] is False
    assert d["confidence"] >= 0.75  # 规则命中，真实校准为中高，不再"一律低置信"
    assert d["review_id"], "中危规则库命中应入队留痕"
    item = review.get(d["review_id"])
    assert item["status"] == "approved"
    assert item["reviewed_by"] == "AI·阈值自动(规则库v2)"
    assert item["self_confirm_required"] is False  # 非低置信非高危 → 无待确认


# ---- 双控自动签发翻案（reopen）：reviewed_by=「AI·阈值自动」的已签发项可回待核对 ----

def test_reopen_auto_signed_item(monkeypatch, tmp_path):
    from backend.core import medical_review as review
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))  # 队列隔离，不污染真实数据
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
    adm = _login(c, "admin01", "Med@2026")
    H = lambda t: {"Authorization": "Bearer " + t}
    # 模拟 QC_AUTO_SIGN 的落库形态：提交 + AI·阈值自动 签发（提交人=qc01，供双控对称校验）
    rid = review.submit(agent="drug", question="中危联合用药提示", answer="提示内容", confidence=0.80,
                        risk_reason="中危相互作用", submitted_by="qc01")
    review.resolve(rid, "approved", "AI·阈值自动(规则库v2)", "QC_AUTO_SIGN：中危规则库提示自动签发")
    # pending 条目重开 → 400（管理员有复核权，错误为业务态）
    rid2 = review.submit(agent="drug", question="另一条", answer="内容", confidence=0.9,
                         risk_reason="药物禁忌", submitted_by="pharm01")
    assert c.post(f"/api/v1/medical/review/{rid2}/reopen", headers=H(adm)).status_code == 400
    # 任务2 收权：doctor/pharmacist 调翻案 → 403
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(doc)).status_code == 403
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(ph)).status_code == 403
    # 提交人本人翻案 → 400（双控对称：qc01 是该条目提交人）
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(qc)).status_code == 400
    # 其他复核人（admin）翻案成功：回到待核对，签署信息清除
    r = c.post(f"/api/v1/medical/review/{rid}/reopen", headers=H(adm))
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "pending"
    assert d["reviewed_by"] is None and d["review_note"] is None and d["resolved_at"] is None
    ids = [i["id"] for i in c.get("/api/v1/medical/review/pending", headers=H(adm)).json()["pending"]]
    assert rid in ids
    # 不存在的条目 → 400
    assert c.post("/api/v1/medical/review/rev-nope/reopen", headers=H(adm)).status_code == 400
    # 未认证 → 401
    assert c.post(f"/api/v1/medical/review/{rid}/reopen").status_code in (401, 403)


# ---- 开药审核 UX 问题1：「我的历史」须含本人作为审核人（reviewed_by）的记录 ----

def test_history_includes_reviewed_by_records(monkeypatch, tmp_path):
    """pharmacist 签发/驳回他人提交的 drug 项后，/review/history 响应必须包含该记录
    （reviewed_by=pharm01、submitted_by=他人）——前端「我的历史」按
    submitted_by=me OR reviewed_by=me 过滤的数据源即本响应。
    服务端保持全量档案语义不变（非 admin 的 images 收窄边界不放宽）；
    队列文件隔离 + 纯 JSON 模式（PG 在线也不触真库）。"""
    from backend.core import medical_review as review
    from backend.core import pg_store
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    monkeypatch.setattr(pg_store, "_pool", None)
    c = _client()
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    H = lambda t: {"Authorization": "Bearer " + t}
    rid = review.submit(agent="drug", question="西地那非和硝酸甘油能一起用吗",
                        answer="禁忌联用，禁止合用", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="doctor01")
    review.resolve(rid, "approved", "pharm01", "药剂科核对无误")
    # 审核人视角：响应含本人签发的记录（submitted_by=他人、reviewed_by=me）
    items = c.get("/api/v1/medical/review/history", headers=H(ph)).json()["items"]
    hit = next(i for i in items if i["id"] == rid)
    assert hit["reviewed_by"] == "pharm01"
    assert hit["submitted_by"] == "doctor01"
    assert hit["status"] == "approved" and hit["resolved_at"]
    # 提交医生视角：同一记录按 submitted_by=me 可见（既有语义不回退）
    items_d = c.get("/api/v1/medical/review/history", headers=H(doc)).json()["items"]
    assert any(i["id"] == rid and i["reviewed_by"] == "pharm01" for i in items_d)
