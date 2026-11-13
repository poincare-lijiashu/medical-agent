"""§296 AI 临床验证与模型治理（计划书 W8.1–W8.4）。

四个子域：
- ``versions``：模型/提示词版本状态机、四眼审批、生效与回滚、问答可追溯；
- ``validation``：临床验证研究、逐例混淆矩阵、Wilson 置信区间、审批冻结；
- ``drift``：按能力域的漂移快照、基线比较与告警信号；
- ``incidents``：误报/漏报/有害输出的事件闭环与逾期统计。

统一原则：只收聚合结果与脱敏文本；任何自由文本入口都拒绝患者身份信息。
"""
from __future__ import annotations

from backend.agents.governance import common, drift, incidents, runtime_binding, validation, versions

__all__ = ["common", "versions", "validation", "drift", "incidents", "runtime_binding", "overview"]


def overview() -> dict:
    """治理看板聚合：只读，不修改任何版本/研究/事件状态。"""
    active = versions.active_versions()
    in_review = versions.list_versions(status="IN_REVIEW")
    studies = validation.list_studies()
    approved = [s for s in studies if s.get("status") == "APPROVED"]
    meets = [s for s in approved
             if (s.get("approvedResult") or {}).get("meetsAcceptance") is True]
    latest_drift = drift.latest_by_agent()
    drift_alert = [d for d in latest_drift if d.get("status") == "ALERT"]
    drift_watch = [d for d in latest_drift if d.get("status") == "WATCH"]
    drift_insufficient = [d for d in latest_drift if d.get("status") == "INSUFFICIENT"]
    baselines = common.load("baselines")
    incident_summary = incidents.summary()

    active_model = any(i.get("kind") == "MODEL" for i in active)
    active_prompt = any(i.get("kind") == "PROMPT" for i in active)
    # 评测 D-13：登记 ≠ 生效。ACTIVE 版本必须与运行时实际模型一致，否则"可追溯"只是台账。
    binding = runtime_binding.check()
    checks = {
        "activeModelVersion": active_model,
        "activePromptVersion": active_prompt,
        "approvedValidationMeetsAcceptance": bool(meets),
        "driftBaselineConfigured": bool(baselines),
        "runtimeModelMatchesRegistry": binding["consistent"] is True,
    }
    passed = sum(1 for v in checks.values() if v)

    return {
        "generatedAt": common.now_iso(),
        "versions": {
            "active": active,
            "pendingApproval": len(in_review),
            "checks": checks,
        },
        "validation": {
            "total": len(studies),
            "approved": len(approved),
            "meetsAcceptance": len(meets),
            "recent": approved[:3],
        },
        "drift": {
            "agents": len(latest_drift),
            "alert": len(drift_alert),
            "watch": len(drift_watch),
            "insufficient": len(drift_insufficient),
            "latest": latest_drift,
            "thresholds": dict(drift.THRESHOLDS),
        },
        "incidents": incident_summary,
        "runtimeBinding": binding,
        "readiness": {
            "passed": passed,
            "total": len(checks),
            "score": round(passed / len(checks), 4),
            "checks": checks,
        },
    }
