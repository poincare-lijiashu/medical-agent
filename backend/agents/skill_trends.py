"""G94 运营趋势与告警：按日快照 + 阈值告警，**没有历史就不画趋势**。

约束：
- 快照按"业务日期"幂等：同一天重复执行只更新当天记录，不刷出多条；
- 指标来自 G93 的只读统计，不新增埋点、不调用模型；
- 历史不足 2 天时 `sufficient=false`，明确返回"无法计算趋势"，不做插值/推测；
- 告警只基于当前指标与相邻快照的**可核对差值**，每条带 metric/value/threshold。
"""
from __future__ import annotations

import json
import os
import threading
from datetime import date, datetime, timezone

from backend.agents import skill_ops
from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SNAPSHOT_FILE = os.path.join(BASE_DIR, "data", "skill_metrics_snapshots.json")

MAX_SNAPSHOTS = 180
_lock = threading.RLock()

# 告警阈值（只做提示，不自动处理）
MIN_REPLAY_CHECKS = 3
MIN_REPLAY_RATE = 0.8
MIN_PROPOSALS_FOR_ADOPTION = 3
MIN_ADOPTION_RATE = 0.3
MAX_UNUSED_PUBLISHED = 3
MAX_PROPOSED_BACKLOG = 5
MIN_EXPERIENCES_FOR_COVERAGE = 4
MIN_COVERAGE_RATE = 0.5
REPLAY_DROP_THRESHOLD = 0.2


def _now() -> datetime:
    return datetime.now(timezone.utc).astimezone()


def _load_json() -> list:
    if not os.path.isfile(SNAPSHOT_FILE):
        return []
    try:
        with open(SNAPSHOT_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:  # noqa: BLE001
        return []


def _load() -> list:
    try:
        return pg_store.load_json_list("agent_skill_metrics", SNAPSHOT_FILE, _load_json)
    except Exception:  # noqa: BLE001
        return []


def _summary(metrics: dict) -> dict:
    """快照关键计数的摘要（覆盖前留痕用，只含聚合数字，不含任何患者或业务明细）。"""
    return {
        "experiences": _get(metrics, "experiences", "total"),
        "proposals": _get(metrics, "proposals", "total"),
        "adopted": _get(metrics, "proposals", "adopted"),
        "published": _get(metrics, "skillCards", "published"),
        "adoptionRate": _get(metrics, "proposals", "adoptionRate"),
    }


def snapshot(actor: str, day: date | None = None) -> dict:
    """生成/更新某业务日的指标快照（同一天幂等）。

    评审 03-S12：同日重写原本**不留任何痕迹**，"当天原始值"与"事后补录值"无法区分。
    现在保留首次生成信息（``firstGeneratedAt`` / ``firstGeneratedBy``），并记录
    ``overwritten``、``overwriteCount`` 与覆盖前摘要（``previous``）。
    """
    day = day or _now().date()
    key = day.isoformat()
    with _lock:
        previous = next((i for i in _load() if i.get("id") == key), None)
        item = {
            "id": key,
            "date": key,
            "generatedAt": _now().isoformat(timespec="seconds"),
            "generatedBy": actor,
            "metrics": skill_ops.metrics(),
        }
        if previous:
            item.update({
                "overwritten": True,
                "overwriteCount": int(previous.get("overwriteCount") or 0) + 1,
                "firstGeneratedAt": previous.get("firstGeneratedAt")
                or previous.get("generatedAt"),
                "firstGeneratedBy": previous.get("firstGeneratedBy")
                or previous.get("generatedBy"),
                "previous": {
                    "generatedAt": previous.get("generatedAt"),
                    "generatedBy": previous.get("generatedBy"),
                    "summary": _summary(previous.get("metrics") or {}),
                },
            })
        else:
            item.update({
                "overwritten": False,
                "overwriteCount": 0,
                "firstGeneratedAt": item["generatedAt"],
                "firstGeneratedBy": actor,
            })
        # G96：按日期主键行级 upsert，多实例并发只覆盖同一天，不丢其它日期
        pg_store.upsert_json_item("agent_skill_metrics", SNAPSHOT_FILE, item)
    return item


def list_snapshots(days: int = 30) -> list[dict]:
    size = max(1, min(int(days or 30), MAX_SNAPSHOTS))
    items = sorted(_load(), key=lambda i: i.get("date") or "")
    return items[-size:]


def trends(days: int = 30) -> dict:
    """趋势：历史不足 2 天时显式 insufficient，不伪造曲线。"""
    snaps = list_snapshots(days)
    if len(snaps) < 2:
        return {
            "sufficient": False,
            "snapshots": snaps,
            "deltas": None,
            "note": f"历史快照不足（当前 {len(snaps)} 天，至少需要 2 天），暂不计算趋势。",
        }
    first, last = snaps[0], snaps[-1]
    a, b = first.get("metrics") or {}, last.get("metrics") or {}
    deltas = {
        "experiences": _get(b, "experiences", "total") - _get(a, "experiences", "total"),
        "proposals": _get(b, "proposals", "total") - _get(a, "proposals", "total"),
        "adopted": _get(b, "proposals", "adopted") - _get(a, "proposals", "adopted"),
        "published": _get(b, "skillCards", "published") - _get(a, "skillCards", "published"),
        "unusedPublished": len(_get(b, "skillCards", "unusedPublished") or [])
        - len(_get(a, "skillCards", "unusedPublished") or []),
        "adoptionRate": round(_get(b, "proposals", "adoptionRate")
                              - _get(a, "proposals", "adoptionRate"), 3),
        "replayPassRate": round(_get(b, "proposals", "replayPassRate")
                                - _get(a, "proposals", "replayPassRate"), 3),
    }
    return {
        "sufficient": True,
        "snapshots": snaps,
        "deltas": deltas,
        "note": f"对比 {first.get('date')} → {last.get('date')}；差值为原值相减。",
    }


def _get(metrics: dict, section: str, key: str):
    return (metrics.get(section) or {}).get(key)


def alerts(metrics: dict | None = None) -> dict:
    """阈值告警：当前指标 + 相邻快照回放通过率下降。不自动处置。

    评审 01-S4：可传入已算好的 ``metrics``，让一次抓取/一次建议生成只加载一次域数据
    （此前 ``prometheus_text()`` 与 ``skill_advice`` 各自重复调用 ``skill_ops.metrics()``）。
    """
    m = metrics if metrics is not None else skill_ops.metrics()
    out: list[dict] = []

    def add(level: str, code: str, message: str, metric: str, value, threshold) -> None:
        out.append({"level": level, "code": code, "message": message,
                    "metric": metric, "value": value, "threshold": threshold})

    replay_checked = (m["proposals"] or {}).get("replayChecked", 0)
    replay_rate = (m["proposals"] or {}).get("replayPassRate", 0)
    if replay_checked >= MIN_REPLAY_CHECKS and replay_rate < MIN_REPLAY_RATE:
        add("high", "REPLAY_PASS_RATE_LOW",
            f"回放通过率 {replay_rate:.0%} 低于阈值 {MIN_REPLAY_RATE:.0%}",
            "proposals.replayPassRate", replay_rate, MIN_REPLAY_RATE)

    proposals = (m["proposals"] or {}).get("total", 0)
    adoption = (m["proposals"] or {}).get("adoptionRate", 0)
    if proposals >= MIN_PROPOSALS_FOR_ADOPTION and adoption < MIN_ADOPTION_RATE:
        add("medium", "ADOPTION_RATE_LOW",
            f"候选采纳率 {adoption:.0%} 低于阈值 {MIN_ADOPTION_RATE:.0%}，可能存在积压",
            "proposals.adoptionRate", adoption, MIN_ADOPTION_RATE)

    unused = len((m["skillCards"] or {}).get("unusedPublished") or [])
    if unused >= MAX_UNUSED_PUBLISHED:
        add("medium", "UNUSED_PUBLISHED_SKILLS",
            f"有 {unused} 个生效技能未被任何 Bot 引用",
            "skillCards.unusedPublished", unused, MAX_UNUSED_PUBLISHED)

    proposed = (m["proposals"] or {}).get("proposed", 0)
    if proposed >= MAX_PROPOSED_BACKLOG:
        add("low", "PROPOSAL_BACKLOG",
            f"待处理候选 {proposed} 个，建议安排审核",
            "proposals.proposed", proposed, MAX_PROPOSED_BACKLOG)

    exp_total = (m["experiences"] or {}).get("total", 0)
    coverage = (m["experiences"] or {}).get("coverageRate", 0)
    if exp_total >= MIN_EXPERIENCES_FOR_COVERAGE and coverage < MIN_COVERAGE_RATE:
        add("low", "EXPERIENCE_COVERAGE_LOW",
            f"经验覆盖率 {coverage:.0%} 低于阈值 {MIN_COVERAGE_RATE:.0%}",
            "experiences.coverageRate", coverage, MIN_COVERAGE_RATE)

    snaps = list_snapshots(2)
    if len(snaps) >= 2:
        prev = (snaps[0].get("metrics") or {}).get("proposals") or {}
        cur = (snaps[1].get("metrics") or {}).get("proposals") or {}
        drop = round((prev.get("replayPassRate") or 0) - (cur.get("replayPassRate") or 0), 3)
        if drop >= REPLAY_DROP_THRESHOLD:
            add("high", "REPLAY_PASS_RATE_DROP",
                f"回放通过率较上次快照下降 {drop:.0%}",
                "proposals.replayPassRate", cur.get("replayPassRate"), prev.get("replayPassRate"))

    return {
        "generatedAt": _now().isoformat(timespec="seconds"),
        "alerts": out,
        "levelCounts": {lvl: sum(1 for a in out if a["level"] == lvl)
                        for lvl in ("high", "medium", "low")},
        "note": "告警仅为运营提示，不自动停用/修改任何技能或 Bot。",
    }


def prometheus_text() -> str:
    """Prometheus 文本导出（只含聚合指标，不含任何业务标识/PHI）。"""
    m = skill_ops.metrics()
    # 评审 01-S4：复用同一次加载结果，避免一次抓取触发两遍域读取
    alert_data = alerts(m)
    counts = alert_data["levelCounts"]
    cards = (m.get("skillCards") or {}).get("byStatus") or {}
    lines = [
        "# HELP medassist_agent_experiences_total Approved skill experiences.",
        "# TYPE medassist_agent_experiences_total gauge",
        f"medassist_agent_experiences_total {m['experiences']['total']}",
        "# HELP medassist_agent_experience_coverage_ratio Experiences covered by proposals.",
        "# TYPE medassist_agent_experience_coverage_ratio gauge",
        f"medassist_agent_experience_coverage_ratio {m['experiences']['coverageRate']}",
        "# HELP medassist_agent_proposals_total Skill proposals by status.",
        "# TYPE medassist_agent_proposals_total gauge",
    ]
    # 评审 02-S10：从 byStatus 动态导出全部状态（未来新增 WITHDRAWN/EXPIRED 也不会漏）；
    # 三个已知状态即使为 0 也保留样本，保证告警表达式的指标名始终存在。
    status_counts = {str(k).lower(): v
                     for k, v in ((m.get("proposals") or {}).get("byStatus") or {}).items()}
    for known in ("proposed", "adopted", "rejected"):
        status_counts.setdefault(known, 0)
    for status in sorted(status_counts):
        lines.append(f'medassist_agent_proposals_total{{status="{status}"}} '
                     f'{status_counts[status]}')
    lines += [
        "# HELP medassist_agent_proposal_adoption_ratio Adopted proposals / all proposals.",
        "# TYPE medassist_agent_proposal_adoption_ratio gauge",
        f"medassist_agent_proposal_adoption_ratio {m['proposals']['adoptionRate']}",
        "# HELP medassist_agent_replay_checks_total Proposals with replay checks.",
        "# TYPE medassist_agent_replay_checks_total gauge",
        f"medassist_agent_replay_checks_total {m['proposals']['replayChecked']}",
        "# HELP medassist_agent_replay_pass_ratio Replay pass ratio.",
        "# TYPE medassist_agent_replay_pass_ratio gauge",
        f"medassist_agent_replay_pass_ratio {m['proposals']['replayPassRate']}",
        "# HELP medassist_agent_skill_cards Skill cards by status.",
        "# TYPE medassist_agent_skill_cards gauge",
    ]
    for status in ("DRAFT", "IN_REVIEW", "PUBLISHED", "REJECTED", "DISABLED"):
        lines.append(f'medassist_agent_skill_cards{{status="{status.lower()}"}} '
                     f'{cards.get(status, 0)}')
    lines += [
        "# HELP medassist_agent_skill_review_pass_ratio Published+disabled / decided.",
        "# TYPE medassist_agent_skill_review_pass_ratio gauge",
        f"medassist_agent_skill_review_pass_ratio {m['skillCards']['reviewPassRate']}",
        "# HELP medassist_agent_unused_published_skills Published skills without bots.",
        "# TYPE medassist_agent_unused_published_skills gauge",
        f"medassist_agent_unused_published_skills "
        f"{len(m['skillCards']['unusedPublished'])}",
        "# HELP medassist_agent_bots_total Bots by status.",
        "# TYPE medassist_agent_bots_total gauge",
        f"medassist_agent_bots_total {m['bots']['total']}",
        f"medassist_agent_bots_published {m['bots']['published']}",
        "# HELP medassist_agent_bot_teams_total Bot teams.",
        "# TYPE medassist_agent_bot_teams_total gauge",
        f"medassist_agent_bot_teams_total {m['bots']['teams']}",
        "# HELP medassist_agent_operational_alerts Operational alerts by level.",
        "# TYPE medassist_agent_operational_alerts gauge",
        f'medassist_agent_operational_alerts{{level="high"}} {counts["high"]}',
        f'medassist_agent_operational_alerts{{level="medium"}} {counts["medium"]}',
        f'medassist_agent_operational_alerts{{level="low"}} {counts["low"]}',
    ]
    return "\n".join(lines) + "\n"
