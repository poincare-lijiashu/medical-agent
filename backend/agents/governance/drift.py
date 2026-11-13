"""§296 漂移与性能监测（计划书 W8.3）。

数据来源：进程内问答计量（``consult_metrics``），按能力域聚合，**不含问题/回答/患者信息**。
判定规则（阈值与前端同源，避免"页面说正常、告警说异常"）：
- 样本不足 20 次 → ``INSUFFICIENT``，不产生漂移信号；
- 错误率 > 20%（或较基线上升 > 10 个百分点）、中止率 > 20% → 漂移信号；
- 质量风险信号率 > 30%（或较基线上升 > 10 个百分点）→ 漂移信号；
- 平均耗时 > 基线 1.5 倍且 > 3s、token/请求 > 基线 1.5 倍 → 漂移信号；
- 请求量较基线下降 > 50% → 流量骤降提示（进程重启会归零，需结合基线时间判读）。

边界：进程内计量在重启后归零；本模块的基线是"某一时刻的进程快照"，用于趋势比较，
不是跨重启的累计值。生产应以 Prometheus 近 30 分钟窗口为主、本模块为辅。
"""
from __future__ import annotations

from backend.agents import catalog, consult_metrics
from backend.agents.governance import common
from backend.agents.governance.common import (
    GovernanceInvalid,
    GovernanceNotFound,
)

MIN_REQUESTS = 20
MAX_SNAPSHOTS = 2000

THRESHOLDS = {
    "minRequests": MIN_REQUESTS,
    "errorRateHigh": 0.2,
    "errorRateAlert": 0.3,
    "errorRateDelta": 0.1,
    "abortedRateHigh": 0.2,
    "qualityFlagRateHigh": 0.3,
    "qualityFlagDelta": 0.1,
    "latencyRatio": 1.5,
    "latencyFloorMs": 3000,
    "latencyAlertRatio": 2.0,
    "latencyAlertMs": 5000,
    "tokenRatio": 1.5,
    "trafficDropRatio": 0.5,
}


def _snapshots() -> list[dict]:
    return common.load("snapshots")


def _baselines() -> list[dict]:
    return common.load("baselines")


def _baseline(agent_id: str) -> dict | None:
    for item in _baselines():
        if item.get("agentId") == agent_id:
            return item
    return None


def _ratio(part: int, total: int) -> float:
    return round(part / total, 4) if total else 0.0


def _aggregate(snap: dict) -> dict[str, dict]:
    """把 consult_metrics.snapshot() 聚合成按能力域的指标。"""
    agents: dict[str, dict] = {}

    def bucket(agent: str) -> dict:
        return agents.setdefault(agent, {
            "requests": 0, "ok": 0, "error": 0, "aborted": 0,
            "qualityFlagged": 0, "latencySumMs": 0.0, "latencyCount": 0,
            "tokensInput": 0, "tokensOutput": 0, "tokenizedRequests": 0,
        })

    for key, value in (snap.get("requests") or {}).items():
        parts = key.split("|")
        if len(parts) != 3:
            continue
        agent, _channel, status = parts
        item = bucket(agent)
        item["requests"] += int(value)
        if status in ("ok", "error", "aborted"):
            item[status] += int(value)

    for key, value in (snap.get("quality") or {}).items():
        parts = key.split("|")
        if len(parts) != 2:
            continue
        bucket(parts[0])["qualityFlagged"] += int(value)

    for key, value in (snap.get("agent_durations") or {}).items():
        parts = key.split("|")
        if len(parts) != 2:
            continue
        item = bucket(parts[0])
        item["latencySumMs"] += float(value.get("sum_ms") or 0)
        item["latencyCount"] += int(value.get("count") or 0)

    for key, value in (snap.get("agent_tokens") or {}).items():
        parts = key.split("|")
        if len(parts) != 2:
            continue
        item = bucket(parts[0])
        if parts[1] == "input":
            item["tokensInput"] += int(value)
        elif parts[1] == "output":
            item["tokensOutput"] += int(value)

    for agent, value in (snap.get("agent_tokenized") or {}).items():
        bucket(agent)["tokenizedRequests"] += int(value)
    return agents


def _metrics(agent: str, raw: dict) -> dict:
    total = raw["requests"]
    ok = raw["ok"]
    latency = (raw["latencySumMs"] / raw["latencyCount"]) if raw["latencyCount"] else 0.0
    token_total = raw["tokensInput"] + raw["tokensOutput"]
    tokens_per_request = (token_total / raw["tokenizedRequests"]) if raw["tokenizedRequests"] else 0.0
    return {
        "agentId": agent,
        "requests": total,
        "ok": ok,
        "error": raw["error"],
        "aborted": raw["aborted"],
        "errorRate": _ratio(raw["error"], total),
        "abortedRate": _ratio(raw["aborted"], total),
        "qualityFlagged": raw["qualityFlagged"],
        "qualityFlagRate": _ratio(raw["qualityFlagged"], ok),
        "avgLatencyMs": round(latency, 1),
        "tokensInput": raw["tokensInput"],
        "tokensOutput": raw["tokensOutput"],
        "tokensPerRequest": round(tokens_per_request, 1),
        "sampleCount": raw["latencyCount"],
    }


def _signals(current: dict, baseline: dict | None) -> list[dict]:
    signals: list[dict] = []

    def add(code: str, severity: str, message: str, current_value, baseline_value=None,
            delta=None) -> None:
        signals.append({"code": code, "severity": severity, "message": message,
                        "current": current_value, "baseline": baseline_value,
                        "delta": delta})

    if current["errorRate"] > THRESHOLDS["errorRateHigh"]:
        severity = "HIGH" if current["errorRate"] > THRESHOLDS["errorRateAlert"] else "MEDIUM"
        add("ERROR_RATE_HIGH", severity, "问答错误率超过阈值", current["errorRate"])
    if current["abortedRate"] > THRESHOLDS["abortedRateHigh"]:
        add("ABORTED_RATE_HIGH", "MEDIUM", "问答中止率超过阈值", current["abortedRate"])
    if current["qualityFlagRate"] > THRESHOLDS["qualityFlagRateHigh"]:
        add("QUALITY_FLAG_HIGH", "MEDIUM", "回答质量风险信号率超过阈值",
            current["qualityFlagRate"])
    if baseline:
        error_delta = round(current["errorRate"] - (baseline.get("errorRate") or 0), 4)
        if error_delta > THRESHOLDS["errorRateDelta"]:
            add("ERROR_RATE_UP", "HIGH" if error_delta > 0.2 else "MEDIUM",
                "错误率较基线明显上升", current["errorRate"],
                baseline.get("errorRate"), error_delta)
        quality_delta = round(current["qualityFlagRate"] - (baseline.get("qualityFlagRate") or 0), 4)
        if quality_delta > THRESHOLDS["qualityFlagDelta"]:
            add("QUALITY_FLAG_UP", "MEDIUM", "质量风险信号率较基线上升",
                current["qualityFlagRate"], baseline.get("qualityFlagRate"), quality_delta)
        base_latency = float(baseline.get("avgLatencyMs") or 0)
        if (base_latency > 0
                and current["avgLatencyMs"] > base_latency * THRESHOLDS["latencyRatio"]
                and current["avgLatencyMs"] > THRESHOLDS["latencyFloorMs"]):
            severity = ("HIGH"
                        if current["avgLatencyMs"] > base_latency * THRESHOLDS["latencyAlertRatio"]
                        and current["avgLatencyMs"] > THRESHOLDS["latencyAlertMs"]
                        else "MEDIUM")
            add("LATENCY_UP", severity, "平均耗时较基线明显上升",
                current["avgLatencyMs"], base_latency)
        base_tokens = float(baseline.get("tokensPerRequest") or 0)
        if (base_tokens > 0
                and current["tokensPerRequest"] > base_tokens * THRESHOLDS["tokenRatio"]):
            add("TOKEN_UP", "MEDIUM", "单次问答 token 用量较基线上升",
                current["tokensPerRequest"], base_tokens)
        base_requests = int(baseline.get("requests") or 0)
        if base_requests >= MIN_REQUESTS and current["requests"] < base_requests * THRESHOLDS["trafficDropRatio"]:
            add("TRAFFIC_DROP", "MEDIUM", "请求量较基线下降超过 50%（进程重启也会归零）",
                current["requests"], base_requests)
    return signals


def capture(agent_ids=None, actor: str = "system") -> dict:
    """抓取当前进程计量为漂移快照；``agent_ids`` 为空时抓有流量的全部能力域。"""
    snap = consult_metrics.snapshot()
    aggregated = _aggregate(snap)
    requested = []
    if agent_ids:
        if not isinstance(agent_ids, list):
            raise GovernanceInvalid("agentIds 必须是数组")
        for value in agent_ids:
            agent = common.require_code(value, "agentId").lower()
            if catalog.get_agent(agent) is None:
                raise GovernanceInvalid("agentId 不在智能体目录中")
            requested.append(agent)
    known = {a for a in aggregated if catalog.get_agent(a) is not None}
    targets = sorted(set(requested) if requested else (known or set()))
    if not targets:
        return {"captured": [], "thresholds": dict(THRESHOLDS),
                "note": "当前进程还没有可归属到能力域的问答计量"}

    created = []
    with common.lock:
        items = _snapshots()
        for agent in targets:
            metrics = _metrics(agent, aggregated.get(agent) or {
                "requests": 0, "ok": 0, "error": 0, "aborted": 0,
                "qualityFlagged": 0, "latencySumMs": 0.0, "latencyCount": 0,
                "tokensInput": 0, "tokensOutput": 0, "tokenizedRequests": 0,
            })
            base = _baseline(agent)
            base_metrics = (base or {}).get("metrics")
            signals = _signals(metrics, base_metrics)
            if metrics["requests"] < MIN_REQUESTS:
                status = "INSUFFICIENT"
                signals = []
            elif any(s["severity"] == "HIGH" for s in signals):
                status = "ALERT"
            elif signals:
                status = "WATCH"
            else:
                status = "OK"
            item = {
                "snapshotId": common.new_id("drift"),
                "agentId": agent,
                "capturedAt": common.now_iso(),
                "window": "process",
                "metrics": metrics,
                "baseline": ({"snapshotId": base.get("snapshotId"),
                              "setAt": base.get("setAt"),
                              "metrics": base_metrics} if base else None),
                "signals": signals,
                "status": status,
                "capturedBy": actor,
            }
            items.append(item)
            created.append(item)
        items = items[-MAX_SNAPSHOTS:]
        common.save("snapshots", items)
    return {"captured": created, "thresholds": dict(THRESHOLDS),
            "note": "进程内计量（重启归零），趋势判读以 Prometheus 近 30 分钟窗口为主"}


def set_baseline(agent_id: str, snapshot_id: str, actor: str) -> dict:
    agent = common.require_code(agent_id, "agentId").lower()
    with common.lock:
        snapshots = _snapshots()
        snapshot = next((s for s in snapshots if s.get("snapshotId") == snapshot_id), None)
        if snapshot is None:
            raise GovernanceNotFound("未找到该漂移快照")
        if snapshot.get("agentId") != agent:
            raise GovernanceInvalid("快照不属于该能力域")
        if (snapshot.get("metrics") or {}).get("requests", 0) < MIN_REQUESTS:
            raise GovernanceInvalid(f"样本不足 {MIN_REQUESTS} 次，不能作为基线")
        baselines = _baselines()
        item = {
            "agentId": agent,
            "snapshotId": snapshot_id,
            "metrics": snapshot.get("metrics"),
            "setBy": actor,
            "setAt": common.now_iso(),
            "status": "ACTIVE",
        }
        baselines = [b for b in baselines if b.get("agentId") != agent]
        baselines.append(item)
        common.save("baselines", baselines)
        return item


def report(agent_id: str | None = None, limit: int = 50) -> dict:
    try:
        limit = max(1, min(int(limit), 200))
    except (TypeError, ValueError):
        limit = 50
    snapshots = _snapshots()
    if agent_id:
        agent = common.require_code(agent_id, "agentId").lower()
        snapshots = [s for s in snapshots if s.get("agentId") == agent]
    snapshots.sort(key=lambda s: (s.get("capturedAt") or ""), reverse=True)

    grouped: dict[str, list[dict]] = {}
    for item in snapshots:
        grouped.setdefault(item.get("agentId") or "", []).append(item)

    agents = []
    for agent in sorted(grouped):
        history = grouped[agent]
        agents.append({
            "agentId": agent,
            "latest": history[0],
            "baseline": _baseline(agent),
            "history": history[:20],
        })
        if len(agents) >= limit:
            break
    return {
        "generatedAt": common.now_iso(),
        "window": "process",
        "thresholds": dict(THRESHOLDS),
        "agents": agents,
        "note": "进程内计量（重启归零）；基线是某一时刻的快照，用于同窗口趋势比较",
    }


def latest_by_agent() -> list[dict]:
    snapshots = _snapshots()
    latest: dict[str, dict] = {}
    for item in snapshots:
        agent = item.get("agentId") or ""
        if agent and (agent not in latest
                      or (item.get("capturedAt") or "") > (latest[agent].get("capturedAt") or "")):
            latest[agent] = item
    return sorted(latest.values(), key=lambda i: i.get("agentId") or "")

