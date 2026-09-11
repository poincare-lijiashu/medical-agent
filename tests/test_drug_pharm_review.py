"""本批次任务2（核心）：drug 类豁免留痕模式 + 药剂科一票（语义变更，锁定新语义）。

- 语义收窄：QC_AUTO_SIGN_FULL 留痕模式的「入队即自动签发」短路只适用于非 drug agent——
  drug 类无论 FULL 开关如何都走既有三档：QC_AUTO_SIGN=on 时中危规则库提示自动签发
  （AI·阈值自动）、高危（禁忌/高危相互作用）恒人工；两开关均 off 时全部人工。
  目的：药剂科（pharm01）始终有 drug 高危待核对项可处理。
- 药剂科一票：/review/{rid}/resolve 按 agent 分流——drug → (pharmacist, admin)
  （问题4 收窄注明：原 (qc, admin, pharmacist)——质控科只管病例，drug 签字权收窄为
  药剂科+管理员）；其它 agent → (qc, admin)。双控仍强制（审核人≠提交人）；翻案维持 qc/admin。
- pending 可见性：pharmacist 在 _REVIEW_ROLES 白名单内（既有），drug 待核对项可见；
  问题4 彻底收窄（用户拍板）：/review/pending 对 qc 剔除 agent=drug——qc 界面彻底
  不见 drug 项（含旧遗留），pharmacist/admin 不变（药剂科本职 + 管理员兜底审核权）。
"""
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import auth as auth_mod
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

HIGH_RISK_Q = "西地那非和硝酸甘油能一起用吗"   # 禁忌 → 高危（review_flag=True，恒人工）
MID_RULE_Q = "氯吡格雷和奥美拉唑能一起吃吗"    # 中危规则库命中（QC_AUTO_SIGN=on 自动签发）
INFO_LOW_Q = "布洛芬怎么用"                    # 信息不足 → 非高危不入队（旧语义）


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- drug 类豁免留痕模式（FULL 短路不适用） ----

def test_drug_high_risk_needs_human_even_full_on(monkeypatch, tmp_path):
    """FULL=on 时 drug 高危（禁忌）仍需人工：入队不自动签发、无「AI·留痕模式(自动)」、
    无待确认强制（人工路径由药剂科复核）；审计记 review_enqueued(trace_mode=False)、
    绝不记 auto_sign_full。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": HIGH_RISK_Q}).json()
    rid = d["review_id"]
    assert rid, "drug 高危恒入队（无论 FULL 与否）"
    item = review.get(rid)
    assert item["status"] == "pending" and item["reviewed_by"] is None
    assert item["agent"] == "drug"
    assert item["self_confirm_required"] is False  # 人工路径不吃 FULL 的知情确认强制
    assert [i["id"] for i in review.pending()] == [rid], "drug 高危必须留在待核对队列等药剂科"
    enq = [e for e in audit.entries
           if e["payload"].get("action") == "review_enqueued" and e["payload"].get("rid") == rid]
    assert enq and enq[-1]["payload"]["trace_mode"] is False
    assert not any(e["payload"].get("action") == "auto_sign_full" and e["payload"].get("rid") == rid
                   for e in audit.entries), "drug 类绝不触发留痕模式自动签发"


def test_drug_mid_risk_rule_auto_sign_still_works_under_full(monkeypatch, tmp_path):
    """FULL=on 时 drug 中危规则库提示仍走 QC_AUTO_SIGN 三档：AI·阈值自动(规则库v2) 签发
    （非留痕模式标签），审计记 drug.auto_sign。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    assert settings.qc_auto_sign is True  # 默认开
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": MID_RULE_Q}).json()
    assert d["review_id"]
    item = review.get(d["review_id"])
    assert item["status"] == "approved"
    assert item["reviewed_by"] == "AI·阈值自动(规则库v2)"
    assert any(e["payload"].get("action") == "auto_sign" for e in audit.entries)


def test_drug_info_low_not_enqueued_under_full(monkeypatch, tmp_path):
    """FULL=on 时 drug 非高危（信息不足）回落旧语义：不入队（仅审计留痕）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": INFO_LOW_Q}).json()
    assert d["review_id"] is None
    assert review.list_all() == []


def test_drug_all_manual_when_both_switches_off(monkeypatch, tmp_path):
    """两开关均 off：drug 高危人工（pending 等药剂科）、中危不入队不自动签发（全部人工）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    monkeypatch.setattr(settings, "qc_auto_sign", False)
    doc = _login(c, "doctor01")
    d1 = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": HIGH_RISK_Q}).json()
    assert d1["review_id"] and review.get(d1["review_id"])["status"] == "pending"
    d2 = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": MID_RULE_Q}).json()
    assert d2["review_id"] is None, "QC_AUTO_SIGN=off 时中危不入队不自动签发"


# ---- 药剂科一票：resolve 按 agent 分流 ----

def test_pharmacist_resolve_drug_approved_and_audited(monkeypatch, tmp_path):
    """pharmacist 签发 drug 高危 → 200，reviewed_by=pharm01；审计 resolved_approved
    的 actor=pharm01（药剂科一票留痕）。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01")
    rid = c.post("/api/v1/medical/drug/ask", headers=_h(doc),
                 json={"question": HIGH_RISK_Q}).json()["review_id"]
    ph = _login(c, "pharm01")
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(ph),
               json={"decision": "approved", "note": "药剂科核对：相互作用成立，用法已复核"})
    assert r.status_code == 200, r.text
    item = r.json()
    assert item["status"] == "approved" and item["reviewed_by"] == "pharm01"
    hits = [e for e in audit.entries
            if e["payload"].get("action") == "resolved_approved"
            and e["payload"].get("rid") == rid]
    assert hits and hits[-1]["actor"] == "pharm01"


def test_pharmacist_reject_drug(monkeypatch, tmp_path):
    """pharmacist 驳回 drug 高危 → 200（签发/驳回双向一票）。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="drug", question="两药合用", answer="提示", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="doctor01")
    ph = _login(c, "pharm01")
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(ph),
               json={"decision": "rejected", "note": "剂量超限"})
    assert r.status_code == 200 and r.json()["status"] == "rejected"
    assert r.json()["reviewed_by"] == "pharm01"


def test_doctor_cannot_resolve_drug(monkeypatch, tmp_path):
    """drug 类签字权矩阵不含 doctor（问题4 注明：矩阵已收窄为 pharmacist/admin）：
    doctor → 403。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="drug", question="两药合用", answer="提示", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="qc01")
    doc = _login(c, "doctor01")
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(doc),
               json={"decision": "approved", "note": ""})
    assert r.status_code == 403


def test_qc_cannot_resolve_drug(monkeypatch, tmp_path):
    """问题4 收窄（用户拍板，锁定新语义）：drug 类签字权矩阵移除 qc——质控科只管病例，
    qc 签发/驳回 drug 项 → 403（pharmacist/admin 不受影响，见上方用例）。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="drug", question="两药合用", answer="提示", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="doctor01")
    qc = _login(c, "qc01")
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(qc),
               json={"decision": "approved", "note": ""})
    assert r.status_code == 403, "问题4：qc 不再签发/驳回 drug 项"
    assert "药物核对项" in r.json()["detail"]


def test_pharmacist_cannot_resolve_non_drug(monkeypatch, tmp_path):
    """非 drug 类维持收权语义：pharmacist → 403；qc → 200（对照）。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="literature", question="文献核对项", answer="内容", confidence=0.5,
                        risk_reason="低置信", submitted_by="doctor01")
    ph = _login(c, "pharm01")
    assert c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(ph),
                  json={"decision": "approved", "note": ""}).status_code == 403
    qc = _login(c, "qc01")
    assert c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(qc),
                  json={"decision": "approved", "note": ""}).status_code == 200


def test_pharmacist_cannot_resolve_own_drug_item(monkeypatch, tmp_path):
    """双控不放宽：pharmacist 不能签发/驳回本人提交的 drug 项 → 400。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="drug", question="两药合用", answer="提示", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="pharm01")
    ph = _login(c, "pharm01")
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(ph),
               json={"decision": "approved", "note": ""})
    assert r.status_code == 400 and "双控" in r.json()["detail"]


def test_pharmacist_reopen_still_forbidden(monkeypatch, tmp_path):
    """翻案（reopen）维持 qc/admin：pharmacist → 403（一票仅限签发/驳回）。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = review.submit(agent="drug", question="两药合用", answer="提示", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="doctor01")
    review.resolve(rid, "approved", "qc01", "ok")
    ph = _login(c, "pharm01")
    assert c.post(f"/api/v1/medical/review/{rid}/reopen", headers=_h(ph)).status_code == 403


def test_pending_visibility_qc_excludes_drug_pharm_admin_keep(monkeypatch, tmp_path):
    """pending 可见性（问题4 彻底收窄更新，锁定新语义）：pharmacist/admin 可见 drug
    高危待核对项（药剂科本职 + 管理员兜底审核权，不变）；qc 响应剔除 agent=drug——
    qc 界面彻底不见 drug 项（含旧遗留），仅可见其它助手高危项。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    doc = _login(c, "doctor01")
    rid_drug = c.post("/api/v1/medical/drug/ask", headers=_h(doc),
                      json={"question": HIGH_RISK_Q}).json()["review_id"]
    rid_other = review.submit(agent="literature", question="文献核对项", answer="内容",
                              confidence=0.5, risk_reason="低置信", submitted_by="doctor01")
    ph = _login(c, "pharm01")
    adm = _login(c, "admin01")
    qc = _login(c, "qc01")
    for tok in (ph, adm):  # pharmacist/admin：drug + 非 drug 均可见（语义不变）
        ids = [i["id"] for i in
               c.get("/api/v1/medical/review/pending", headers=_h(tok)).json()["pending"]]
        assert rid_drug in ids and rid_other in ids
    ids_qc = [i["id"] for i in
              c.get("/api/v1/medical/review/pending", headers=_h(qc)).json()["pending"]]
    assert rid_other in ids_qc, "qc 仍可见其它助手高危项"
    assert rid_drug not in ids_qc, "qc 界面彻底不见 drug 项（含旧遗留）"
