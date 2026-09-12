"""QC 自动化：QC_AUTO_PASS 三档分流 + QC_AUTO_SIGN 中危自动签发 + 签名/科室服务端绑定。

settings 开关 monkeypatch 到实例属性（teardown 自动恢复）；内涵轨 LLM 用假响应隔离，
绝不触网；队列文件与审计隔离到 tmp_path。
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
MID_RISK_Q = "氯吡格雷和奥美拉唑能一起吃吗"   # 中危 → 规则库 v2
HIGH_RISK_Q = "西地那非和硝酸甘油能一起用吗"  # 禁忌 → 必须人工双控


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))  # 用户表隔离（测试会创建无 dept 账号）
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    monkeypatch.setattr(qc_mod, "connotation_check", lambda record: [])  # 内涵轨离线假响应
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- 批次二-4：医师签名/科室服务端绑定（后端权威，覆盖前端任何值）----

def test_qc_record_binds_signature_and_department(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    captured = {}
    real = qc_mod.review_quality

    def spy(record, *a, **k):
        captured["record"] = dict(record)
        return real(record, *a, **k)

    monkeypatch.setattr(qc_mod, "review_quality", spy)
    rec = dict(FULL_RECORD, **{"医师签名": "伪造签名", "科室": "伪造科室"})
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": rec})
    assert r.status_code == 200, r.text
    assert captured["record"]["医师签名"] == "doctor01"  # 强制绑定当前账号
    assert captured["record"]["科室"] == "口腔科"        # 强制绑定账号科室（seed dept）


def test_qc_record_rejected_without_department(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    r = c.post("/api/v1/medical/admin/users", headers=_h(adm),
               json={"username": "nodept1", "password": "Str0ngPass!", "role": "doctor"})
    assert r.status_code == 200, r.text
    tok = _login(c, "nodept1", "Str0ngPass!")
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": {"主诉": "v"}})
    assert r.status_code == 400, r.text
    assert "科室" in r.json()["detail"]


# ---- 批次二-6：QC_AUTO_PASS 三档分流 ----

def test_qc_auto_pass_hard_defects_auto_rejected(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_pass", True)
    tok = _login(c, "doctor01", "Med@2026")
    rec = dict(FULL_RECORD)
    rec.pop("主诉")  # 确定性硬伤：主诉缺失
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": rec})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "auto_rejected"
    assert d["needs_human_review"] is False
    assert d["review_id"] is None  # 不入队
    assert "⛔" in d["answer"] and "主诉" in d["answer"]
    assert review.pending() == []


def test_qc_auto_pass_high_confidence_archived(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_pass", True)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": dict(FULL_RECORD)})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "ok"
    assert d["needs_human_review"] is False  # 不入队
    assert d["confidence"] >= 0.85
    assert "✅" in d["answer"] and "AI 预审通过" in d["answer"]
    assert review.pending() == []


def test_qc_auto_pass_connotation_flaw_falls_back_to_manual(monkeypatch, tmp_path):
    """硬伤全过但内涵轨有缺陷（置信 <0.85）→ 仍走现行为入队人工。
    任务3 注记：本测试锁定人工队列语义，显式关闭留痕模式（默认已改 True）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    monkeypatch.setattr(settings, "qc_auto_pass", True)
    monkeypatch.setattr(qc_mod, "connotation_check",
                        lambda record: [{"field": "内涵质量", "issue": "诊断缺乏病史支持",
                                         "level": "中", "track": "内涵质量"}])
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": dict(FULL_RECORD)})
    d = r.json()
    assert d["status"] == "ok"
    assert d["needs_human_review"] is True
    assert d["review_id"]
    assert len(review.pending()) == 1


def test_qc_auto_pass_off_keeps_legacy(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)  # 任务3：人工队列语义测试，关闭留痕模式
    monkeypatch.setattr(settings, "qc_auto_pass", False)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": dict(FULL_RECORD)})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is True
    assert d["status"] == "ok"
    assert d["review_id"]
    assert len(review.pending()) == 1


def test_review_quality_exposes_hard_defects():
    rec = dict(FULL_RECORD)
    rec.pop("既往史")
    out = qc_mod.review_quality(rec)
    assert out["hard_pass"] is False
    assert any("既往史" in s for s in out["hard_defects"])
    ok = qc_mod.review_quality(dict(FULL_RECORD))
    assert ok["hard_pass"] is True and ok["hard_defects"] == []


# ---- 批次二-7：QC_AUTO_SIGN 中危规则库自动签发 ----

def test_qc_auto_sign_mid_risk_auto_approved(monkeypatch, tmp_path):
    """QC_AUTO_SIGN 旧语义（留痕模式关闭时）：中危规则库提示由「AI·阈值自动」签发。
    任务3 注记：留痕模式默认开启时中危统一由「AI·留痕模式(自动)」签发（见
    test_review_permission.test_full_mode_mid_risk_auto_sign_without_self_confirm）；
    本测试显式关闭留痕模式以继续锁定 QC_AUTO_SIGN 既有语义。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign", True)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)  # 锁定旧语义
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/drug/ask", headers=_h(tok), json={"question": MID_RISK_Q})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is False          # 医生侧不受影响
    assert d["confidence"] == 0.80
    rid = d["review_id"]
    assert rid, "中危应已入队并返回 review_id"
    hist = c.get("/api/v1/medical/review/history", headers=_h(tok)).json()["items"]
    item = next(i for i in hist if i["id"] == rid)
    assert item["status"] == "approved"
    assert item["reviewed_by"].startswith("AI·阈值自动")
    assert review.pending() == []
    signs = [e for e in audit.entries if e["payload"].get("action") == "auto_sign"]
    assert signs and signs[-1]["payload"].get("rid") == rid


def test_qc_auto_sign_off_keeps_legacy(monkeypatch, tmp_path):
    """QC_AUTO_SIGN=False → 完全现行为：中危不入队（review_id=None），无自动签发。
    任务3 注记：同时关闭留痕模式（默认 True 时中危也会留痕入队）。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign", False)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)  # 锁定旧语义
    tok = _login(c, "doctor01", "Med@2026")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(tok),
               json={"question": MID_RISK_Q}).json()
    assert d["needs_human_review"] is False
    assert d["review_id"] is None
    assert not [e for e in audit.entries if e["payload"].get("action") == "auto_sign"]


def test_qc_auto_sign_high_risk_still_manual(monkeypatch, tmp_path):
    """高危（禁忌）在 QC_AUTO_SIGN 旧语义下仍人工双控。
    任务3 注记：显式关闭留痕模式（默认 True 时高危入队即自动签发+待确认，见
    test_review_permission.test_full_mode_high_risk_auto_sign_on_enqueue）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign", True)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)  # 锁定旧语义
    tok = _login(c, "doctor01", "Med@2026")
    d = c.post("/api/v1/medical/drug/ask", headers=_h(tok),
               json={"question": HIGH_RISK_Q}).json()
    assert d["needs_human_review"] is True  # 禁忌/高危仍人工双控
    rid = d["review_id"]
    assert rid and review.get(rid)["status"] == "pending"
