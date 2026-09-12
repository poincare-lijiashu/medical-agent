"""病历质控三轨（借鉴 MedAgent 蓝图）。

轨道1 完整性规则引擎：必填项缺失=确定性判定，100% 可解释，不调 LLM（可离线单测）。
轨道2 内涵质量 LLM：主诉/现病史/诊断一致性与逻辑缺陷（Think 两步：先推理后分级），失败降级。
轨道3 ICD/危急值：数据驱动——需院方提供编码表/阈值表；**未提供则标记未启用，绝不用臆造的临床阈值做安全判定**。
三列留痕（AI建议/人工裁定/最终结果）由审核中心承载；本模块只产出 AI 建议。
"""
from __future__ import annotations

import json
import logging
import re

REQUIRED_FIELDS = ["主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名"]
# 内涵质量的最小长度启发（仅提示，不作硬诊断）
MIN_LEN = {"现病史": 10, "既往史": 2, "初步诊断": 2}

# 任务1 质控维度枚举：LLM 输出 category 必须落在此枚举内（与前端分组/过滤一致）；
# 非枚举值在解析层一律归「其他」，杜绝自由文本维度污染统计与前端分组。
QC_CATEGORIES = ("完整性", "一致性", "诊断依据", "鉴别诊断", "书写规范", "其他")
QC_CATEGORY_DESC = {
    "完整性": "必备要素缺失",
    "一致性": "主诉/现病史/查体/诊断之间矛盾",
    "诊断依据": "诊断与检查支撑关系",
    "鉴别诊断": "需鉴别而未记录",
    "书写规范": "格式/签名/科室归属",
    "其他": "以上维度之外未归类的缺陷",
}


def normalize_category(value) -> str:
    """维度归一（解析层强制收敛）：合法枚举原样返回，非枚举/空值一律「其他」。

    LLM 输出不可信：无论它编出什么维度名，下游只认 QC_CATEGORIES 六个值。
    """
    v = str(value or "").strip()
    return v if v in QC_CATEGORIES else "其他"


def completeness_check(record: dict) -> list[dict]:
    """确定性：必填项缺失或过短。返回缺陷列表（不调 LLM）。category 恒为「完整性」。"""
    defects = []
    for f in REQUIRED_FIELDS:
        v = record.get(f)
        if v is None or not str(v).strip():
            defects.append({"field": f, "issue": "缺失", "level": "高", "track": "完整性",
                            "category": "完整性"})
        elif f in MIN_LEN and len(str(v).strip()) < MIN_LEN[f]:
            defects.append({"field": f, "issue": "内容过简", "level": "中", "track": "完整性",
                            "category": "完整性"})
    return defects


def connotation_check(record: dict) -> list[dict]:
    """内涵质量：主诉/现病史/诊断的一致性与逻辑。LLM Think 两步；失败降级返回空。

    任务1：category 强制枚举（QC_CATEGORIES），prompt 内给出六维定义；
    解析时经 normalize_category 收敛，非枚举值一律归「其他」。
    """
    try:
        from langchain_core.messages import HumanMessage
        from backend.core.llm_factory import get_llm
        enum_desc = "；".join(f"{k}={v}" for k, v in QC_CATEGORY_DESC.items())
        prompt = (
            "你是病案质控专家。第一步逐条推理该病历的内涵质量缺陷（如主诉与现病史时间/部位不一致、"
            "诊断缺乏病史支持、缺关键鉴别记录）；第二步对缺陷分级（高/中/低），"
            "并为每条缺陷归类维度 category，category 必须且只能是以下枚举之一：" + enum_desc + "。"
            '仅返回 JSON：{"defects":[{"issue":"...","level":"高|中|低",'
            '"category":"完整性|一致性|诊断依据|鉴别诊断|书写规范|其他"}]}；无缺陷则 defects 为空数组。\n'
            "病历：" + json.dumps(record, ensure_ascii=False)[:1500]
        )
        r = get_llm("medical_case", temperature=0).invoke([HumanMessage(content=prompt)])
        raw = (r.content if hasattr(r, "content") else str(r))
        raw = raw.strip()
        if raw.startswith("```"):
            raw = "\n".join(l for l in raw.split("\n")[1:] if not l.startswith("```"))
        m = re.search(r"\{.*\}", raw, re.S)
        data = json.loads(m.group()) if m else {"defects": []}
        out = []
        for dd in data.get("defects", []) or []:
            out.append({"field": dd.get("field", "内涵质量"), "issue": dd.get("issue", ""),
                        "level": dd.get("level", "中"), "track": "内涵质量",
                        "category": normalize_category(dd.get("category"))})
        return out
    except Exception:  # noqa: BLE001
        return []  # 降级：LLM 不可用则该轨未执行，不影响其它轨


def icd_check(record: dict, code_table: dict | None) -> dict:
    """ICD 编码核对（需院方诊断-ICD 表 {诊断名: ICD码}）。无表→未启用（不臆造编码）。"""
    if not code_table:
        return {"track": "ICD核对", "enabled": False, "issues": [], "note": "未提供院方 ICD 编码库"}
    issues = []
    dx = str(record.get("初步诊断", ""))
    code = record.get("ICD编码") or record.get("诊断编码")
    # ICD编码可能是 list（多诊断病历），按成员逐一比对
    code_strs = [str(code)] if not isinstance(code, (list, tuple)) else [str(c) for c in code]
    for name, expect in code_table.items():
        if name and name in dx:
            if code_strs and not any(str(expect) in c for c in code_strs):
                issues.append({"field": name, "issue": f"编码应为 {expect}", "level": "高", "track": "ICD核对"})
    return {"track": "ICD核对", "enabled": True, "issues": issues}


def critical_value_check(labs: dict, thresholds: dict | None) -> dict:
    """危急值硬规则（需院方阈值表 {项目:{low,high}}）。无表→未启用；绝不用臆造数值。
    终评 F7：阈值含非数值（如 "N/A"、脏数据）不再 500——该值无法比较，跳过该项并
    warning 留痕（阈值表是院方配置，脏项不应拖垮整份质控）。"""
    if not thresholds:
        return {"track": "危急值", "enabled": False, "flags": [], "note": "未提供院方危急值阈值表"}
    flags = []
    for item, val in (labs or {}).items():
        th = thresholds.get(item)
        if not th:
            continue
        try:
            v = float(val)
            hi = float(th["high"]) if "high" in th else None
            lo = float(th["low"]) if "low" in th else None
        except (TypeError, ValueError):
            logging.getLogger(__name__).warning(
                "危急值阈值非数值，跳过该项目比对：item=%r threshold=%r", item, th)
            continue
        if hi is not None and v > hi:
            flags.append({"item": item, "value": v, "issue": f"危急高值 >{th['high']}", "level": "危急"})
        elif lo is not None and v < lo:
            flags.append({"item": item, "value": v, "issue": f"危急低值 <{th['low']}", "level": "危急"})
    return {"track": "危急值", "enabled": True, "flags": flags}


def review_quality(record: dict, icd_table=None, thresholds=None, labs=None) -> dict:
    """三轨汇总为 AI 建议（含完整性+内涵缺陷、ICD/危急状态、置信、需人工终审）。"""
    comp = completeness_check(record)
    con = connotation_check(record)
    icd = icd_check(record, icd_table)
    crit = critical_value_check(labs or {}, thresholds)
    defects = comp + con
    # 危急值或高危缺陷 → 置信偏低且必须人工终审
    has_critical = bool(crit.get("flags"))
    high = [d for d in defects if d.get("level") in ("高", "危急")]
    conf = 0.85 if not defects and not has_critical else (0.6 if not high else 0.45)
    # 确定性硬伤 = 完整性规则引擎失败项（100% 可解释，QC_AUTO_PASS 开启时用于自动驳回）
    hard_defects = [f"{d['field']}：{d['issue']}" for d in comp]
    return {
        "defects": defects, "completeness": comp, "connotation": con,
        "icd": icd, "critical": crit, "has_critical": has_critical,
        "hard_defects": hard_defects, "hard_pass": not hard_defects,
        "confidence": conf, "needs_human_review": True,  # 病历质控终审恒定人工签字
    }
