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
    """create → pending_pharm；approve/reject 合法流转并留痕 reviewer/opinion。"""
    r1 = rx.create(doctor="doctor01", dept="内科", case_text=CASE,
                   drugs=[{"name": "华法林", "dose": "2.5mg", "freq": "qd"}])
    assert r1["id"].startswith("rx-") and r1["status"] == "pending_pharm"
    assert r1["drugs"][0]["note"] == "" and r1["forced_high_risk"] is False
    assert r1["pharm_reviewer"] is None and r1["pharm_opinion"] is None
    ok = rx.approve(r1["id"], "pharm01", "相互作用已核对，同意")
    assert ok["status"] == "approved" and ok["pharm_reviewer"] == "pharm01"
    assert ok["pharm_opinion"] == "相互作用已核对，同意"
    r2 = rx.create(doctor="doctor01", case_text=CASE,
                   drugs=[{"name": "华法林"}], contraindication_reason="test",
                   forced_high_risk=True)
    rej = rx.reject(r2["id"], "pharm01", "高危联用，驳回")
    assert rej["status"] == "rejected" and rej["pharm_reviewer"] == "pharm01"
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
