"""阶段1.1：药品字典域模块 drug_dict（repo + 进程内缓存 + 失效钩子）——TDD 先行测试。

覆盖：别名图/规则图构建（规范名→自身、别名/商品名→规范名、药对规范化 a<b）、
进程内缓存（PG/JSON 只读一次）、invalidate 失效钩子（阶段1.5 管理页用）、
save 直写后缓存同步、medical_drug 接线（detect_drugs/check 读 repo + 机制/处置拼接）。

不连真实 PG（池为 None → 纯 JSON 路径），文件全部隔离到 tmp_path。
"""
import json

import pytest

from backend.core import drug_dict
from backend.core import medical_drug as md

_DICT = [
    {"name": "华法林", "aliases": ["法华林", "warfarin"], "brand_names": ["可密达"],
     "category": "抗凝抗栓", "level": "处方药"},
    {"name": "布洛芬", "aliases": ["ibuprofen"], "brand_names": ["芬必得"],
     "category": "解热镇痛", "level": "OTC"},
    {"name": "阿司匹林", "aliases": ["aspirin", "阿斯匹林"], "brand_names": ["拜阿司匹灵"],
     "category": "抗凝抗栓", "level": "处方药"},
]
_RULES = [
    {"drug_a": "布洛芬", "drug_b": "华法林", "severity": "高危",
     "mechanism": "NSAID 抑制血小板并置换蛋白结合。", "management": "避免联用并监测 INR。",
     "source": "AI辅助生成·待药师核对"},
    {"drug_a": "阿司匹林", "drug_b": "华法林", "severity": "高危",
     "mechanism": "双重抗栓出血叠加。", "management": "", "source": "curated_v2"},
]


@pytest.fixture(autouse=True)
def _reset_cache():
    """进程内缓存是模块级状态：每个测试前后强制失效，防跨测试串数据。"""
    drug_dict.invalidate()
    yield
    drug_dict.invalidate()


@pytest.fixture()
def isolated_repo(tmp_path, monkeypatch):
    """字典/规则 JSON 隔离到 tmp_path 并写入固定数据，返回文件路径。"""
    df = tmp_path / "drug_dict.json"
    rf = tmp_path / "drug_rules.json"
    df.write_text(json.dumps(_DICT, ensure_ascii=False), encoding="utf-8")
    rf.write_text(json.dumps(_RULES, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(drug_dict, "DRUG_DICT_FILE", str(df))
    monkeypatch.setattr(drug_dict, "DRUG_RULES_FILE", str(rf))
    return df, rf


# ---------- 别名图 / 规则图 ----------

def test_alias_map_maps_name_aliases_and_brands(isolated_repo):
    m = drug_dict.alias_map()
    assert m["华法林"] == "华法林"          # 规范名 → 自身
    assert m["法华林"] == "华法林"          # 别名
    assert m["warfarin"] == "华法林"
    assert m["可密达"] == "华法林"          # 商品名
    assert m["芬必得"] == "布洛芬"
    assert m["阿斯匹林"] == "阿司匹林"


def test_rule_map_normalizes_pair(isolated_repo):
    m = drug_dict.rule_map()
    # 乱序输入 → frozenset 药对键（与输入顺序无关，legacy INTERACTIONS 同构）
    assert frozenset({"华法林", "布洛芬"}) in m and frozenset({"华法林", "阿司匹林"}) in m
    assert m[frozenset({"华法林", "布洛芬"})][0] == "高危"


def test_alias_map_and_rule_map_use_cache(isolated_repo, monkeypatch):
    """进程内缓存：同一进程多次查询只触发一次底层读（PG/JSON）；
    invalidate 后重新读（阶段1.5 管理页变更后调失效钩子即可生效）。"""
    calls = {"n": 0}
    real_load = drug_dict.pg_store.load_drug_dict

    def counting_loader(path, json_loader=None):
        calls["n"] += 1
        return real_load(path, json_loader)

    monkeypatch.setattr(drug_dict.pg_store, "load_drug_dict", counting_loader)
    drug_dict.alias_map()
    drug_dict.alias_map()
    drug_dict.alias_map()
    assert calls["n"] == 1, "进程内缓存应命中，底层只读一次"
    drug_dict.invalidate()
    drug_dict.alias_map()
    assert calls["n"] == 2, "失效后应重新加载"


def test_save_dict_writes_files_and_refreshes_cache(isolated_repo):
    df, rf = isolated_repo
    items = drug_dict.load_dict() + [
        {"name": "氯吡格雷", "aliases": ["clopidogrel"], "brand_names": ["波立维"],
         "category": "抗凝抗栓", "level": "处方药"}]
    drug_dict.save_dict(items)
    on_disk = json.loads(df.read_text(encoding="utf-8"))
    assert [i["name"] for i in on_disk] == ["华法林", "布洛芬", "阿司匹林", "氯吡格雷"]
    assert "氯吡格雷" in drug_dict.alias_map(), "save 后缓存应反映新数据"


def test_save_rules_writes_files_and_refreshes_cache(isolated_repo):
    _, rf = isolated_repo
    items = drug_dict.load_rules() + [
        {"drug_a": "布洛芬", "drug_b": "华法林", "severity": "中危",
         "mechanism": "修订：仅提示监测。", "management": "", "source": "s"}]
    drug_dict.save_rules(items)
    on_disk = json.loads(rf.read_text(encoding="utf-8"))
    assert len(on_disk) == 2  # 同药对覆盖（后写生效，JSON 兜底文件按规范药对去重）
    m = drug_dict.rule_map()
    assert m[frozenset({"华法林", "布洛芬"})][0] == "中危"


# ---------- medical_drug 接线：查询函数读 repo ----------

def test_detect_drugs_reads_repo_alias_map(isolated_repo):
    assert md.detect_drugs("我在吃法华林") == ["华法林"]
    assert md.detect_drugs("布洛芬和ibupro芬") == ["布洛芬"]
    assert "阿司匹林" in md.detect_drugs("拜阿司匹灵与阿司匹林")


def test_check_reads_repo_rules_and_combines_management(isolated_repo):
    r = md.check("华法林和布洛芬能一起吃吗")
    assert "发现以下相互作用" in r["answer"]
    # mechanism + management 拼接（management 非空时以「；」连接）
    assert "NSAID 抑制血小板并置换蛋白结合。；避免联用并监测 INR。" in r["answer"]
    assert r["max_severity"] == "高危"
    assert r["sources"] == ["drug_rules:curated_v2"]


def test_check_management_empty_keeps_legacy_note(isolated_repo):
    """management 为空（legacy curated_v2 迁移条目形态）→ 备注原文不变。"""
    r = md.check("华法林与阿司匹林联用")
    assert "双重抗栓出血叠加。" in r["answer"]
    assert "；。" not in r["answer"]
