"""任务2+3：质控复核收权 + 医生侧轻量视图 + QC_AUTO_SIGN_FULL 留痕模式。

- 收权（显式行为变更，锁定新语义）：/review/{rid}/resolve 与 /review/{rid}/reopen
  仅 qc/admin 可调（doctor/pharmacist → 403）；GET 列表（pending/history）多角色可见权保留。
  本批次任务2 药剂科一票更新：drug 类 resolve 放宽到 pharmacist（见 test_drug_pharm_review），
  本文件锁定非 drug 类收权语义与 drug 留痕模式豁免语义。
- GET /qc/my-rejections：只返回当前用户自己的被驳回记录（数据隔离）。
- GET /review/my-pending-confirm + POST /review/{rid}/self-confirm：留痕模式下
  非 drug 类高危项入队即自动签发（AI·留痕模式(自动)），提交医生本人知情确认（仅本人）；
  drug 类豁免留痕模式（高危恒人工，无自动签发/待确认）。
"""
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import auth as auth_mod
from backend.core import medical_review as review
from backend.core import qc as qc_mod
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

FULL_RECORD = {f: "内容充分填写完整无误" for f in
               ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")}
HIGH_RISK_Q = "西地那非和硝酸甘油能一起用吗"   # 禁忌 → 高危
MID_RISK_Q = "氯吡格雷和奥美拉唑能一起吃吗"    # 中危 → 规则库自动留痕（非高危）


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _mkitem(submitted_by: str) -> str:
    # 阶段0.2 语义：my_rejections 只返回 agent=="qc" 的驳回（drug 驳回走 my_drug_rejections），
    # 本夹具改为 qc 类，保留「数据隔离」断言意图不变。
    return review.submit(agent="qc", question="测试核对项", answer="回答", confidence=0.9,
                         risk_reason="药物禁忌", submitted_by=submitted_by)


# ---- 任务2：复核操作收权（qc/admin）----

def test_review_resolve_role_matrix(monkeypatch, tmp_path):
    """新语义：doctor/pharmacist 调签发/驳回/翻案 → 403；qc/admin → 200。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
    adm = _login(c, "admin01", "Med@2026")
    rid = _mkitem("doctor01")
    # 收权：doctor/pharmacist → 403（现状 doctor/pharmacist 本可调，属显式行为变更）
    assert c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(doc),
                  json={"decision": "approved", "note": ""}).status_code == 403
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=_h(ph)).status_code == 403
    # 质控员签发通过 → 200（双控：qc01 ≠ 提交人 doctor01）
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(qc),
               json={"decision": "approved", "note": "核对无误"})
    assert r.status_code == 200, r.text
    assert r.json()["reviewed_by"] == "qc01"
    # 管理员翻案 → 200
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=_h(adm)).status_code == 200
    # 质控员驳回 → 200
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(qc),
               json={"decision": "rejected", "note": "书写不规范"})
    assert r.status_code == 200 and r.json()["status"] == "rejected"
    # 未认证 → 401/403
    assert c.post(f"/api/v1/medical/review/{rid}/reopen").status_code in (401, 403)


def test_review_list_endpoints_keep_multi_role(monkeypatch, tmp_path):
    """GET 列表（pending/history）可见权保留多角色：doctor/qc/admin 均 200。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
    adm = _login(c, "admin01", "Med@2026")
    for tok in (doc, qc, adm):
        assert c.get("/api/v1/medical/review/pending", headers=_h(tok)).status_code == 200
        assert c.get("/api/v1/medical/review/history", headers=_h(tok)).status_code == 200


# ---- 任务2：我的质控驳回（只返回自己的）----

def test_my_rejections_only_own_with_reason_and_time(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
    rid_doc = _mkitem("doctor01")
    rid_ph = _mkitem("pharm01")
    review.resolve(rid_doc, "rejected", "qc01", "主诉与现病史矛盾")
    review.resolve(rid_ph, "rejected", "qc01", "他人记录")
    r = c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc))
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    ids = [i["id"] for i in items]
    assert rid_doc in ids and rid_ph not in ids, "my-rejections 只返回自己的被驳回记录"
    it = next(i for i in items if i["id"] == rid_doc)
    assert it["review_note"] == "主诉与现病史矛盾"   # 驳回原因
    assert it["reviewed_by"] == "qc01"               # 审核人
    assert it["resolved_at"]                          # 驳回时间
    assert it["status"] == "rejected"
    # 通过签发的不出现在驳回列表
    ok_rid = _mkitem("doctor01")
    review.resolve(ok_rid, "approved", "qc01", "ok")
    ids2 = [i["id"] for i in c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc)).json()["items"]]
    assert ok_rid not in ids2


def test_my_rejections_require_auth(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    assert c.get("/api/v1/medical/qc/my-rejections").status_code in (401, 403)


# ---- 任务3：QC_AUTO_SIGN_FULL 留痕模式----

def test_full_mode_drug_high_risk_stays_pending(monkeypatch, tmp_path):
    """本批次任务2 语义变更（原断言「drug 高危入队即自动签发」按新语义更新）：
    drug 类豁免留痕模式——FULL=on 时高危（禁忌）仍走人工三档：保持 pending 等药剂科
    签发/驳回（药剂科一票），无 AI 自动签发、无知情确认推本人；审计记 review_enqueued。
    非 drug 类（literature 等）的 FULL 自动签发由 test_full_trace_mode 锁定。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": HIGH_RISK_Q})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is True  # 医生侧标记不变（不阻塞回答本身）
    rid = d["review_id"]
    assert rid
    item = review.get(rid)
    assert item["status"] == "pending"
    assert item["reviewed_by"] is None  # drug 高危绝不自动签发（药剂科一票）
    assert item["self_confirm_required"] is False
    assert [i["id"] for i in review.pending()] == [rid]
    assert not any(e["payload"].get("action") == "auto_sign_full" and e["payload"].get("rid") == rid
                   for e in audit.entries)


def test_full_mode_drug_mid_risk_auto_sign_via_threshold(monkeypatch, tmp_path):
    """本批次任务2 语义变更（原断言「中危按留痕模式标签自动签发」更新）：
    FULL=on 时 drug 中危规则库提示仍走 QC_AUTO_SIGN 三档 → AI·阈值自动(规则库v2)
    签发（非「AI·留痕模式(自动)」），非高危不要求知情确认。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01", "Med@2026")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": MID_RISK_Q}).json()
    assert d["review_id"]
    item = review.get(d["review_id"])
    assert item["status"] == "approved" and item["reviewed_by"] == "AI·阈值自动(规则库v2)"
    assert item["self_confirm_required"] is False
    assert c.get("/api/v1/medical/review/my-pending-confirm", headers=_h(doc)).json()["items"] == []


def test_full_mode_off_keeps_legacy(monkeypatch, tmp_path):
    """开关默认 False：高危维持人工双控（pending 等待 qc/admin 签发），无自动签发。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    doc = _login(c, "doctor01", "Med@2026")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": HIGH_RISK_Q}).json()
    rid = d["review_id"]
    assert rid and review.get(rid)["status"] == "pending"
    assert review.get(rid)["self_confirm_required"] is False
    assert c.get("/api/v1/medical/review/my-pending-confirm", headers=_h(doc)).json()["items"] == []


def test_full_mode_qc_record_auto_signed(monkeypatch, tmp_path):
    """full 模式：病历质控入队即自动签发并推本人确认（qc/record 恒入队路径）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    monkeypatch.setattr(qc_mod, "connotation_check", lambda record: [])  # 内涵轨离线假响应
    doc = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/record", headers=_h(doc), json={"record": dict(FULL_RECORD)})
    assert r.status_code == 200, r.text
    rid = r.json()["review_id"]
    assert rid
    item = review.get(rid)
    assert item["status"] == "approved" and item["reviewed_by"] == "AI·留痕模式(自动)"
    assert item["self_confirm_required"] is True


# ---- 任务3：知情确认（仅本人）与数据隔离 ----

def test_my_pending_confirm_isolation(monkeypatch, tmp_path):
    """doctor/pharmacist 视图数据隔离：my-pending-confirm 只返回 submitted_by=本人 的项。
    本批次任务2 注记：drug 类豁免留痕模式（高危不再自动签发+待确认），故改用
    review.submit 直造 self_confirm_required 条目验证隔离语义（与 agent 无关）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01", "Med@2026")
    ph = _login(c, "pharm01", "Med@2026")
    r1 = review.submit(agent="literature", question="文献高危项", answer="内容", confidence=0.5,
                       risk_reason="低置信", submitted_by="doctor01", self_confirm_required=True)
    r2 = review.submit(agent="literature", question="文献高危项乙", answer="内容", confidence=0.5,
                       risk_reason="低置信", submitted_by="pharm01", self_confirm_required=True)
    mine_doc = c.get("/api/v1/medical/review/my-pending-confirm", headers=_h(doc)).json()["items"]
    assert {i["id"] for i in mine_doc} == {r1}
    assert all(i["submitted_by"] == "doctor01" for i in mine_doc)
    mine_ph = c.get("/api/v1/medical/review/my-pending-confirm", headers=_h(ph)).json()["items"]
    assert {i["id"] for i in mine_ph} == {r2}


def test_self_confirm_only_self_and_once(monkeypatch, tmp_path):
    """知情确认仅本人且一次性。
    本批次任务2 注记：drug 类豁免留痕模式后不再产生「自动签发+待确认」条目，
    改用 review.submit 直造 self_confirm_required 条目验证确认流（与 agent 无关）。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01", "Med@2026")
    qc = _login(c, "qc01", "Med@2026")
    rid = review.submit(agent="literature", question="文献高危项", answer="内容", confidence=0.5,
                        risk_reason="低置信", submitted_by="doctor01", self_confirm_required=True)
    # 非本人（qc）→ 400
    assert c.post(f"/api/v1/medical/review/{rid}/self-confirm", headers=_h(qc)).status_code == 400
    # 本人 → 200：标记 confirmed_by_self/self_confirmed_at，审计记 self_confirmed
    r = c.post(f"/api/v1/medical/review/{rid}/self-confirm", headers=_h(doc))
    assert r.status_code == 200, r.text
    assert r.json()["confirmed_by_self"] is True and r.json()["self_confirmed_at"]
    hits = [e for e in audit.entries if e["payload"].get("action") == "self_confirmed"]
    assert hits and hits[-1]["payload"]["rid"] == rid and hits[-1]["actor"] == "doctor01"
    # 重复确认 → 400
    assert c.post(f"/api/v1/medical/review/{rid}/self-confirm", headers=_h(doc)).status_code == 400
    # 确认后从待确认列表消失
    ids = [i["id"] for i in c.get("/api/v1/medical/review/my-pending-confirm",
                                  headers=_h(doc)).json()["items"]]
    assert rid not in ids


def test_self_confirm_rejects_item_without_flag(monkeypatch, tmp_path):
    """普通 pending 项（无 self_confirm_required）不可自确认。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    rid = _mkitem("doctor01")
    assert c.post(f"/api/v1/medical/review/{rid}/self-confirm", headers=_h(doc)).status_code == 400


def test_config_exposes_full_mode_flag(monkeypatch, tmp_path):
    """/config 下发 qc_auto_sign_full（前端据此切换审核中心形态）。
    任务3：默认值已改 True（留痕模式默认开启）。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    d = c.get("/api/v1/medical/config", headers=_h(doc)).json()
    assert d["qc_auto_sign_full"] is True
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    d = c.get("/api/v1/medical/config", headers=_h(doc)).json()
    assert d["qc_auto_sign_full"] is False
