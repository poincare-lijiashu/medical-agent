"""任务6：质控驳回重提——resubmit_of 校验（他人记录 403 / 非驳回记录 400 / 不存在 400）、
attempt 沿链递增、attempt>=2 不合格自动升级 rejected_escalated（优先于 QC_AUTO_PASS，
永远进人工）、my-rejections 含重提链置顶条目与原病历预填数据。

内涵轨 LLM 用假响应隔离，绝不触网；队列文件/用户表/审计隔离到 tmp_path。
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
_DEFECT = [{"field": "内涵质量", "issue": "诊断缺乏病史支持", "level": "中", "track": "内涵质量"}]


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    # 任务3 注记：重提链依赖人工队列状态机（rejected → resubmit），显式关闭留痕模式
    # （默认已改 True；留痕模式下条目入队即自动签发，无法人工驳回构造重提链）
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    seed_default_users()
    monkeypatch.setattr(qc_mod, "connotation_check", lambda record: list(_DEFECT))
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit  # 不进 with，跳过 lifespan 的模型预加载


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _submit_qc(c, tok, resubmit_of=None):
    body = {"record": dict(FULL_RECORD)}
    if resubmit_of:
        body["resubmit_of"] = resubmit_of
    return c.post("/api/v1/medical/qc/record", headers=_h(tok), json=body)


# ---------- resubmit_of 校验 ----------

def test_resubmit_of_validation(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    ph = _login(c, "pharm01")
    # 原记录不存在 → 400
    r = _submit_qc(c, doc, resubmit_of="rev-notexist")
    assert r.status_code == 400 and "不存在" in r.json()["detail"]
    # 非驳回记录（pending）→ 400
    r0 = _submit_qc(c, doc)
    assert r0.status_code == 200, r0.text
    rid_pending = r0.json()["review_id"]
    r = _submit_qc(c, doc, resubmit_of=rid_pending)
    assert r.status_code == 400 and "驳回" in r.json()["detail"]
    # 他人记录 → 403（pharm01 的被驳回记录，doctor01 不可重提）
    r1 = _submit_qc(c, ph)
    assert r1.status_code == 200, r1.text
    review.resolve(r1.json()["review_id"], "rejected", "qc01", "需修改")
    r = _submit_qc(c, doc, resubmit_of=r1.json()["review_id"])
    assert r.status_code == 403 and "本人" in r.json()["detail"]
    # 已 approved（非驳回态）→ 400
    review.resolve(rid_pending, "approved", "qc01", "ok")
    r = _submit_qc(c, doc, resubmit_of=rid_pending)
    assert r.status_code == 400


# ---------- 正常链：attempt 递增 + 不合格自动升级 ----------

def test_resubmit_chain_attempt_increments_and_escalates(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    # 第 1 次提交：默认路径入队人工（attempt=1 / resubmit_of=None / meta 留档原病历）
    r1 = _submit_qc(c, doc)
    d1 = r1.json()
    assert d1["status"] == "ok" and d1["needs_human_review"] is True
    rid1 = d1["review_id"]
    item1 = review.get(rid1)
    assert item1["attempt"] == 1 and item1["resubmit_of"] is None
    assert item1["meta"]["record"]["主诉"] == FULL_RECORD["主诉"]  # 原病历留档（预填数据源）
    review.resolve(rid1, "rejected", "qc01", "内涵缺陷未修复")
    # 第 2 次提交（重提）：attempt=2，不合格 → 自动升级 rejected_escalated（进人工终审）
    r2 = _submit_qc(c, doc, resubmit_of=rid1)
    assert r2.status_code == 200, r2.text
    d2 = r2.json()
    assert d2["status"] == "rejected_escalated"
    assert d2["needs_human_review"] is True and d2["review_id"]  # escalated 永远进人工
    assert "已自动升级病案科复核" in d2["answer"]
    rid2 = d2["review_id"]
    item2 = review.get(rid2)
    assert item2["resubmit_of"] == rid1 and item2["attempt"] == 2
    # 审计 qc_escalated（attempt=2）
    esc = [e for e in audit.entries if e["payload"].get("action") == "qc_escalated"]
    assert esc and esc[-1]["payload"]["attempt"] == 2
    assert esc[-1]["payload"]["resubmit_of"] == rid1
    # 第 3 次提交（链式重提）：attempt 从原记录链读取并 +1 → 3
    review.resolve(rid2, "rejected", "qc01", "仍不合格")
    r3 = _submit_qc(c, doc, resubmit_of=rid2)
    assert r3.status_code == 200, r3.text
    item3 = review.get(r3.json()["review_id"])
    assert item3["attempt"] == 3 and item3["resubmit_of"] == rid2


# ---------- escalated 优先于 auto_pass ----------

def test_escalated_overrides_auto_pass(monkeypatch, tmp_path):
    """attempt>=2 不合格：QC_AUTO_PASS=True 也永不自动归档，直接升级进人工。"""
    c, audit = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_pass", True)
    doc = _login(c, "doctor01")
    r1 = _submit_qc(c, doc)
    rid1 = r1.json()["review_id"]
    review.resolve(rid1, "rejected", "qc01", "驳回")
    r2 = _submit_qc(c, doc, resubmit_of=rid1)
    d2 = r2.json()
    assert d2["status"] == "rejected_escalated"            # 非 auto_pass 归档
    assert d2["needs_human_review"] is True and d2["review_id"]
    assert len(review.pending()) == 1                       # 已进入人工队列
    # 合格重提（无缺陷）不受升级影响：auto_pass 正常归档（升级仅针对不合格）
    monkeypatch.setattr(qc_mod, "connotation_check", lambda record: [])
    r3 = _submit_qc(c, doc, resubmit_of=rid1)
    d3 = r3.json()
    assert d3["status"] == "ok" and d3["review_id"] is None
    assert "✅" in d3["answer"]


def test_hard_defect_prefilter_stays_before_escalation(monkeypatch, tmp_path):
    """确定性硬伤预筛仍在升级判定之前：attempt>=2 硬伤仍即时自动驳回不入队
    （不入队故无升级对象；医生立即拿到确定性修复反馈）。"""
    c, _ = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_pass", True)
    doc = _login(c, "doctor01")
    r1 = _submit_qc(c, doc)
    rid1 = r1.json()["review_id"]
    review.resolve(rid1, "rejected", "qc01", "驳回")
    rec = dict(FULL_RECORD)
    rec.pop("主诉")  # 确定性硬伤：主诉缺失
    r2 = c.post("/api/v1/medical/qc/record", headers=_h(doc),
                json={"record": rec, "resubmit_of": rid1})
    assert r2.status_code == 200, r2.text
    d2 = r2.json()
    assert d2["status"] == "auto_rejected" and d2["review_id"] is None
    assert review.pending() == []


# ---------- my-rejections：重提链置顶 + 原病历预填数据 ----------

def test_my_rejections_includes_resubmit_thread_and_prefill(monkeypatch, tmp_path):
    """重提提交后新条目置顶显示在该卡片第一条（待复核 + 第 N 次提交元数据）；
    被驳回原记录 meta 携带原病历文本（前端「重新提交」预填数据源）。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    r1 = _submit_qc(c, doc)
    rid1 = r1.json()["review_id"]
    review.resolve(rid1, "rejected", "qc01", "内涵缺陷")
    r2 = _submit_qc(c, doc, resubmit_of=rid1)
    rid2 = r2.json()["review_id"]
    items = c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc)).json()["items"]
    ids = [i["id"] for i in items]
    assert ids[0] == rid2            # 重提新条目置顶（新→旧）
    assert rid1 in ids
    top = items[0]
    assert top["status"] == "pending" and top["attempt"] == 2 and top["resubmit_of"] == rid1
    rej = next(i for i in items if i["id"] == rid1)
    assert rej["status"] == "rejected" and rej["attempt"] == 1
    assert rej["meta"]["record"]["主诉"] == FULL_RECORD["主诉"]      # 原病历文本（预填）
    assert rej["meta"]["record"]["现病史"] == FULL_RECORD["现病史"]
    assert rej["review_note"] == "内涵缺陷"
