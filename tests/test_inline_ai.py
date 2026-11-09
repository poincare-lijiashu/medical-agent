"""内联 AI（第 132 批）测试：医嘱内联审方 / 病历内联质控。

锁定三条商业形态要求：
1. 审方是**确定性规则**（字典外/重复/相互作用），不调 LLM；
2. 质控默认只跑完整性规则，LLM 内涵质量必须显式请求；
3. 审计只记计数与等级（不写病例正文）。
"""
from __future__ import annotations

import pytest

from backend.api.v1.medical import inline
from backend.core import drug_dict as dd


@pytest.fixture(autouse=True)
def _isolate(monkeypatch):
    """隔离审计副作用：内联检查不落真实审计文件。"""
    writes: list[dict] = []

    class _Log:
        def write(self, **kw):
            writes.append(kw)

    monkeypatch.setattr(inline._shared, "get_audit_logger", lambda: _Log())
    monkeypatch.setattr(inline.pg_store, "mirror_audit", lambda *a, **k: None)
    return writes


def _user():
    return {"username": "doctor01", "role": "doctor"}


@pytest.mark.asyncio
async def test_rx_check_high_risk_interaction(monkeypatch):
    monkeypatch.setattr(dd, "alias_map", lambda: {"华法林": "华法林", "阿司匹林": "阿司匹林"})
    monkeypatch.setattr(dd, "rule_map", lambda: {
        frozenset(("华法林", "阿司匹林")): ("高危", "出血风险显著增加", "监测 INR 与出血征象")})
    out = await inline.inline_rx_check(inline.RxCheckReq(items=[
        inline.RxItem(name="华法林", dose="3mg", frequency="qd"),
        inline.RxItem(name="阿司匹林", dose="100mg", frequency="qd"),
    ]), _user())
    assert out["level"] == "high"
    hit = [f for f in out["findings"] if f["code"] == "INTERACTION"]
    assert hit and hit[0]["severity"] == "高" and "出血" in hit[0]["detail"]
    assert out["disclaimer"]


@pytest.mark.asyncio
async def test_rx_check_out_of_dict_and_duplicate(monkeypatch):
    monkeypatch.setattr(dd, "alias_map", lambda: {"阿司匹林": "阿司匹林"})
    monkeypatch.setattr(dd, "rule_map", lambda: {})
    out = await inline.inline_rx_check(inline.RxCheckReq(items=[
        inline.RxItem(name="阿司匹林", dose="100mg", frequency="qd"),
        inline.RxItem(name="阿司匹林", dose="100mg", frequency="bid"),
        inline.RxItem(name="不明中成药", dose="1袋"),
    ]), _user())
    codes = {f["code"] for f in out["findings"]}
    assert "DUPLICATE" in codes and "OUT_OF_DICT" in codes
    # 重复用药=中危；字典外=低危 → overall medium
    assert out["level"] == "medium"


@pytest.mark.asyncio
async def test_rx_check_clean_passes(monkeypatch):
    monkeypatch.setattr(dd, "alias_map", lambda: {"阿莫西林": "阿莫西林"})
    monkeypatch.setattr(dd, "rule_map", lambda: {})
    out = await inline.inline_rx_check(inline.RxCheckReq(items=[
        inline.RxItem(name="阿莫西林", dose="0.5g", frequency="tid"),
    ]), _user())
    assert out["level"] == "low" and out["findings"] == []


@pytest.mark.asyncio
async def test_qc_check_completeness_only_by_default(monkeypatch):
    """默认不调 LLM：只返回完整性缺陷（缺失必填项）。"""
    called = {"connotation": False}

    def _boom(record):
        called["connotation"] = True
        return []

    monkeypatch.setattr(inline.qc_core, "connotation_check", _boom)
    out = await inline.inline_qc_check(
        inline.QcCheckReq(fields={"主诉": "头痛 2 天", "现病史": "反复头痛"}),
        _user())
    assert called["connotation"] is False, "未显式请求时不得调用 LLM"
    assert out["level"] == "high"
    fields = {d["field"] for d in out["defects"]}
    assert "初步诊断" in fields and "医师签名" in fields
    assert out["completeness_pass"] is False


@pytest.mark.asyncio
async def test_qc_check_homepage_profile_uses_homepage_required_fields():
    """病案首页用独立必填表：不应误报"缺主诉"，缺主治医师/离院方式才报。"""
    ok = await inline.inline_qc_check(inline.QcCheckReq(fields={
        "病案号": "P1", "科室": "内科", "主治医师": "张医生",
        "离院方式": "医嘱离院", "主要诊断": "高血压",
    }, profile="homepage"), _user())
    assert ok["level"] == "low" and ok["defects"] == []
    bad = await inline.inline_qc_check(inline.QcCheckReq(fields={"病案号": "P1"}, profile="homepage"),
                                       _user())
    fields = {d["field"] for d in bad["defects"]}
    assert {"科室", "主治医师", "离院方式", "主要诊断"} <= fields
    assert "主诉" not in fields, "首页没有主诉字段，不能套用 EMR 必填表"


@pytest.mark.asyncio
async def test_qc_check_can_include_connotation_with_redaction(monkeypatch):
    seen: dict = {}

    def _connotation(record):
        seen.update(record)
        return [{"field": "内涵质量", "issue": "诊断缺依据", "level": "中",
                 "track": "内涵质量", "category": "诊断依据"}]

    monkeypatch.setattr(inline.qc_core, "connotation_check", _connotation)
    out = await inline.inline_qc_check(inline.QcCheckReq(
        fields={"主诉": "头痛 2 天", "现病史": "患者张三电话 13800138000 反复头痛"},
        include_connotation=True), _user())
    assert out["level"] == "high"   # 缺失必填项为高
    assert any(d["issue"] == "诊断缺依据" for d in out["defects"])
    # 入模前脱敏：手机号不得原样进入模型输入
    assert "13800138000" not in seen.get("现病史", "")
