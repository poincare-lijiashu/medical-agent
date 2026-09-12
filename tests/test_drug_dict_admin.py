"""阶段1.5：药品字典/规则管理端点（POST /admin/drug/dict、/admin/drug/rules）——TDD 先行测试。

覆盖：权限矩阵（未登录 401 / doctor 403 / pharmacist、admin 双端点可写——问题3 起 dict
由 admin 专属放宽为 pharmacist+admin，药学专业数据药剂科维护）、
add/update/delete 往返（HTTP 可见 + JSON 落盘写透 + save 后进程内缓存即时生效）、
422 校验（name/drug_a/drug_b 必填、severity 枚举 高危|中危、未知 action、目标不存在）、
审计 admin.drug_dict_changed（dict 带 name、rules 带规范药对）、
阶段1.4 前端免责声明文案与后端 DISCLAIMER 同源锁定。

用户表/字典/规则 JSON 与审计均隔离到 tmp_path，不污染真实数据（不连真实 PG，走 JSON 兜底）。
"""
import json
import re
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import drug_dict, pg_store
from backend.core.auth import seed_default_users
from backend.main import app

BASE = Path(__file__).resolve().parents[1]

_DICT = [
    {"name": "华法林", "aliases": ["法华林", "warfarin"], "brand_names": ["可密达"],
     "category": "抗凝抗栓", "level": "处方药"},
    {"name": "布洛芬", "aliases": ["ibuprofen"], "brand_names": ["芬必得"],
     "category": "解热镇痛", "level": "OTC"},
]
_RULES = [
    {"drug_a": "布洛芬", "drug_b": "华法林", "severity": "高危",
     "mechanism": "NSAID 抑制血小板并置换蛋白结合。", "management": "避免联用并监测 INR。",
     "source": "AI辅助生成·待药师核对"},
]


class _Audit:
    """内存审计假件（显式 kwargs 签名，与真实 AuditLog.write 对齐），供断言用。"""

    def __init__(self):
        self.entries = []

    def write(self, event_type="event", action="", actor="system", payload=None, **kw):
        self.entries.append({"event_type": event_type, "action": action,
                             "actor": actor, "payload": dict(payload or {})})

    def recent(self, n=50, offset=0):
        return list(reversed(self.entries))[:n]


@pytest.fixture()
def env(monkeypatch, tmp_path):
    """隔离环境：用户表/字典/规则/审计到 tmp_path；进程内缓存测试前后强制失效。"""
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    df, rf = tmp_path / "drug_dict.json", tmp_path / "drug_rules.json"
    df.write_text(json.dumps(_DICT, ensure_ascii=False), encoding="utf-8")
    rf.write_text(json.dumps(_RULES, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(drug_dict, "DRUG_DICT_FILE", str(df))
    monkeypatch.setattr(drug_dict, "DRUG_RULES_FILE", str(rf))
    audit = _Audit()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger",
                        lambda: audit)
    drug_dict.invalidate()
    yield SimpleNamespace(client=TestClient(app), audit=audit, df=df, rf=rf)
    drug_dict.invalidate()


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


# ---- 权限矩阵：问题3 起 dict=pharmacist+admin（药学专业数据药剂科维护）；rules pharmacist/admin 双角色 ----

def test_drug_dict_manage_permission_matrix(env):
    c = env.client
    D, R = "/api/v1/medical/admin/drug/dict", "/api/v1/medical/admin/drug/rules"
    # 未认证 → 401
    assert c.post(D, json={"action": "add", "item": {"name": "x"}}).status_code == 401
    assert c.post(R, json={"action": "add", "item": {"drug_a": "a", "drug_b": "b"}}).status_code == 401
    doc = _login(c, "doctor01")
    # doctor → 双端点 403
    assert c.post(D, headers=doc, json={"action": "add", "item": {"name": "x"}}).status_code == 403
    assert c.post(R, headers=doc,
                  json={"action": "add", "item": {"drug_a": "a", "drug_b": "b"}}).status_code == 403
    # 问题3 权限放宽（语义变更注明）：药学专业数据由药剂科维护——dict 由 admin 专属放宽为
    # pharmacist+admin（与 rules 同源双角色，admin 保留兜底），原 pharmacist dict 403 断言更新为 200
    ph = _login(c, "pharm01")
    assert c.post(D, headers=ph, json={"action": "add", "item": {"name": "x"}}).status_code == 200
    r = c.post(R, headers=ph, json={"action": "add", "item": {
        "drug_a": "地高辛", "drug_b": "呋塞米", "severity": "中危",
        "mechanism": "利尿致低钾，增强洋地黄毒性。"}})
    assert r.status_code == 200, r.text
    # admin 双端点保留兜底（放宽不收窄）
    admin = _login(c, "admin01")
    assert c.post(D, headers=admin, json={"action": "add", "item": {"name": "y"}}).status_code == 200
    assert c.post(R, headers=admin, json={"action": "add", "item": {
        "drug_a": "a", "drug_b": "b", "severity": "中危"}}).status_code == 200


# ---- 字典 add/update/delete 往返（admin）----

def test_drug_dict_add_update_delete_roundtrip(env):
    from backend.core.drug_dict import DISCLAIMER
    c = env.client
    H = _login(c, "admin01")
    D = "/api/v1/medical/admin/drug/dict"
    item = {"name": "氯吡格雷", "aliases": ["clopidogrel"], "brand_names": ["波立维"],
            "category": "抗凝抗栓", "level": "处方药"}
    # add
    assert c.post(D, headers=H, json={"action": "add", "item": item}).status_code == 200
    # 重名 422
    assert c.post(D, headers=H, json={"action": "add", "item": dict(item)}).status_code == 422
    # GET 可见（含免责声明下发）+ stats 同步
    d = c.get("/api/v1/medical/drug/dict", headers=H).json()
    assert any(x["name"] == "氯吡格雷" for x in d["drugs"])
    assert d["disclaimer"] == DISCLAIMER
    assert d["stats"]["drugs"] == 3
    # save 后缓存即时生效（未手动 invalidate，进程内 load 反映变更）
    assert any(x["name"] == "氯吡格雷" for x in drug_dict.load_dict())
    # 落盘写透（JSON 兜底文件）
    assert "氯吡格雷" in [i["name"] for i in json.loads(env.df.read_text(encoding="utf-8"))]
    # update（按 name 定位、整体替换）
    assert c.post(D, headers=H,
                  json={"action": "update", "item": dict(item, category="抗血小板")}).status_code == 200
    got = next(x for x in c.get("/api/v1/medical/drug/dict", headers=H).json()["drugs"]
               if x["name"] == "氯吡格雷")
    assert got["category"] == "抗血小板"
    # delete + 删除后不存在再删 → 422
    assert c.post(D, headers=H, json={"action": "delete", "item": {"name": "氯吡格雷"}}).status_code == 200
    d = c.get("/api/v1/medical/drug/dict", headers=H).json()
    assert all(x["name"] != "氯吡格雷" for x in d["drugs"]) and d["stats"]["drugs"] == 2
    assert c.post(D, headers=H, json={"action": "delete", "item": {"name": "氯吡格雷"}}).status_code == 422


# ---- 规则 add/update/delete 往返（pharmacist）----

def test_drug_rules_add_update_delete_roundtrip(env):
    c = env.client
    H = _login(c, "pharm01")
    R = "/api/v1/medical/admin/drug/rules"
    # add：乱序药对 → 规范序（_norm_pair，与输入顺序无关）落盘
    assert c.post(R, headers=H, json={"action": "add", "item": {
        "drug_a": "呋塞米", "drug_b": "华法林", "severity": "中危",
        "mechanism": "利尿致低钾，增强华法林效应。", "management": "监测 INR 与血钾。"}}).status_code == 200
    a, b = pg_store._norm_pair("呋塞米", "华法林")
    row = next(x for x in json.loads(env.rf.read_text(encoding="utf-8"))
               if (x["drug_a"], x["drug_b"]) == (a, b))
    assert row["severity"] == "中危"
    # add 重复药对（换序提交）→ 422
    assert c.post(R, headers=H, json={"action": "add", "item": {
        "drug_a": "华法林", "drug_b": "呋塞米", "severity": "高危"}}).status_code == 422
    # update（按药对定位，乱序亦可）
    assert c.post(R, headers=H, json={"action": "update", "item": {
        "drug_a": "华法林", "drug_b": "呋塞米", "severity": "高危",
        "mechanism": "修订。", "management": "避免联用。"}}).status_code == 200
    row = next(x for x in json.loads(env.rf.read_text(encoding="utf-8"))
               if (x["drug_a"], x["drug_b"]) == (a, b))
    assert row["severity"] == "高危"
    # delete
    assert c.post(R, headers=H, json={"action": "delete",
                                      "item": {"drug_a": "呋塞米", "drug_b": "华法林"}}).status_code == 200
    assert all((x["drug_a"], x["drug_b"]) != (a, b)
               for x in json.loads(env.rf.read_text(encoding="utf-8")))


# ---- 422 校验 ----

def test_drug_dict_manage_422_validation(env):
    c = env.client
    H, ph = _login(c, "admin01"), _login(c, "pharm01")
    D, R = "/api/v1/medical/admin/drug/dict", "/api/v1/medical/admin/drug/rules"
    # 未知 action / name 必填（空白）/ item 缺失（FastAPI 校验）/ update 目标不存在
    assert c.post(D, headers=H, json={"action": "upsert", "item": {"name": "x"}}).status_code == 422
    assert c.post(D, headers=H, json={"action": "add", "item": {"name": "  "}}).status_code == 422
    assert c.post(D, headers=H, json={"action": "add"}).status_code == 422
    assert c.post(D, headers=H,
                  json={"action": "update", "item": {"name": "不存在的药"}}).status_code == 422
    # 规则：drug_b 缺失 / 同药成对 / severity 非枚举（高危|中危 之外）/ 未知 action
    assert c.post(R, headers=ph, json={"action": "add", "item": {"drug_a": "华法林"}}).status_code == 422
    assert c.post(R, headers=ph, json={"action": "add", "item": {
        "drug_a": "华法林", "drug_b": "华法林", "severity": "中危"}}).status_code == 422
    assert c.post(R, headers=ph, json={"action": "add", "item": {
        "drug_a": "华法林", "drug_b": "呋塞米", "severity": "高危险"}}).status_code == 422
    assert c.post(R, headers=ph, json={"action": "patch", "item": {
        "drug_a": "华法林", "drug_b": "呋塞米", "severity": "中危"}}).status_code == 422


# ---- 审计：admin.drug_dict_changed（dict 带 name / rules 带规范药对）----

def test_drug_dict_manage_audit(env):
    c = env.client
    H, ph = _login(c, "admin01"), _login(c, "pharm01")
    c.post("/api/v1/medical/admin/drug/dict", headers=H,
           json={"action": "add", "item": {"name": "胺碘酮", "category": "抗心律失常"}})
    c.post("/api/v1/medical/admin/drug/dict", headers=H,
           json={"action": "delete", "item": {"name": "胺碘酮"}})
    c.post("/api/v1/medical/admin/drug/rules", headers=ph,
           json={"action": "add", "item": {"drug_a": "胺碘酮", "drug_b": "华法林", "severity": "高危"}})
    ev = [e for e in env.audit.entries
          if e["event_type"] == "admin" and e["action"] == "drug_dict_changed"]
    assert len(ev) == 3
    assert ev[0]["actor"] == "admin01"
    assert ev[0]["payload"] == {"target": "dict", "action": "add", "name": "胺碘酮"}
    assert ev[1]["payload"] == {"target": "dict", "action": "delete", "name": "胺碘酮"}
    a, b = pg_store._norm_pair("胺碘酮", "华法林")
    assert ev[2]["actor"] == "pharm01"
    assert ev[2]["payload"] == {"target": "rules", "action": "add", "drug_a": a, "drug_b": b}


# ---- 阶段1.4：前端免责声明文案与后端同源锁定 + admin 卡/部署文档就位 ----

def test_frontend_disclaimer_matches_backend_and_docs():
    from tests.test_frontend_syntax import _vue_source
    from backend.core.drug_dict import DISCLAIMER
    html = _vue_source()  # 轮4 起：DRUG_DISCLAIMER 位于 frontend-vue/src/constants/copy.js（Vue 源为权威全文）
    m = re.search(r"DRUG_DISCLAIMER =\n  '([^']+)'", html)
    assert m, "前端应定义 DRUG_DISCLAIMER 常量"
    assert m.group(1) == DISCLAIMER, "前端文案须与后端 drug_dict.DISCLAIMER 逐字一致"
    # 阶段3 更新注明：开药工作台（rx 视图）取代 drug 视图，免责声明条随之移至
    # rx 视图（data-testid="rx-disclaimer"），仍复用 DRUG_DISCLAIMER 常量（上方逐字一致性锁定不变）
    assert 'data-testid="rx-disclaimer"' in html, "开药工作台（rx 视图）应有免责声明条"
    assert 'drugDisclaimer' not in html, "drug 视图已删除，旧 chat 免责条锚点不应残留"
    # 阶段4 任务1 语义变更（注明）：admin 数据面板药品字典卡已移除（药品字典管理 UI
    # 只在药剂科审核中心 tab；admin 编辑权限后端不变兜底），原「admin 面板应有药品字典卡」
    # 正向锁改为反向锁（防回退）。
    assert 'drugDictPanel' not in html, "admin 数据面板字典卡应已移除（阶段4 任务1）"
    md = (BASE / "docs" / "DEPLOY.md").read_text(encoding="utf-8")
    assert "药品数据免责声明" in md
