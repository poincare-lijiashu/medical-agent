"""智能开药阶段2.1+2.2 测试：处方状态机/repo 往返 + 字典约束 LLM suggest。

- 2.1：状态机合法/非法流转（非法一律 ValueError）、repo JSON 往返（文件隔离 monkeypatch）、
  list_mine/list_pending/stats；PG 往返与迁移见 test_pg_repo.py（照既有模式补）。
- 2.2：POST /prescriptions/suggest——字典外药丢弃（幻觉=0）、华法林+布洛芬高危 blocked、
  中危 warnings、审计 prescription.suggested、case_text 422、响应携带 DISCLAIMER；
  LLM 经 medical_router.get_llm 模块级符号 monkeypatch（fake 不触网）。
"""
import json
import os

import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import drug_dict as dd
from backend.core import prescriptions as rx
from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

CASE = "患者女，68 岁，高血压合并房颤，需长期抗凝管理，近期膝关节疼痛明显，评估用药方案。"

# 字典/规则种子（文件隔离，绝不读真实 data/drug_*.json）
_DICT = [
    {"name": "华法林", "aliases": ["warfarin"], "brand_names": ["可密达"],
     "category": "抗凝抗栓", "level": "处方药"},
    {"name": "布洛芬", "aliases": [], "brand_names": [], "category": "解热镇痛",
     "level": "OTC"},
]
_RULES_HIGH = [{"drug_a": "华法林", "drug_b": "布洛芬", "severity": "高危",
                "mechanism": "NSAID 增加出血风险", "management": "避免联用",
                "source": "test"}]
_RULES_MID = [{"drug_a": "华法林", "drug_b": "布洛芬", "severity": "中危",
               "mechanism": "可能轻度影响 INR", "management": "加强监测", "source": "test"}]


class _FakeMsg:
    def __init__(self, content):
        self.content = content


class _FakeLLM:
    """同步 fake：invoke 返回 content=预设 JSON 文本（与真实 AIMessage 同形）。"""

    def __init__(self, content):
        self._content = content
        self.prompts = []

    def invoke(self, messages):
        self.prompts.append(messages)
        return _FakeMsg(self._content)


@pytest.fixture()
def rx_env(tmp_path, monkeypatch):
    """域测试环境：处方文件隔离 + 纯 JSON 模式（无 PG 池）。"""
    monkeypatch.setattr(rx, "PRESCRIPTIONS_FILE", str(tmp_path / "prescriptions.json"))
    monkeypatch.setattr(pg_store, "_pool", None)
    return tmp_path


def _seed_dict(monkeypatch, tmp_path, rules):
    """药品字典/规则文件隔离 + 失效缓存（teardown 在 monkeypatch 恢复真实路径前清缓存，
    下个用例重读真源，绝不污染其它测试）。"""
    dfile = tmp_path / "drug_dict.json"
    rfile = tmp_path / "drug_rules.json"
    dfile.write_text(json.dumps(_DICT, ensure_ascii=False), encoding="utf-8")
    rfile.write_text(json.dumps(rules, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(dd, "DRUG_DICT_FILE", str(dfile))
    monkeypatch.setattr(dd, "DRUG_RULES_FILE", str(rfile))
    dd.invalidate()


@pytest.fixture()
def suggest_env(tmp_path, monkeypatch):
    """suggest 端点环境：账号/审计/字典隔离 + 处方文件隔离（验证高危不落库）。"""
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger",
                        lambda: audit)
    monkeypatch.setattr(rx, "PRESCRIPTIONS_FILE", str(tmp_path / "prescriptions.json"))
    _seed_dict(monkeypatch, tmp_path, _RULES_HIGH)
    yield TestClient(app), audit, tmp_path
    dd.invalidate()  # 清缓存（此刻 monkeypatch 尚未恢复真实路径，路径恢复后下次读重载）


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- 阶段2.1：状态机 + 非法流转 ----

def test_state_machine_happy_path(rx_env):
    """create → pending_pharm；approve/reject 合法流转并留痕 reviewer/opinion/审核时间。"""
    r1 = rx.create(doctor="doctor01", dept="内科", case_text=CASE,
                   drugs=[{"name": "华法林", "dose": "2.5mg", "freq": "qd"}])
    assert r1["id"].startswith("rx-") and r1["status"] == "pending_pharm"
    assert r1["drugs"][0]["note"] == "" and r1["forced_high_risk"] is False
    assert r1["pharm_reviewer"] is None and r1["pharm_opinion"] is None
    assert r1["pharm_reviewed_at"] is None  # 开药审核 UX 问题3：审核时间随签发/驳回落值
    ok = rx.approve(r1["id"], "pharm01", "相互作用已核对，同意")
    assert ok["status"] == "approved" and ok["pharm_reviewer"] == "pharm01"
    assert ok["pharm_opinion"] == "相互作用已核对，同意"
    assert ok["pharm_reviewed_at"], "签发须留审核操作时间（pharm_reviewed_at）"
    r2 = rx.create(doctor="doctor01", case_text=CASE,
                   drugs=[{"name": "华法林"}], contraindication_reason="test",
                   forced_high_risk=True)
    rej = rx.reject(r2["id"], "pharm01", "高危联用，驳回")
    assert rej["status"] == "rejected" and rej["pharm_reviewer"] == "pharm01"
    assert rej["pharm_reviewed_at"], "驳回须留审核操作时间（pharm_reviewed_at）"
    assert rej["forced_high_risk"] is True


def test_illegal_transitions_raise_valueerror(rx_env):
    """非法流转一律 ValueError：终态再签发、跨态改写/提交、未态直签、不存在处方。"""
    r = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    with pytest.raises(ValueError):  # pending_pharm 不能直接 draft（仅 rejected 可改写）
        rx.rewrite(r["id"], "doctor01", case_text=CASE)
    rx.approve(r["id"], "pharm01", "ok")
    with pytest.raises(ValueError):  # approved 为终态：再签发拒绝
        rx.approve(r["id"], "pharm01")
    with pytest.raises(ValueError):  # approved 不能驳回
        rx.reject(r["id"], "pharm01")
    with pytest.raises(ValueError):  # approved 不能改写
        rx.rewrite(r["id"], "doctor01", case_text=CASE)
    with pytest.raises(ValueError):  # 不存在的处方
        rx.approve("rx-nope", "pharm01")
    r2 = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    rx.reject(r2["id"], "pharm01", "剂量存疑")
    with pytest.raises(ValueError):  # rejected 不能直接再签发（须先 rewrite 回 draft）
        rx.approve(r2["id"], "pharm01")
    with pytest.raises(ValueError):  # rejected 不能直接 submit（仅 draft 可送审）
        rx.submit(r2["id"], "doctor01")
    with pytest.raises(ValueError):  # rejected 不能直接 reject
        rx.reject(r2["id"], "pharm01")
    rx.rewrite(r2["id"], "doctor01", case_text=CASE)  # rejected → draft
    with pytest.raises(ValueError):  # draft 不能直接 approve（须先 submit 送审）
        rx.approve(r2["id"], "pharm01")


def test_rewrite_rejected_to_draft_owner_only(rx_env):
    """rejected →（rewrite，仅本人）→ draft →（submit）→ pending_pharm 闭环。"""
    r = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    rx.reject(r["id"], "pharm01", "联用高危")
    with pytest.raises(ValueError):  # 非本人不可改写
        rx.rewrite(r["id"], "doctor02", case_text=CASE)
    new_case = "改写后的病例：停用布洛芬，改对乙酰氨基酚镇痛，继续抗凝随访。"
    d = rx.rewrite(r["id"], "doctor01", case_text=new_case,
                   drugs=[{"name": "华法林"}, {"name": "对乙酰氨基酚"}])
    assert d["status"] == "draft" and d["case_text"] == new_case
    assert len(d["drugs"]) == 2
    s = rx.submit(r["id"], "doctor01")
    assert s["status"] == "pending_pharm" and s["pharm_reviewer"] is None
    assert s["pharm_reviewed_at"] is None, "重提清空上一轮审核留痕（reviewer/opinion/时间）"


def test_domain_validation_errors(rx_env):
    """域校验：case_text 超长/空、无有效药品、空医生。"""
    with pytest.raises(ValueError):
        rx.create(doctor="doctor01", case_text="超" * 5001, drugs=[{"name": "华法林"}])
    with pytest.raises(ValueError):
        rx.create(doctor="doctor01", case_text="   ", drugs=[{"name": "华法林"}])
    with pytest.raises(ValueError):
        rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "  "}])
    with pytest.raises(ValueError):
        rx.create(doctor="", case_text=CASE, drugs=[{"name": "华法林"}])
    with pytest.raises(ValueError):
        rx.approve("rx-x", "  ")


def test_lists_and_stats(rx_env):
    """list_mine 仅本人（新→旧）；list_pending 仅 pending_pharm；stats 状态分布。"""
    a = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    b = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    c = rx.create(doctor="doctor02", case_text=CASE, drugs=[{"name": "华法林"}])
    rx.approve(b["id"], "pharm01")
    mine = rx.list_mine("doctor01")
    assert [i["id"] for i in mine] == [b["id"], a["id"]]  # 新→旧
    assert all(i["doctor"] == "doctor01" for i in mine)
    # 待核清单（新→旧）：doctor02 的 c 与未签发的 a（b 已 approved 出队）
    assert [i["id"] for i in rx.list_pending()] == [c["id"], a["id"]]
    st = rx.stats()
    assert st == {"total": 3, "draft": 0, "pending_pharm": 2, "approved": 1, "rejected": 0}


def test_repo_roundtrip_json_file(rx_env):
    """repo 往返（文件隔离）：写后 JSON 兜底文件落盘、重读一致（PG 不可用纯 JSON）。"""
    r = rx.create(doctor="doctor01", dept="内科", case_text=CASE,
                  drugs=[{"name": "华法林", "dose": "2.5mg", "freq": "qd", "note": "n"}])
    f = rx_env / "prescriptions.json"
    raw = json.loads(f.read_text(encoding="utf-8"))
    assert [i["id"] for i in raw] == [r["id"]]
    again = rx.get(r["id"])
    assert again == r  # 往返字段无损
    assert again["drugs"] == [{"name": "华法林", "dose": "2.5mg", "freq": "qd", "note": "n"}]


# ---- 阶段2.2：suggest 端点 ----

def test_suggest_drops_out_of_dict_drug(suggest_env, monkeypatch):
    """字典外药名经硬校验丢弃（幻觉=0），且按别名图归一为规范名。"""
    c, audit, _tmp = suggest_env
    fake = _FakeLLM(json.dumps([
        {"name": "warfarin", "dose": "2.5mg", "freq": "qd", "reason": "抗凝"},
        {"name": "神仙速效散", "dose": "1粒", "freq": "tid", "reason": "幻觉药"}],
        ensure_ascii=False))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_llm",
                        lambda *a, **k: fake)
    doc = _login(c, "doctor01")
    r = c.post("/api/v1/medical/prescriptions/suggest", headers=_h(doc),
               json={"case_text": CASE})
    assert r.status_code == 200, r.text
    d = r.json()
    names = [s["name"] for s in d["suggestions"]]
    assert names == ["华法林"], "别名归一 + 幻觉药丢弃"
    assert not any("神仙" in n for n in names), "幻觉=0：字典外药绝不透出"
    assert d["blocked"] is False and d["conflicts"] == []
    assert d["disclaimer"] == dd.DISCLAIMER
    # prompt 注入了候选池（名称+类别）
    system = fake.prompts[0][0].content
    assert "华法林（抗凝抗栓）" in system and "只能从下方候选药品清单" in system


def test_suggest_warfarin_ibuprofen_blocked_no_persist(suggest_env, monkeypatch):
    """华法林+布洛芬 → 高危 blocked=true + conflicts，不落库（不产生处方记录）。"""
    c, audit, tmp = suggest_env
    fake = _FakeLLM(json.dumps([
        {"name": "华法林", "dose": "2.5mg", "freq": "qd", "reason": "抗凝"},
        {"name": "布洛芬", "dose": "0.3g", "freq": "bid", "reason": "镇痛"}],
        ensure_ascii=False))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_llm",
                        lambda *a, **k: fake)
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/prescriptions/suggest", headers=_h(doc),
               json={"case_text": CASE}).json()
    assert d["blocked"] is True
    cf = d["conflicts"]
    assert len(cf) == 1 and set(cf[0]["pair"]) == {"华法林", "布洛芬"}
    assert cf[0]["severity"] == "高危" and "出血" in cf[0]["note"]
    assert d["warnings"] == []
    assert not os.path.isfile(tmp / "prescriptions.json"), "高危建议不落库"


def test_suggest_mid_risk_yields_warnings(suggest_env, monkeypatch, tmp_path):
    """中危规则命中 → blocked=false，warnings 附响应。"""
    c, _audit, _tmp = suggest_env
    _seed_dict(monkeypatch, tmp_path, _RULES_MID)  # 同 tmp_path 实例（pytest 函数级缓存）
    fake = _FakeLLM(json.dumps([
        {"name": "华法林", "dose": "2.5mg", "freq": "qd", "reason": "抗凝"},
        {"name": "布洛芬", "dose": "0.3g", "freq": "bid", "reason": "镇痛"}],
        ensure_ascii=False))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_llm",
                        lambda *a, **k: fake)
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/prescriptions/suggest", headers=_h(doc),
               json={"case_text": CASE}).json()
    assert d["blocked"] is False and d["conflicts"] == []
    assert len(d["warnings"]) == 1 and d["warnings"][0]["severity"] == "中危"


def test_suggest_audited_and_role_guarded(suggest_env, monkeypatch):
    """审计 prescription.suggested（actor=医生）；pharmacist 无 suggest 权限（403）。"""
    c, audit, _tmp = suggest_env
    fake = _FakeLLM(json.dumps(
        [{"name": "华法林", "dose": "2.5mg", "freq": "qd", "reason": "抗凝"}],
        ensure_ascii=False))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_llm",
                        lambda *a, **k: fake)
    doc = _login(c, "doctor01")
    c.post("/api/v1/medical/prescriptions/suggest", headers=_h(doc),
           json={"case_text": CASE})
    hits = [e for e in audit.entries if e["event_type"] == "prescription"
            and e["payload"].get("action") == "suggested"]
    assert hits and hits[-1]["actor"] == "doctor01"
    assert hits[-1]["payload"]["n"] == 1 and hits[-1]["payload"]["blocked"] is False
    ph = _login(c, "pharm01")
    assert c.post("/api/v1/medical/prescriptions/suggest", headers=_h(ph),
                  json={"case_text": CASE}).status_code == 403


def test_suggest_case_text_length_422(suggest_env):
    """case_text <20 字 → pydantic 422（20-5000 边界）。"""
    c, _audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    r = c.post("/api/v1/medical/prescriptions/suggest", headers=_h(doc),
               json={"case_text": "太短的病例"})
    assert r.status_code == 422


# ---- 阶段2.3：处方流转端点（提交 / 列表 / 审核双控 / 改写重提）----

SUB = "/api/v1/medical/prescriptions"


def _submit(c, tok, drugs, reason="", case_text=CASE):
    """医生提交开药的公共请求体构造（缺省无禁忌理由）。"""
    return c.post(SUB, headers=_h(tok),
                  json={"case_text": case_text, "drugs": drugs,
                        "contraindication_reason": reason})


def _seed_doctor2():
    """补第二名医生账号（跨医生数据隔离 / 角色拒绝用例）。"""
    auth_mod.create_user("doctor02", "Med@2026x", "doctor", dept="内科")


def test_submit_rx_ok_and_audited(suggest_env):
    """doctor 提交开药 200 → pending_pharm；审计 prescription.submitted（payload 含 role/channel=web/药数）。"""
    c, audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    r = _submit(c, doc, [{"name": "华法林", "dose": "2.5mg", "freq": "qd"}])
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["id"].startswith("rx-") and d["status"] == "pending_pharm"
    assert d["doctor"] == "doctor01" and d["forced_high_risk"] is False
    hits = [e for e in audit.entries if e["event_type"] == "prescription"
            and e["payload"].get("action") == "submitted"]
    assert hits and hits[-1]["actor"] == "doctor01"
    assert hits[-1]["payload"]["n"] == 1 and hits[-1]["payload"]["rid"] == d["id"]
    assert hits[-1]["payload"]["channel"] == "web" and hits[-1]["payload"]["role"] == "doctor"
    assert hits[-1]["payload"]["forced_with_reason"] is False
    # 我的处方列表可见（只本人）
    mine = c.get(f"{SUB}/mine", headers=_h(doc))
    assert mine.status_code == 200
    assert [i["id"] for i in mine.json()["items"]] == [d["id"]]
    assert mine.json()["me"] == "doctor01"


def test_submit_high_risk_without_reason_422(suggest_env):
    """服务端再校验（不信任前端）：高危组合（华法林×布洛芬）无理由直提 → 422 且不落库、不留痕。"""
    c, audit, tmp = suggest_env
    doc = _login(c, "doctor01")
    r = _submit(c, doc, [{"name": "华法林"}, {"name": "布洛芬", "dose": "0.3g"}])
    assert r.status_code == 422
    assert "高危" in r.json()["detail"]
    assert not os.path.isfile(tmp / "prescriptions.json"), "校验拒绝不落库"
    assert not [e for e in audit.entries
                if e["payload"].get("action") == "submitted"], "拒绝不留 submitted 审计"


def test_submit_high_risk_with_reason_forced(suggest_env):
    """高危组合附禁忌理由 → 200 + forced_high_risk=true；审计 submitted 含 forced_with_reason=true，
    另留 prescription.forced_with_reason 独立事件（药师重点核对）。"""
    c, audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    reason = "病情必需，已告知出血风险并加强 INR 监测"
    r = _submit(c, doc, [{"name": "华法林"}, {"name": "布洛芬"}], reason=reason)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["forced_high_risk"] is True and d["contraindication_reason"] == reason
    sub = [e for e in audit.entries
           if e["payload"].get("action") == "submitted"][-1]
    assert sub["payload"]["forced_with_reason"] is True
    fw = [e for e in audit.entries if e["event_type"] == "prescription"
          and e["payload"].get("action") == "forced_with_reason"]
    assert fw and fw[-1]["actor"] == "doctor01" and fw[-1]["payload"]["rid"] == d["id"]
    assert "出血" in fw[-1]["payload"]["reason"]
    assert fw[-1]["payload"]["channel"] == "web"


def test_submit_server_side_revalidation_dict_unknown(suggest_env):
    """服务端硬校验第二道：字典外药名（幻觉）绕过前端直接提交 → 422 拦截、不落库。"""
    c, _audit, tmp = suggest_env
    doc = _login(c, "doctor01")
    r = _submit(c, doc, [{"name": "华法林"}, {"name": "神仙速效散"}])
    assert r.status_code == 422
    assert "字典" in r.json()["detail"]
    assert not os.path.isfile(tmp / "prescriptions.json")


def test_review_approve_ok_and_audited(suggest_env):
    """pharmacist approve 200 → approved + 留痕 reviewer/opinion；审计 prescription.approved（opinion/药数）。"""
    c, audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    ph = _login(c, "pharm01")
    r = c.post(f"{SUB}/{rid}/review", headers=_h(ph),
               json={"action": "approve", "opinion": "相互作用已核对，同意"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "approved" and d["pharm_reviewer"] == "pharm01"
    hit = [e for e in audit.entries if e["payload"].get("action") == "approved"][-1]
    assert hit["actor"] == "pharm01" and hit["payload"]["rid"] == rid
    assert hit["payload"]["n"] == 1 and "核对" in hit["payload"]["opinion"]
    assert hit["payload"]["channel"] == "web" and hit["payload"]["role"] == "pharmacist"


def test_review_reject_requires_opinion_then_rejects(suggest_env):
    """驳回意见必填（空白 → 422）；附意见驳回 → 200 rejected + 审计 prescription.rejected。"""
    c, audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    ph = _login(c, "pharm01")
    r = c.post(f"{SUB}/{rid}/review", headers=_h(ph),
               json={"action": "reject", "opinion": "   "})
    assert r.status_code == 422
    r2 = c.post(f"{SUB}/{rid}/review", headers=_h(ph),
                json={"action": "reject", "opinion": "剂量超说明书上限，请调整"})
    assert r2.status_code == 200 and r2.json()["status"] == "rejected"
    hit = [e for e in audit.entries if e["payload"].get("action") == "rejected"][-1]
    assert hit["actor"] == "pharm01" and "剂量" in hit["payload"]["opinion"]


def test_review_double_control_self_403(suggest_env):
    """双控：提交人本人审核自己的处方 → 403，且处方状态不被改变。"""
    c, _audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    r = c.post(f"{SUB}/{rid}/review", headers=_h(doc), json={"action": "approve"})
    assert r.status_code == 403
    assert rx.get(rid)["status"] == "pending_pharm", "双控拒绝不改变状态"


def test_review_doctor_role_403(suggest_env):
    """doctor 无审核签字权（403）：用另一医生的处方规避双控干扰，纯角色拒绝。"""
    c, _audit, _tmp = suggest_env
    _seed_doctor2()
    d2 = _login(c, "doctor02", "Med@2026x")
    rid = _submit(c, d2, [{"name": "华法林"}]).json()["id"]
    doc = _login(c, "doctor01")
    assert c.post(f"{SUB}/{rid}/review", headers=_h(doc),
                  json={"action": "approve"}).status_code == 403


def test_review_unknown_rx_404(suggest_env):
    """审核不存在的处方 → 404。"""
    c, _audit, _tmp = suggest_env
    ph = _login(c, "pharm01")
    assert c.post(f"{SUB}/rx-nope/review", headers=_h(ph),
                  json={"action": "approve"}).status_code == 404


def test_review_draft_status_400(suggest_env):
    """draft 状态（域层改写未重提）调 approve → 400（域层状态机拒绝非法流转）。"""
    c, _audit, _tmp = suggest_env
    r = rx.create(doctor="doctor01", case_text=CASE, drugs=[{"name": "华法林"}])
    rx.reject(r["id"], "pharm01", "依据不足")
    rx.rewrite(r["id"], "doctor01", case_text=CASE)  # → draft（未重提）
    ph = _login(c, "pharm01")
    resp = c.post(f"{SUB}/{r['id']}/review", headers=_h(ph),
                  json={"action": "approve", "opinion": "ok"})
    assert resp.status_code == 400


def test_rewrite_owner_resubmit_and_audited(suggest_env):
    """本人改写被驳回处方：更新药品后自动重提（draft→pending_pharm）；审计 prescription.rewritten；
    药师可再次审核（闭环）。"""
    c, audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}, {"name": "布洛芬"}],
                  reason="病情必需").json()["id"]
    ph = _login(c, "pharm01")
    assert c.post(f"{SUB}/{rid}/review", headers=_h(ph),
                  json={"action": "reject", "opinion": "联用高危，请调整"}).status_code == 200
    r = c.post(f"{SUB}/{rid}/rewrite", headers=_h(doc),
               json={"case_text": "改写：停用布洛芬，改外用镇痛，继续抗凝随访。",
                     "drugs": [{"name": "华法林", "dose": "2.5mg", "freq": "qd"}],
                     "contraindication_reason": ""})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["status"] == "pending_pharm" and d["pharm_reviewer"] is None, "draft→再提交自动完成"
    assert [x["name"] for x in d["drugs"]] == ["华法林"]
    hit = [e for e in audit.entries if e["payload"].get("action") == "rewritten"][-1]
    assert hit["actor"] == "doctor01" and hit["payload"]["rid"] == rid
    assert hit["payload"]["n"] == 1 and hit["payload"]["channel"] == "web"
    # 闭环：药师可对新版再次签发
    assert c.post(f"{SUB}/{rid}/review", headers=_h(ph),
                  json={"action": "approve", "opinion": "已调整，同意"}).status_code == 200


def test_rewrite_invalid_content_keeps_rejected_422(suggest_env):
    """改写内容变成高危组合且无理由 → 422，处方保持 rejected（医生可修正后重试，不产生死态）。"""
    c, _audit, _tmp = suggest_env
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    ph = _login(c, "pharm01")
    c.post(f"{SUB}/{rid}/review", headers=_h(ph),
           json={"action": "reject", "opinion": "依据不足"})
    r = c.post(f"{SUB}/{rid}/rewrite", headers=_h(doc),
               json={"drugs": [{"name": "华法林"}, {"name": "布洛芬"}]})
    assert r.status_code == 422
    assert rx.get(rid)["status"] == "rejected", "校验失败保持 rejected（非 draft）"


def test_rewrite_not_owner_403(suggest_env):
    """非本人改写 → 403（改写仅处方医生本人）。"""
    c, _audit, _tmp = suggest_env
    _seed_doctor2()
    doc = _login(c, "doctor01")
    rid = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    ph = _login(c, "pharm01")
    c.post(f"{SUB}/{rid}/review", headers=_h(ph),
           json={"action": "reject", "opinion": "依据不足"})
    d2 = _login(c, "doctor02", "Med@2026x")
    r = c.post(f"{SUB}/{rid}/rewrite", headers=_h(d2), json={"case_text": CASE})
    assert r.status_code == 403
    assert rx.get(rid)["status"] == "rejected"


def test_list_permissions(suggest_env):
    """mine 只回本人处方；pending 队列 pharmacist/qc/admin 三角色可见，doctor 403（role guard）。"""
    c, _audit, _tmp = suggest_env
    _seed_doctor2()
    doc = _login(c, "doctor01")
    d2 = _login(c, "doctor02", "Med@2026x")
    id1 = _submit(c, doc, [{"name": "华法林"}]).json()["id"]
    id2 = _submit(c, d2, [{"name": "华法林"}]).json()["id"]
    mine1 = c.get(f"{SUB}/mine", headers=_h(doc)).json()
    assert [i["id"] for i in mine1["items"]] == [id1]
    mine2 = c.get(f"{SUB}/mine", headers=_h(d2)).json()
    assert [i["id"] for i in mine2["items"]] == [id2]
    for u in ("pharm01", "qc01", "admin01"):
        tok = _login(c, u)
        pend = c.get(f"{SUB}/pending", headers=_h(tok))
        assert pend.status_code == 200, u
        assert {i["id"] for i in pend.json()["items"]} == {id1, id2}, u
    assert c.get(f"{SUB}/pending", headers=_h(doc)).status_code == 403, "doctor 无待审队列权限"
    assert c.get(f"{SUB}/mine", headers=_h(_login(c, "pharm01"))).status_code == 403, \
        "mine 为 doctor 专属"
