"""§296 临床验证研究（计划书 W8.1）。

把"AI 准不准"从主观判断变成可复核的统计：
- 研究方案（预期用途/人群/金标准/验收阈值）先登记；
- 逐例录入金标准与模型判断（只收布尔结果，不收患者信息）；
- 自动计算敏感度/特异度/PPV/NPV/准确率/F1 与 Wilson 95% 置信区间；
- 提交要求至少 30 例且阳性/阴性各至少 10 例，避免"5 例全对"式结论；
- 审批四眼，批准时冻结结果快照（``approvedResult``），后续改病例必须新建研究。

边界：统计口径是二分类；多分类/连续评分需扩展。结论必须写局限，不能只报喜。
"""
from __future__ import annotations

import copy
import math

from backend.agents import catalog
from backend.agents.governance import common
from backend.agents.governance.common import (
    GovernanceConflict,
    GovernanceForbidden,
    GovernanceInvalid,
    GovernanceNotFound,
)

STATUSES = ("DRAFT", "IN_REVIEW", "APPROVED", "RETIRED")
MIN_CASES = 30
MIN_PER_CLASS = 10
Z95 = 1.959963984540054


def _items() -> list[dict]:
    return common.load("studies")


def _find(items: list[dict], study_code: str) -> dict:
    for item in items:
        if item.get("studyCode") == study_code:
            return item
    raise GovernanceNotFound("未找到该临床验证研究")


def _require_agent(value) -> str:
    agent_id = common.require_code(value, "agentId").lower()
    if catalog.get_agent(agent_id) is None:
        raise GovernanceInvalid("agentId 不在智能体目录中")
    return agent_id


def _wilson(successes: int, n: int) -> list[float]:
    """Wilson 95% 置信区间：小样本下比正态近似可靠，不会给出 <0 或 >1 的区间。"""
    if n <= 0:
        return [0.0, 0.0]
    p = successes / n
    denom = 1 + Z95 * Z95 / n
    center = (p + Z95 * Z95 / (2 * n)) / denom
    half = (Z95 * math.sqrt(p * (1 - p) / n + Z95 * Z95 / (4 * n * n))) / denom
    return [round(max(0.0, center - half), 4), round(min(1.0, center + half), 4)]


def _ratio(part: int, total: int) -> float:
    return round(part / total, 4) if total else 0.0


def _compute(cases: list[dict], acceptance_sensitivity: float,
             acceptance_specificity: float) -> dict:
    tp = sum(1 for c in cases if c["referencePositive"] and c["predictionPositive"])
    fp = sum(1 for c in cases if not c["referencePositive"] and c["predictionPositive"])
    tn = sum(1 for c in cases if not c["referencePositive"] and not c["predictionPositive"])
    fn = sum(1 for c in cases if c["referencePositive"] and not c["predictionPositive"])
    n = len(cases)
    sensitivity = _ratio(tp, tp + fn)
    specificity = _ratio(tn, tn + fp)
    ppv = _ratio(tp, tp + fp)
    npv = _ratio(tn, tn + fn)
    accuracy = _ratio(tp + tn, n) if n else 0.0
    f1 = round(2 * tp / (2 * tp + fp + fn), 4) if (2 * tp + fp + fn) else 0.0
    sufficient = n >= MIN_CASES and (tp + fn) >= MIN_PER_CLASS and (tn + fp) >= MIN_PER_CLASS
    meets = (sufficient and sensitivity >= acceptance_sensitivity
             and specificity >= acceptance_specificity)
    return {
        "n": n,
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
        "positives": tp + fn,
        "negatives": tn + fp,
        "sensitivity": sensitivity,
        "specificity": specificity,
        "ppv": ppv,
        "npv": npv,
        "accuracy": accuracy,
        "f1": f1,
        "sensitivityCi": _wilson(tp, tp + fn),
        "specificityCi": _wilson(tn, tn + fp),
        "acceptanceSensitivity": acceptance_sensitivity,
        "acceptanceSpecificity": acceptance_specificity,
        "sufficient": sufficient,
        "meetsAcceptance": meets,
        "qualityGate": "PASS" if meets else ("INSUFFICIENT" if not sufficient else "FAIL"),
        "computedAt": common.now_iso(),
    }


def list_studies(status: str | None = None, agent_id: str | None = None) -> list[dict]:
    items = _items()
    if status:
        st = common.require_choice(status, "status", STATUSES)
        items = [i for i in items if i.get("status") == st]
    if agent_id:
        items = [i for i in items if i.get("agentId") == str(agent_id).strip().lower()]
    return sorted(items, key=lambda i: (i.get("createdAt") or ""), reverse=True)


def get_study(study_code: str) -> dict:
    return _find(_items(), study_code)


def create_study(payload: dict, actor: str) -> dict:
    title = common.require_no_phi(
        common.require_text(payload.get("title"), "研究标题", 5, 200), "研究标题")
    agent_id = _require_agent(payload.get("agentId"))
    intended_use = common.require_no_phi(
        common.require_text(payload.get("intendedUse"), "预期用途", 10, 1000), "预期用途")
    population = common.require_no_phi(
        common.require_text(payload.get("population"), "目标人群", 5, 500), "目标人群")
    gold = common.require_no_phi(
        common.require_text(payload.get("goldStandard"), "金标准", 5, 500), "金标准")
    protocol = common.require_no_phi(
        common.require_text(payload.get("protocol"), "研究方案", 10, 5000), "研究方案")
    ethics = common.optional_text(payload.get("ethicsApproval"), "伦理批件号", 64)
    target = payload.get("targetSampleSize", 100)
    try:
        target = int(target)
    except (TypeError, ValueError):
        raise GovernanceInvalid("目标样本量必须是整数") from None
    if target < MIN_CASES or target > 100000:
        raise GovernanceInvalid(f"目标样本量必须在 {MIN_CASES}–100000 之间")
    acceptance_s = common.require_ratio(payload.get("acceptanceSensitivity"),
                                        "敏感度验收阈值", 0.85)
    acceptance_p = common.require_ratio(payload.get("acceptanceSpecificity"),
                                        "特异度验收阈值", 0.80)

    with common.lock:
        items = _items()
        code = payload.get("studyCode")
        if code:
            code = common.require_code(code, "studyCode").upper()
            if any(i.get("studyCode") == code for i in items):
                raise GovernanceConflict("研究编号已存在")
        else:
            prefix = "VAL-" + common.now().strftime("%Y%m%d") + "-"
            seq = sum(1 for i in items if str(i.get("studyCode", "")).startswith(prefix)) + 1
            code = f"{prefix}{seq:03d}"
        item = {
            "studyCode": code,
            "title": title,
            "agentId": agent_id,
            "intendedUse": intended_use,
            "population": population,
            "goldStandard": gold,
            "protocol": protocol,
            "ethicsApproval": ethics,
            "targetSampleSize": target,
            "acceptanceSensitivity": acceptance_s,
            "acceptanceSpecificity": acceptance_p,
            "status": "DRAFT",
            "cases": [],
            "result": _compute([], acceptance_s, acceptance_p),
            "createdBy": actor,
            "createdAt": common.now_iso(),
            "updatedAt": common.now_iso(),
            "rowVersion": 1,
        }
        items.append(item)
        common.save("studies", items)
        return item


def add_case(study_code: str, payload: dict, actor: str) -> dict:
    with common.lock:
        items = _items()
        study = _find(items, study_code)
        if study.get("status") != "DRAFT":
            raise GovernanceInvalid("只有草稿研究可以录入病例；已提交/已批准的研究不可修改")
        case_id = common.require_code(payload.get("caseId"), "caseId")
        if any(c.get("caseId") == case_id for c in study.get("cases") or []):
            raise GovernanceConflict("该研究已存在相同 caseId")
        reference = common.require_bool(payload.get("referencePositive"), "referencePositive")
        prediction = common.require_bool(payload.get("predictionPositive"),
                                         "predictionPositive")
        note = common.optional_text(payload.get("note"), "病例备注", 200)
        if note:
            note = common.require_no_phi(note, "病例备注")
        case = {
            "caseId": case_id,
            "referencePositive": reference,
            "predictionPositive": prediction,
            "note": note,
            "addedBy": actor,
            "addedAt": common.now_iso(),
        }
        study.setdefault("cases", []).append(case)
        study["result"] = _compute(study["cases"], study["acceptanceSensitivity"],
                                   study["acceptanceSpecificity"])
        common.bump(study)
        common.save("studies", items)
        return study


def submit_study(study_code: str, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        study = _find(items, study_code)
        if study.get("status") != "DRAFT":
            raise GovernanceInvalid("只有草稿研究可以提交审核")
        common.require_row_version(study, expected)
        result = study.get("result") or {}
        if int(result.get("n") or 0) < MIN_CASES:
            raise GovernanceInvalid(f"至少录入 {MIN_CASES} 例才能提交（当前 {result.get('n', 0)} 例）")
        if int(result.get("positives") or 0) < MIN_PER_CLASS:
            raise GovernanceInvalid(f"阳性例数至少 {MIN_PER_CLASS} 例才能评估敏感度")
        if int(result.get("negatives") or 0) < MIN_PER_CLASS:
            raise GovernanceInvalid(f"阴性例数至少 {MIN_PER_CLASS} 例才能评估特异度")
        study["status"] = "IN_REVIEW"
        study["submittedBy"] = actor
        study["submittedAt"] = common.now_iso()
        common.bump(study)
        common.save("studies", items)
        return study


def review_study(study_code: str, approve: bool, payload: dict, actor: str) -> dict:
    with common.lock:
        items = _items()
        study = _find(items, study_code)
        if study.get("status") != "IN_REVIEW":
            raise GovernanceInvalid("只有待审核研究可以审批")
        if actor == study.get("createdBy"):
            raise GovernanceForbidden("不能审批自己创建的研究，请由其他人复核")
        common.require_row_version(study, payload.get("rowVersion"))
        if approve:
            conclusion = common.require_no_phi(
                common.require_text(payload.get("conclusion"), "研究结论", 10, 2000),
                "研究结论")
            limitations = common.require_no_phi(
                common.require_text(payload.get("limitations"), "局限性", 10, 2000),
                "局限性")
            result = _compute(study.get("cases") or [], study["acceptanceSensitivity"],
                              study["acceptanceSpecificity"])
            study["result"] = result
            study["approvedResult"] = copy.deepcopy(result)
            study["conclusion"] = conclusion
            study["limitations"] = limitations
            study["status"] = "APPROVED"
            study["approvedBy"] = actor
            study["approvedAt"] = common.now_iso()
            study["approvalComment"] = common.optional_text(
                payload.get("approvalComment"), "审批意见", 500)
        else:
            reason = common.require_text(payload.get("rejectReason"), "驳回原因", 2, 500)
            study["status"] = "DRAFT"
            study["rejectedBy"] = actor
            study["rejectedAt"] = common.now_iso()
            study["rejectReason"] = reason
        common.bump(study)
        common.save("studies", items)
        return study


def retire_study(study_code: str, reason, expected: int, actor: str) -> dict:
    with common.lock:
        items = _items()
        study = _find(items, study_code)
        if study.get("status") not in ("APPROVED", "IN_REVIEW"):
            raise GovernanceInvalid("只有已批准或待审核研究可以停用")
        note = common.require_text(reason, "停用原因", 2, 256)
        common.require_row_version(study, expected)
        study["status"] = "RETIRED"
        study["retiredBy"] = actor
        study["retiredAt"] = common.now_iso()
        study["retireReason"] = note
        common.bump(study)
        common.save("studies", items)
        return study


def approved_studies() -> list[dict]:
    return [i for i in _items() if i.get("status") == "APPROVED"]

