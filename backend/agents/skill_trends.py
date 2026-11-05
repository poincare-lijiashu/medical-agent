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


def _save(items: list) -> None:
    pg_store.save_json_list("agent_skill_metrics", SNAPSHOT_FILE, items)


def snapshot(actor: str, day: date | None = None) -> dict:
    """生成/更新某业务日的指标快照（同一天幂等）。"""
    day = day or _now().date()
    key = day.isoformat()
    item = {
        "id": key,
        "date": key,
        "generatedAt": _now().isoformat(timespec="seconds"),
        "generatedBy": actor,
        "metrics": skill_ops.metrics(),
    }
    with _lock:
        items = [i for i in _load() if i.get("id") != key]
        items.append(item)
        items.sort(key=lambda i: i.get("date") or "")
        _save(items[-MAX_SNAPSHOTS:])
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


def alerts() -> dict:
    """阈值告警：当前指标 + 相邻快照回放通过率下降。不自动处置。"""
    m = skill_ops.metrics()
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
