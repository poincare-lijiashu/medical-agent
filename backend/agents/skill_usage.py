"""G103 技能使用率看板数据（只读聚合）。

把 G102 的进程内计量与当前已发布技能卡对齐，回答三个运营问题：
1. 有多少已发布技能真正被问答用过（覆盖率）；
2. 每个技能被用多少次、最近一次是什么时候；
3. 问答本身的成功率/错误率/中止率与平均耗时。

边界：
- 数据窗口是**当前进程**（重启归零），不落库、不含问题/回答/患者信息；
- 使用率 = 被成功问答引用的已发布技能数 / 已发布技能数；失败与中止不计使用；
- 已停用/已删除但仍被历史计量到的技能，单独归入 ``usedNotPublishedInProcess``，不混入分母。

口径澄清（2026-11-06 评审 M-26 / M-27 整改）——"未被使用"有三个不同含义，**不要混用**：
1. ``unusedInProcess``（本模块）：本进程内从未被成功问答引用；
2. ``skillCards.unusedPublished``（skill_ops）：未被任何 Bot 绑定引用——告警与建议用它；
3. 已停用/已删除：用 ``usedNotPublishedInProcess`` 单独归类。
另外：默认问答会应用该能力域**全部**已发布技能卡，因此 ``usageRatio`` 在有流量的实例上
天然接近 1，**不具备判别力**；判断"技能是否真被需要"请看 ``dominantShare`` / ``concentration``
（引用集中度）与 ``unusedInProcess``。
"""
from __future__ import annotations

from datetime import datetime, timezone

from backend.agents import consult_metrics, skills

_WINDOW_NOTE = (
    "进程内累计（服务重启归零）；使用率=被成功问答引用的已发布技能数/已发布技能数；"
    "失败与中止不计技能使用；不含患者信息。"
)


def _iso(ts: float | None) -> str | None:
    if not ts:
        return None
    return datetime.fromtimestamp(ts, tz=timezone.utc).astimezone().isoformat(
        timespec="seconds")


def _ratio(part: int, total: int) -> float:
    return round(part / total, 4) if total else 0.0


def report() -> dict:
    """构建看板数据（只读，不修改任何计量或技能状态）。"""
    snap = consult_metrics.snapshot()
    published = {s["code"]: s for s in skills.list_skills("admin", "PUBLISHED")}
    usage = snap["skills"]
    last_used = snap["skill_last_used"]

    channels: dict[str, dict] = {}
    agents: dict[str, dict] = {}
    for key, n in snap["requests"].items():
        agent, channel, status = key.split("|")
        for bucket in (channels.setdefault(channel, {"total": 0, "ok": 0,
                                                     "error": 0, "aborted": 0}),
                       agents.setdefault(agent, {"total": 0, "ok": 0,
                                                 "error": 0, "aborted": 0})):
            bucket["total"] += n
            bucket[status] += n

    ok_total = sum(v["ok"] for v in channels.values())
    total = sum(v["total"] for v in channels.values())
    error = sum(v["error"] for v in channels.values())
    aborted = sum(v["aborted"] for v in channels.values())

    durations = snap["durations"]
    dur_sum = sum(v["sum_ms"] for v in durations.values())
    dur_count = sum(v["count"] for v in durations.values())

    published_count = len(published)
    used_published = [c for c in usage if c in published]
    unused_in_process = sorted(c for c in published if c not in usage)
    used_not_published = sorted(c for c in usage if c not in published)

    # 评审 M-27：引用集中度比"覆盖率"更能反映技能是否真被需要
    counts = [usage.get(c, 0) for c in published]
    references = sum(counts)
    dominant = max(counts) if counts else 0
    concentration = (round(sum((n / references) ** 2 for n in counts), 4)
                     if references else 0.0)

    items = []
    for code in sorted(set(published) | set(usage),
                       key=lambda c: (c not in published, -usage.get(c, 0), c)):
        card = published.get(code)
        count = usage.get(code, 0)
        items.append({
            "code": code,
            "name": (card or {}).get("name") or code,
            "agentId": (card or {}).get("agentId") or "",
            "version": (card or {}).get("version"),
            "published": card is not None,
            "usage": count,
            # 评审 01-S5：一个请求可应用多张技能卡，该值可 >1，不是"占比"，
            # 而是"每成功问答平均被引用次数"；旧名 usageShare 已废弃（易被误读为百分比）。
            "perRequestAvg": _ratio(count, ok_total),
            "lastUsedAt": _iso(last_used.get(code)),
        })

    return {
        "window": "process",
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(
            timespec="seconds"),
        "requests": {
            "total": total,
            "ok": ok_total,
            "error": error,
            "aborted": aborted,
            "successRate": _ratio(ok_total, total),
            "errorRate": _ratio(error, total),
            "abortedRate": _ratio(aborted, total),
            "channels": channels,
            "agents": agents,
        },
        "duration": {
            "avgMs": round(dur_sum / dur_count, 1) if dur_count else 0.0,
            "byChannel": {
                ch: {"avgMs": round(v["sum_ms"] / v["count"], 1) if v["count"] else 0.0,
                     "count": v["count"]}
                for ch, v in durations.items()
            },
        },
        "tokens": {
            "input": snap["tokens"]["input"],
            "output": snap["tokens"]["output"],
            "total": snap["tokens"]["input"] + snap["tokens"]["output"],
            "tokenizedRequests": snap["tokens"]["requests"],
            "coverageRatio": _ratio(snap["tokens"]["requests"], ok_total),
        },
        "skills": {
            "published": published_count,
            "used": len(used_published),
            # 保留字段：有流量时接近 1，仅作"是否有任何技能被执行"的下限信号
            "usageRatio": _ratio(len(used_published), published_count),
            "usageRatioNote": "默认应用该能力域全部已发布技能，覆盖率在有流量时接近 1，"
                              "不具备判别力；请结合 dominantShare/concentration 判读",
            "skillReferences": references,
            "dominantShare": _ratio(dominant, references),
            "concentration": concentration,  # Herfindahl 指数：1=全部集中在一个技能
            "unusedInProcess": unused_in_process,
            "usedNotPublishedInProcess": used_not_published,
            "definitions": {
                "unusedInProcess": "本进程内从未被成功问答引用",
                "unusedPublished": "未被任何 Bot 绑定（见 skill-library/metrics，告警用它）",
                "usedNotPublishedInProcess": "已停用/已删除但仍被历史计量到",
            },
            "items": items,
        },
        # 评审 01-S7：区分"无数据"与"0%"——进程重启后计量归零，0% 会被误读成
        # "完全没人用"。dataAvailable=false 时前端应显示"暂无数据"而不是 0。
        "dataAvailable": total > 0,
        "dataNote": None if total > 0 else
        "当前进程内尚无问答计量（服务重启后归零），以下比率不代表业务真实使用情况",
        "note": _WINDOW_NOTE,
    }
