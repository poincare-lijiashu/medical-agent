"""API 层等价 E2E 冒烟（评测行动项 2，无论浏览器版走 A/B 都做）：HTTP 全链路。

链路（复用 tests/test_prescriptions.py 的夹具模式，全部 tmp 隔离、不触真实数据）：
doctor01 登录 → POST /prescriptions（单药阿司匹林，无高危）→ 200 pending_pharm →
pharm01 登录 → GET /prescriptions/pending 含该单 → POST review approve →
200 approved → 审计文件含 prescription.approved。

浏览器真机链路见 tests/e2e/test_smoke_flow.py（环境无 playwright 时自动 skip）；
本文件基于 TestClient（httpx）走 ASGI HTTP，不依赖运行中的服务，CI 稳定资产。
"""
import json
import os

import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import drug_dict as dd
from backend.core import pg_store
from backend.core import prescriptions as rx
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

CASE = "患者男，52 岁，冠心病二级预防随诊，无药物过敏史，肝肾功能正常，拟长期抗血小板治疗。"
SUB = "/api/v1/medical/prescriptions"

# 字典种子：仅含阿司匹林（文件隔离，绝不读写真实 data/drug_dict.json）；无联用规则
_DICT = [{"name": "阿司匹林", "aliases": ["aspirin", "乙酰水杨酸"], "brand_names": ["拜阿司匹灵"],
          "category": "抗凝抗栓", "level": "处方药"}]


@pytest.fixture()
def smoke_env(tmp_path, monkeypatch):
    """冒烟环境：账号/审计/处方/字典四类文件全部 tmp 隔离 + 纯 JSON 模式（无 PG 池）。"""
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()  # doctor01/pharm01 种子账号（口令=AUTH_DEMO_PASSWORD=Med@2026）
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger",
                        lambda: audit)
    monkeypatch.setattr(rx, "PRESCRIPTIONS_FILE", str(tmp_path / "prescriptions.json"))
    monkeypatch.setattr(pg_store, "_pool", None)
    dfile = tmp_path / "drug_dict.json"
    rfile = tmp_path / "drug_rules.json"
    dfile.write_text(json.dumps(_DICT, ensure_ascii=False), encoding="utf-8")
    rfile.write_text("[]", encoding="utf-8")
    monkeypatch.setattr(dd, "DRUG_DICT_FILE", str(dfile))
    monkeypatch.setattr(dd, "DRUG_RULES_FILE", str(rfile))
    dd.invalidate()
    yield TestClient(app), audit, tmp_path
    dd.invalidate()  # teardown 先清缓存，路径恢复后下个用例重读真源（不污染其它测试）


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_api_smoke_doctor_submit_then_pharmacist_approve(smoke_env):
    """全链路 API 冒烟：开单 → pending_pharm → 待审队列 → approve → approved → 审计留痕。"""
    c, audit, tmp = smoke_env

    # ① 医生登录并提交单药处方（200 → pending_pharm）
    doc = _login(c, "doctor01")
    r = c.post(SUB, headers=_h(doc), json={
        "case_text": CASE,
        "drugs": [{"name": "阿司匹林", "dose": "0.1g", "freq": "qd", "note": "字典添加 · 抗凝抗栓"}],
        "contraindication_reason": ""})
    assert r.status_code == 200, r.text
    d = r.json()
    rid = d["id"]
    assert rid.startswith("rx-") and d["status"] == "pending_pharm"
    assert d["doctor"] == "doctor01" and d["forced_high_risk"] is False

    # ② 药师登录：待审队列包含该单（双控前置——提交人≠审核人）
    ph = _login(c, "pharm01")
    pend = c.get(f"{SUB}/pending", headers=_h(ph))
    assert pend.status_code == 200, pend.text
    assert any(i["id"] == rid for i in pend.json()["items"]), "待审队列须包含新提交处方"

    # ③ 通过签发（200 → approved + 审核留痕）
    rev = c.post(f"{SUB}/{rid}/review", headers=_h(ph),
                 json={"action": "approve", "opinion": "单药无相互作用，同意签发"})
    assert rev.status_code == 200, rev.text
    d2 = rev.json()
    assert d2["status"] == "approved" and d2["pharm_reviewer"] == "pharm01"
    assert d2["pharm_reviewed_at"], "签发须留审核时间"
    assert c.get(f"{SUB}/pending", headers=_h(ph)).json()["items"] == [], "签发后出队"

    # ④ 审计文件含 prescription.approved（隔离 tmp 文件，事件 + 落盘双断言）
    hits = [e for e in audit.entries if e["event_type"] == "prescription"
            and e["payload"].get("action") == "approved"]
    assert hits and hits[-1]["actor"] == "pharm01" and hits[-1]["payload"]["rid"] == rid
    audit_file = tmp / "audit.jsonl"
    assert os.path.isfile(audit_file), "审计须落盘（jsonl）"
    raw = audit_file.read_text(encoding="utf-8")
    assert '"approved"' in raw and rid in raw, "审计文件须含 prescription.approved 事件与本单 id"
