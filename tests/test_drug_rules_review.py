"""评测行动项4（工具链部分）：药师审校工作流——规则审校状态/批量标记/覆盖率统计/筛选。

drug_rules.json 318 条规则（高危 135）为 AI 初稿，需执业药师人工审校；本轮只建工具链，
实际医学审校由药师人工执行。覆盖：
- 域函数 mark_reviewed 往返：approved 写入 review_status/reviewed_by/reviewed_at
  （load 与 JSON 落盘均反映）；unreviewed 撤销（三字段移除，回到未审校缺省语义）；
  键为 "drug_a||drug_b" 编码（规范药对，与输入顺序无关）；未知键 ValueError 原子拒绝
  （不做部分写入）；decision 非法 ValueError。
- rules_review_stats 计数：{total, reviewed, unreviewed, high_risk_total,
  high_risk_reviewed}，高危子集口径与既有 stats().high 一致（severity=="高危"）。
- 端点 POST /admin/drug/rules/review（pharmacist+admin）：200 返回新 stats；
  审计 drug_rules.reviewed（count/actor/decision）；doctor 403 / 未认证 401；
  未知键 422（且不落盘）；decision/keys 非法 422。
- GET /drug/dict 响应 stats 并入 review 统计（既有 stats 键不丢）。
- JSON 兜底文件 diff 语义：仅被标记条目被改写，其余条目（含字段顺序）逐字不变——
  不批量改写现有 JSON。
- 前端标记锁（相互作用规则 tab）：审校徽章（已审校绿/未审校灰）/筛选下拉（全部/未审校/
  已审校/未审校高危）/高危默认排前/行勾选框/「标记已审校」批量按钮/统计头覆盖率文案；
  humanizeAudit 补 reviewed 映射（覆盖回归锁配套）。

用户表/字典/规则 JSON 与审计均隔离到 tmp_path，不污染真实数据（不连真实 PG，走 JSON 兜底）。
"""
import json
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
    {"name": "华法林", "aliases": [], "brand_names": [], "category": "抗凝抗栓", "level": "处方药"},
    {"name": "布洛芬", "aliases": [], "brand_names": [], "category": "解热镇痛", "level": "OTC"},
]
# 4 条规则：高危 2 + 中危 2（现有规则无 id 字段，键=(drug_a,drug_b) 规范药对）
_RULES = [
    {"drug_a": "布洛芬", "drug_b": "华法林", "severity": "高危",
     "mechanism": "m1", "management": "g1", "source": "AI辅助生成·待药师核对"},
    {"drug_a": "华法林", "drug_b": "阿司匹林", "severity": "高危",
     "mechanism": "m2", "management": "g2", "source": "curated_v1"},
    {"drug_a": "地高辛", "drug_b": "呋塞米", "severity": "中危",
     "mechanism": "m3", "management": "g3", "source": "AI辅助生成·待药师核对"},
    {"drug_a": "胺碘酮", "drug_b": "地高辛", "severity": "中危",
     "mechanism": "m4", "management": "g4", "source": "curated_v1"},
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


def _rule_on_disk(rf: Path, pair: tuple[str, str]) -> dict:
    """按规范药对从 JSON 兜底文件定位规则条目（保持文件原序语义；入参药对自动归一）。"""
    pair = pg_store._norm_pair(*pair)
    for x in json.loads(rf.read_text(encoding="utf-8")):
        if pg_store._norm_pair(x.get("drug_a"), x.get("drug_b")) == pair:
            return x
    raise AssertionError(f"规则不在文件中：{pair}")


# ---- 域函数：mark_reviewed 往返（approved 写入 / unreviewed 撤销 / 键与顺序无关）----

def test_mark_reviewed_roundtrip(env):
    rf = env.rf
    # ① approved：写入 review_status/reviewed_by/reviewed_at（进程内缓存与 JSON 落盘均反映）
    drug_dict.mark_reviewed(["布洛芬||华法林"], "pharm01", "approved")
    r = next(x for x in drug_dict.load_rules()
             if pg_store._norm_pair(x["drug_a"], x["drug_b"]) ==
             pg_store._norm_pair("布洛芬", "华法林"))
    assert r["review_status"] == "approved"
    assert r["reviewed_by"] == "pharm01"
    assert r["reviewed_at"]  # ISO 时间串非空
    disk = _rule_on_disk(rf, ("布洛芬", "华法林"))
    assert disk["review_status"] == "approved" and disk["reviewed_by"] == "pharm01"
    assert disk["reviewed_at"] == r["reviewed_at"]  # 写透一致（缓存不偷改）
    # ② 键与输入顺序无关（规范药对）：乱序提交同一药对仍可定位
    drug_dict.mark_reviewed(["华法林||阿司匹林"], "pharm01", "approved")
    r2 = next(x for x in drug_dict.load_rules()
              if pg_store._norm_pair(x["drug_a"], x["drug_b"]) ==
              pg_store._norm_pair("阿司匹林", "华法林"))
    assert r2["review_status"] == "approved"
    # ③ unreviewed 撤销：三字段整体移除（回到缺省 unreviewed 语义，不留残字段）
    drug_dict.mark_reviewed(["布洛芬||华法林"], "pharm02", "unreviewed")
    r3 = _rule_on_disk(rf, ("布洛芬", "华法林"))
    assert "review_status" not in r3 and "reviewed_by" not in r3 and "reviewed_at" not in r3
    # ④ 未知键：ValueError 原子拒绝（不做部分写入，已标记状态不受影响）
    with pytest.raises(ValueError):
        drug_dict.mark_reviewed(["不存在A||不存在B", "华法林||阿司匹林"], "pharm01", "approved")
    r4 = _rule_on_disk(rf, ("华法林", "阿司匹林"))
    assert r4["review_status"] == "approved"  # 原子性：批内含未知键则整批拒绝
    # ⑤ decision 非法：ValueError
    with pytest.raises(ValueError):
        drug_dict.mark_reviewed(["布洛芬||华法林"], "pharm01", "rejected")


def test_mark_reviewed_empty_keys_rejected(env):
    with pytest.raises(ValueError):
        drug_dict.mark_reviewed([], "pharm01", "approved")


# ---- 域函数：rules_review_stats 计数（含高危子集，口径与 stats().high 一致）----

def test_rules_review_stats_counts(env):
    st0 = drug_dict.rules_review_stats()
    assert st0 == {"total": 4, "reviewed": 0, "unreviewed": 4,
                   "high_risk_total": 2, "high_risk_reviewed": 0}
    # 标 1 高危 + 1 中危 → reviewed=2，高危子集 reviewed=1
    drug_dict.mark_reviewed(["布洛芬||华法林", "地高辛||呋塞米"], "pharm01", "approved")
    st1 = drug_dict.rules_review_stats()
    assert st1 == {"total": 4, "reviewed": 2, "unreviewed": 2,
                   "high_risk_total": 2, "high_risk_reviewed": 1}
    # 撤销高危 → high_risk_reviewed 回落
    drug_dict.mark_reviewed(["华法林||布洛芬"], "pharm01", "unreviewed")
    st2 = drug_dict.rules_review_stats()
    assert st2["reviewed"] == 1 and st2["high_risk_reviewed"] == 0
    # 高危口径与既有 stats()["high"] 一致（同一 severity 谓词）
    assert drug_dict.rules_review_stats()["high_risk_total"] == drug_dict.stats()["high"]


# ---- 端点：POST /admin/drug/rules/review（pharmacist+admin / 审计 / 422 / stats 返回）----

def test_review_endpoint_permission_matrix(env):
    c = env.client
    R = "/api/v1/medical/admin/drug/rules/review"
    # 未认证 → 401
    assert c.post(R, json={"keys": ["布洛芬||华法林"], "decision": "approved"}).status_code == 401
    doc = _login(c, "doctor01")
    # doctor → 403
    assert c.post(R, headers=doc, json={"keys": ["布洛芬||华法林"],
                                        "decision": "approved"}).status_code == 403
    # pharmacist → 200（批量标记 + 返回新 stats）
    ph = _login(c, "pharm01")
    r = c.post(R, headers=ph, json={"keys": ["布洛芬||华法林", "地高辛||呋塞米"],
                                    "decision": "approved"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] is True
    assert body["stats"]["reviewed"] == 2 and body["stats"]["high_risk_reviewed"] == 1
    # admin 保留兜底（pharmacist+admin 同源双角色）
    admin = _login(c, "admin01")
    r2 = c.post(R, headers=admin, json={"keys": ["布洛芬||华法林"], "decision": "unreviewed"})
    assert r2.status_code == 200 and r2.json()["stats"]["reviewed"] == 1


def test_review_endpoint_audit(env):
    c = env.client
    R = "/api/v1/medical/admin/drug/rules/review"
    ph = _login(c, "pharm01")
    c.post(R, headers=ph, json={"keys": ["布洛芬||华法林", "华法林||阿司匹林"],
                                "decision": "approved"})
    c.post(R, headers=ph, json={"keys": ["布洛芬||华法林"], "decision": "unreviewed"})
    ev = [e for e in env.audit.entries
          if e["event_type"] == "drug_rules" and e["action"] == "reviewed"]
    assert len(ev) == 2
    assert ev[0]["actor"] == "pharm01"
    assert ev[0]["payload"] == {"count": 2, "decision": "approved"}
    assert ev[1]["payload"] == {"count": 1, "decision": "unreviewed"}


def test_review_endpoint_422(env):
    c = env.client
    R = "/api/v1/medical/admin/drug/rules/review"
    ph = _login(c, "pharm01")
    before = env.rf.read_text(encoding="utf-8")
    # 未知键 422（含未知键整批拒绝，不落盘）
    r = c.post(R, headers=ph, json={"keys": ["不存在的药||也没有"], "decision": "approved"})
    assert r.status_code == 422
    # keys 空 / 缺失 / decision 非法 → 422
    assert c.post(R, headers=ph, json={"keys": [], "decision": "approved"}).status_code == 422
    assert c.post(R, headers=ph, json={"decision": "approved"}).status_code == 422
    assert c.post(R, headers=ph, json={"keys": ["布洛芬||华法林"],
                                       "decision": "ok"}).status_code == 422
    # 422 路径不产生任何写透（JSON 文件逐字不变）
    assert env.rf.read_text(encoding="utf-8") == before
    assert drug_dict.rules_review_stats()["reviewed"] == 0


# ---- /drug/dict 响应 stats 并入 review 统计（既有键不丢）----

def test_drug_dict_stats_merged_with_review(env):
    c = env.client
    ph = _login(c, "pharm01")
    drug_dict.mark_reviewed(["布洛芬||华法林"], "pharm01", "approved")
    st = c.get("/api/v1/medical/drug/dict", headers=ph).json()["stats"]
    # 既有键保留（字典/规则规模与分类分布不回退）
    for k in ("drugs", "rules", "high", "mid", "categories", "ai_marked"):
        assert k in st
    # review 统计并入
    assert st["total"] == 4 and st["reviewed"] == 1 and st["unreviewed"] == 3
    assert st["high_risk_total"] == 2 and st["high_risk_reviewed"] == 1


# ---- JSON 兜底文件 diff 语义：只变更标记过的条目，其余逐字不变（不批量改写）----

def test_json_fallback_minimal_diff(env):
    rf = env.rf
    before = json.loads(rf.read_text(encoding="utf-8"))
    drug_dict.mark_reviewed(["布洛芬||华法林"], "pharm01", "approved")
    after = json.loads(rf.read_text(encoding="utf-8"))
    # 条目数与顺序不变
    assert len(after) == len(before) == 4
    assert [(x["drug_a"], x["drug_b"]) for x in after] == \
        [(x["drug_a"], x["drug_b"]) for x in before]
    # 被标记条目：原字段全部保留 + 新增三个审校字段（不重写不丢字段）
    pair = pg_store._norm_pair("布洛芬", "华法林")
    marked = next(x for x in after
                  if pg_store._norm_pair(x["drug_a"], x["drug_b"]) == pair)
    orig = next(x for x in before
                if pg_store._norm_pair(x["drug_a"], x["drug_b"]) == pair)
    for k, v in orig.items():
        assert marked[k] == v, f"被标记条目既有字段被改写：{k}"
    assert set(marked) - set(orig) == {"review_status", "reviewed_by", "reviewed_at"}
    # 其余条目：逐字段逐字不变（diff 语义——未被标记的条目不得被批量改写）
    others_after = [x for x in after
                    if pg_store._norm_pair(x["drug_a"], x["drug_b"]) != pair]
    others_before = [x for x in before
                     if pg_store._norm_pair(x["drug_a"], x["drug_b"]) != pair]
    assert others_after == others_before


# ---- 前端标记锁：徽章/筛选/批量按钮/覆盖率/humanizeAudit reviewed 映射 ----

def test_frontend_review_markers_present():
    """前端标记锁（防回退）：相互作用规则 tab 审校工具链四件套 + 人话映射配套。
    轮4 起断言源 = frontend-vue/src（Vue 源为权威全文；DrugRulesEditor.vue + utils/audit.js）。"""
    from tests.test_frontend_syntax import _all_frontend_js, _vue_source, _extract_fn
    html = _vue_source()
    for marker in (
        # 统计头覆盖率 chip（如「审校 42/318 · 高危 12/135」）
        'data-testid="drug-coverage"',
        "审校 {{ st.reviewed ?? 0 }}/{{ st.total ?? rules.length }} · 高危 {{ st.high_risk_reviewed ?? 0 }}/{{ st.high_risk_total ?? st.high ?? '—' }}",
        # 筛选下拉（全部/未审校/已审校/未审校高危，el-option）
        '<el-option label="未审校" value="unreviewed" />', '<el-option label="已审校" value="approved" />',
        '<el-option label="未审校高危" value="unreviewed_high" />',
        # 行勾选框 + 批量「标记已审校」按钮
        'data-testid="drug-rule-review"', "function markReviewed(", "标记已审校",
        "'/medical/admin/drug/rules/review'",
        # 审校徽章（已审校/未审校）
        "'已审校' : '未审校'",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 徽章/筛选/排序逻辑：revOf 缺省视为未审校 + 列表高危默认排前（稳定排序）+ unreviewed_high 分支
    rev = _extract_fn(html, "revOf")
    assert "review_status === 'approved'" in rev, "徽章必须按 review_status 计算（缺省视为未审校）"
    assert "reviewFilter.value === 'unreviewed_high'" in html, "筛选必须含「未审校高危」分支"
    assert "(y.r.severity === '高危') - (x.r.severity === '高危')" in html, \
        "规则列表默认高危排前（稳定排序）"
    # 批量标记：从勾选框取编码键，decision=approved，成功后重载
    mk = _extract_fn(html, "markReviewed")
    assert "keys: checked.value" in mk, "批量标记必须收集勾选框的规范药对编码键"
    assert "decision: 'approved'" in mk, "批量标记提交 decision=approved（reviewed_by 由后端取登录名）"
    assert "await load()" in mk, "标记成功后必须重载规则编辑器"
    # humanizeAudit 补 reviewed 映射（审计覆盖回归锁配套）
    hum = _extract_fn(_all_frontend_js(), "humanizeAudit")
    assert "reviewed:" in hum, "humanizeAudit MAP 必须补 reviewed（drug_rules.reviewed 人话）"


def _extract_fn(src: str, name: str) -> str:
    """从内联脚本源码截取完整 JS 函数定义（与 tests/test_frontend_syntax 同口径）。"""
    start = src.index("function " + name + "(")
    end = src.index("\n}", start)
    return src[start:end + 2]
