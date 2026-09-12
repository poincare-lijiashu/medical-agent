"""阶段0.1/0.2：我的驳回过滤与预填数据——TDD 先行测试。

0.1：qc 提交留档 meta 含结构化原病历六栏+检验值（前端「重新提交」逐栏直填数据源）。
0.2：my_rejections 只返回 agent=="qc" 的驳回（drug 驳回排除，改走药物助手上下文提示）；
     新增 my_drug_rejections 与 GET /drug/my-rejections 供药物助手视图显示
     「曾被药剂科驳回：{问题}」。

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

FULL_RECORD = {f: f"内容充分填写完整无误·{f}" for f in
               ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")}
_DEFECT = [{"field": "内涵质量", "issue": "诊断缺乏病史支持", "level": "中", "track": "内涵质量"}]


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    # 重提链依赖人工队列状态机（rejected → resubmit），显式关闭留痕模式
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


def _reject_one_qc(c, tok):
    """提交一条 qc 记录并由 qc01 驳回，返回 review_id。"""
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": dict(FULL_RECORD), "labs": {"血钾": "6.8"}})
    assert r.status_code == 200, r.text
    rid = r.json()["review_id"]
    review.resolve(rid, "rejected", "qc01", "内涵缺陷未修复")
    return rid


def _reject_one_drug(submitted_by="doctor01",
                     question="atorvastatin 加 clarithromycin 会怎样"):
    """直接走 review.submit 构造 drug 高危条目并驳回（与 /drug/ask 高危路径同构）。"""
    rid = review.submit(agent="drug", question=question, answer="规则库初筛结果",
                        confidence=0.86, risk_reason="高危相互作用",
                        submitted_by=submitted_by, sources=["drug_rules:curated_v2"])
    review.resolve(rid, "rejected", "pharm01", "联用禁忌，请更换方案")
    return rid


# ---------- 阶段0.1：my_rejections 返回结构化预填字段 ----------

def test_my_rejections_qc_meta_contains_structured_fields(monkeypatch, tmp_path):
    """0.1 验收：my_rejections 返回的 qc 驳回条目 meta 携带结构化原病历
    （六栏逐字段）+ 检验值（labs），供前端 qcResubmit 逐栏 .value 直填。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    rid = _reject_one_qc(c, doc)
    items = c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc)).json()["items"]
    rej = next(i for i in items if i["id"] == rid)
    meta = rej["meta"] or {}
    rec = meta.get("record") or {}
    for f in ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断"):
        assert rec.get(f) == FULL_RECORD[f], f"meta.record 缺结构化字段：{f}"
    assert (meta.get("labs") or {}).get("血钾") == "6.8", "meta.labs 缺检验值"
    assert rej["review_note"] == "内涵缺陷未修复"


# ---------- 阶段0.2：my_rejections 只返回 qc 驳回 ----------

def test_my_rejections_excludes_drug_agent(monkeypatch, tmp_path):
    """0.2：drug 驳回不得出现在 my_rejections（qc 驳回仍在）。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    qc_rid = _reject_one_qc(c, doc)
    drug_rid = _reject_one_drug()
    ids = [i["id"] for i in
           c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc)).json()["items"]]
    assert qc_rid in ids, "qc 驳回必须保留在 my_rejections"
    assert drug_rid not in ids, "drug 驳回必须被过滤（改走药物助手上下文）"
    assert all((i.get("agent") or "qc") == "qc" for i in
               c.get("/api/v1/medical/qc/my-rejections", headers=_h(doc)).json()["items"])


def test_my_drug_rejections_returns_only_drug(monkeypatch, tmp_path):
    """0.2：my_drug_rejections 只返回本人 drug 驳回（qc 驳回不混入），含问题与驳回原因。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    qc_rid = _reject_one_qc(c, doc)
    drug_rid = _reject_one_drug()
    items = review.my_drug_rejections("doctor01")
    ids = [i["id"] for i in items]
    assert ids == [drug_rid], "仅 drug 驳回（新→旧）"
    it = items[0]
    assert it["agent"] == "drug" and it["question"] and it["review_note"] == "联用禁忌，请更换方案"
    assert qc_rid not in ids
    # 数据隔离：他人看不到
    assert review.my_drug_rejections("qc01") == []


def test_drug_my_rejections_route(monkeypatch, tmp_path):
    """0.2：GET /drug/my-rejections 只返回本人的 drug 驳回条目（供前端药物助手提示）。"""
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    drug_rid = _reject_one_drug()
    _reject_one_qc(c, doc)
    d = c.get("/api/v1/medical/drug/my-rejections", headers=_h(doc)).json()
    assert [i["id"] for i in d["items"]] == [drug_rid]
    assert d["me"] == "doctor01"
    acts = [e for e in audit.entries if e["payload"].get("action") == "my_rejections"
            and e["event_type"] == "drug"]
    assert acts and acts[-1]["payload"]["n"] == 1
