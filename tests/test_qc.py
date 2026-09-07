"""病历质控三轨离线测试（完整性/ICD/危急值为确定性；内涵质量 LLM 在离线时降级为空）。"""
from backend.core import qc


def test_completeness_flags_missing_and_short():
    defects = qc.completeness_check({"主诉": "牙痛3天", "现病史": "太短"})
    fields = {d["field"] for d in defects}
    assert "既往史" in fields and "初步诊断" in fields   # 缺失
    assert any(d["field"] == "现病史" and d["issue"] == "内容过简" for d in defects)


def test_completeness_pass_when_filled():
    rec = {f: "内容充分填写完整无误" for f in qc.REQUIRED_FIELDS}
    assert qc.completeness_check(rec) == []


def test_icd_check_uses_table_and_disabled_without():
    off = qc.icd_check({"初步诊断": "2型糖尿病"}, None)
    assert off["enabled"] is False
    table = {"2型糖尿病": "E11"}
    mismatch = qc.icd_check({"初步诊断": "2型糖尿病", "ICD编码": "E10"}, table)
    assert mismatch["enabled"] and mismatch["issues"]
    ok = qc.icd_check({"初步诊断": "2型糖尿病", "ICD编码": "E11"}, table)
    assert ok["issues"] == []


def test_critical_value_engine_never_invents():
    assert qc.critical_value_check({"血钾": 7.0}, None)["enabled"] is False
    th = {"血钾": {"low": 2.5, "high": 6.0}}  # 测试合成阈值，非临床权威
    flags = qc.critical_value_check({"血钾": 7.0}, th)["flags"]
    assert flags and flags[0]["item"] == "血钾"
    assert qc.critical_value_check({"血钾": 4.0}, th)["flags"] == []


def test_review_quality_always_needs_human_and_tracks_present():
    rec = {f: "填写完整" for f in qc.REQUIRED_FIELDS}
    out = qc.review_quality(rec)
    assert out["needs_human_review"] is True   # 病案质控终审恒人工
    for k in ("completeness", "connotation", "icd", "critical", "defects", "confidence"):
        assert k in out
    assert 0.0 <= out["confidence"] <= 1.0
