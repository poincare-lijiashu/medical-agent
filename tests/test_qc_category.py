"""任务1：质控维度枚举化——category 强制六枚举（完整性/一致性/诊断依据/鉴别诊断/书写规范/其他）。

- prompt 内给出枚举定义（完整性=必备要素缺失；一致性=主诉/现病史/查体/诊断之间矛盾；
  诊断依据=诊断与检查支撑关系；鉴别诊断=需鉴别而未记录；书写规范=格式/签名/科室归属）；
- 解析时非枚举值一律归「其他」；
- /qc/record 下发结构化 qc_defects（前端按维度分组渲染 + 快速过滤）。

LLM 全部用假响应隔离，绝不触网；队列文件与审计隔离到 tmp_path。
"""
import json

from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import medical_review as review
from backend.core import qc as qc_mod
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

FULL_FIELDS = ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")


class _FakeLLM:
    """假 LLM：invoke 返回预置 content；captured 非空时记录收到的 prompt。"""

    def __init__(self, content, captured=None):
        self._content = content
        self._captured = captured

    def invoke(self, messages):
        if self._captured is not None:
            self._captured.append(messages[0].content)

        class _R:
            pass

        r = _R()
        r.content = self._content
        return r


def _fake_llm_factory(content, captured=None):
    return lambda *a, **k: _FakeLLM(content, captured)


# ---- 归一函数 ----

def test_normalize_category_accepts_all_enum_values():
    for c in qc_mod.QC_CATEGORIES:
        assert qc_mod.normalize_category(c) == c


def test_normalize_category_non_enum_goes_other():
    assert qc_mod.normalize_category("胡编的维度") == "其他"
    assert qc_mod.normalize_category("") == "其他"
    assert qc_mod.normalize_category(None) == "其他"
    assert qc_mod.normalize_category("  一致性  ") == "一致性"  # 容忍首尾空白


def test_qc_category_enum_six_dimensions():
    assert qc_mod.QC_CATEGORIES == ("完整性", "一致性", "诊断依据", "鉴别诊断", "书写规范", "其他")
    # 枚举定义齐备（prompt 中引用）
    assert qc_mod.QC_CATEGORY_DESC["完整性"] == "必备要素缺失"
    assert qc_mod.QC_CATEGORY_DESC["书写规范"] == "格式/签名/科室归属"


# ---- LLM 解析：非法 category 归「其他」----

def test_connotation_non_enum_category_normalized_to_other(monkeypatch):
    payload = json.dumps({"defects": [
        {"issue": "主诉与现病史时间矛盾", "level": "高", "category": "一致性"},
        {"issue": "缺关键鉴别记录", "level": "中", "category": "乱写的维度"},
        {"issue": "诊断缺乏检查支持", "level": "中"},              # 缺 category
        {"issue": "空 category", "level": "低", "category": ""},    # 空 category
    ]}, ensure_ascii=False)
    monkeypatch.setattr("backend.core.llm_factory.get_llm", _fake_llm_factory(payload))
    out = qc_mod.connotation_check({"主诉": "x"})
    cats = [d["category"] for d in out]
    assert cats[0] == "一致性"
    assert cats[1:] == ["其他", "其他", "其他"]


def test_connotation_prompt_contains_enum_definitions(monkeypatch):
    captured = []
    payload = json.dumps({"defects": []}, ensure_ascii=False)
    monkeypatch.setattr("backend.core.llm_factory.get_llm", _fake_llm_factory(payload, captured))
    qc_mod.connotation_check({"主诉": "x"})
    prompt = captured[0]
    for word in ("完整性", "一致性", "诊断依据", "鉴别诊断", "书写规范", "其他"):
        assert word in prompt, f"prompt 缺枚举值：{word}"
    for desc in ("必备要素缺失", "主诉/现病史/查体/诊断之间矛盾", "诊断与检查支撑关系",
                 "需鉴别而未记录", "格式/签名/科室归属"):
        assert desc in prompt, f"prompt 缺枚举定义：{desc}"
    assert "category" in prompt


def test_completeness_defects_carry_integrity_category():
    defects = qc_mod.completeness_check({"主诉": "", "现病史": "短"})
    assert defects and all(d["category"] == "完整性" for d in defects)


def test_review_quality_defects_all_categorized(monkeypatch):
    rec = {f: "内容充分填写完整无误" for f in FULL_FIELDS}
    rec.pop("辅助检查")
    payload = json.dumps({"defects": [{"issue": "诊断缺乏病史支持", "level": "中",
                                       "category": "诊断依据"}]}, ensure_ascii=False)
    # connotation 内部 import get_llm：monkeypatch 后 review_quality 汇总路径同样生效
    monkeypatch.setattr("backend.core.llm_factory.get_llm", _fake_llm_factory(payload))
    out = qc_mod.review_quality(rec)
    cats = {d["category"] for d in out["defects"]}
    assert cats == {"完整性", "诊断依据"}
    assert all(c in qc_mod.QC_CATEGORIES for c in cats)


# ---- 路由：/qc/record 下发 qc_defects（前端分组渲染数据源）----

def test_route_qc_record_returns_structured_qc_defects(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    payload = json.dumps({"defects": [
        {"issue": "主诉与现病史部位矛盾", "level": "高", "category": "一致性"},
        {"issue": " bizarre ", "level": "低", "category": "不存在的维度"},
    ]}, ensure_ascii=False)
    monkeypatch.setattr("backend.core.llm_factory.get_llm", _fake_llm_factory(payload))
    c = TestClient(app)
    tok = c.post("/api/v1/auth/login",
                 json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    rec = {f: "内容充分填写完整无误" for f in FULL_FIELDS}
    r = c.post("/api/v1/medical/qc/record", headers={"Authorization": "Bearer " + tok},
               json={"record": rec})
    assert r.status_code == 200, r.text
    d = r.json()
    defects = d["qc_defects"]
    assert defects, "应下发结构化 qc_defects"
    assert all(x["category"] in qc_mod.QC_CATEGORIES for x in defects), "category 必须落在六枚举内"
    con = [x for x in defects if x["category"] == "一致性"]
    assert con and con[0]["level"] == "高" and con[0]["issue"] == "主诉与现病史部位矛盾"
    assert any(x["category"] == "其他" for x in defects), "非法 category 应归「其他」"
    # 完整性轨无硬伤（字段全填），分组数据里不应出现「完整性」空组
    assert all(x["track"] in ("完整性", "内涵质量") for x in defects)
