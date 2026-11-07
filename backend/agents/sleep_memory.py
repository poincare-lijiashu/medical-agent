"""G90 睡眠记忆整理（sleep-time consolidation，只读建议）。

定位：在"空闲时"对 Bot 长期记忆做一次**治理体检**，产出可复核的候选建议；
**绝不自动修改记忆、绝不生成临床结论、绝不改模型权重**。确认/删除仍在 Bot
记忆面板由人完成。

为什么不做"从对话自动抽取记忆"：
- 会话里含患者信息与一次性上下文，自动抽取极易把 PHI 沉淀为长期记忆；
- 无人工确认的"自动记住"在医疗场景不可接受。
因此本模块只处理**已经存在、已经过人工候选流程**的记忆，做一致性/时效性整理。

检查项：
1. ``EXPIRED_CONFIRMED``：已确认但已过期 → 建议归档/删除；
2. ``STALE_CANDIDATE``：候选超过 14 天未确认 → 建议确认或丢弃；
3. ``DUPLICATE_VALUE``：同一 Bot 内两条已确认记忆内容完全相同 → 建议合并；
4. ``NEAR_DUPLICATE``：同 Bot 内内容高度相似（二元组 Jaccard ≥0.85）→ 建议复核合并；
5. ``CROSS_BOT_CONFLICT``：不同 Bot 使用同一 key 但值不同 → 建议人工判断是否口径冲突。
"""
from __future__ import annotations

import json
import os
import re
import threading
import uuid
from datetime import datetime, timezone

from backend.agents import bot_memory
from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPORT_FILE = os.path.join(BASE_DIR, "data", "sleep_memory_reports.json")

STALE_CANDIDATE_DAYS = 14
NEAR_DUP_THRESHOLD = 0.85
MAX_REPORTS = 20
MAX_FINDINGS = 200

_PUNCT = re.compile(r"[\s\u3000。，、；：！？,.!?;:\"'“”‘’（）()\[\]【】<>《》/\\|—\-_=+*#`~]+")

_lock = threading.RLock()


def _now() -> datetime:
    return datetime.now(timezone.utc).astimezone()


def _norm(text: str) -> str:
    return _PUNCT.sub("", (text or "").lower())


def _bigrams(text: str) -> set[str]:
    s = _norm(text)
    if len(s) < 2:
        return {s} if s else set()
    return {s[i:i + 2] for i in range(len(s) - 1)}


def _jaccard(a: str, b: str) -> float:
    sa, sb = _bigrams(a), _bigrams(b)
    union = sa | sb
    return round(len(sa & sb) / len(union), 3) if union else 0.0


def _jaccard_sets(sa: set[str], sb: set[str]) -> float:
    """已预算 bigram 集合的 Jaccard（评审 01-S8：两两比较时不再重复构建字符集）。"""
    union = sa | sb
    return round(len(sa & sb) / len(union), 3) if union else 0.0


def _age_days(created_at: str, now: datetime) -> int:
    try:
        ts = datetime.fromisoformat(created_at)
    except (TypeError, ValueError):
        return 0
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    return max(0, (now - ts).days)


def _load_reports_json() -> list:
    """JSON 兜底读（PG 不可用或表为空时由 ``load_json_list`` 调用）。"""
    if not os.path.isfile(REPORT_FILE):
        return []
    try:
        with open(REPORT_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:  # noqa: BLE001
        return []


def _load_reports() -> list:
    """报告读 PG 真源（JSON 兜底），并按生成时间升序。

    评审 M-39：此前只读写 JSON（还直接调 ``pg_store._write_json_atomic``），
    多实例下 A 实例生成的报告 B 实例看不到；PG 读默认 ``updated_at DESC``，
    这里按 ``generatedAt`` 升序还原"旧→新"，保证 ``last_report()`` 取最新一条。
    """
    items = pg_store.load_json_list("agent_sleep_reports", REPORT_FILE, _load_reports_json)
    return sorted(items, key=lambda r: str(r.get("generatedAt") or ""))


def _save_report(report: dict) -> None:
    reports = _load_reports()
    reports.append(report)
    # 评审 M-39：走通用 JSON 列表域（PG 真源 + JSON 兜底），不再直接碰私有写函数
    pg_store.save_json_list("agent_sleep_reports", REPORT_FILE, reports[-MAX_REPORTS:])


def _finding(kind: str, severity: str, bot: str, memories: list[dict],
             reason: str, suggestion: str) -> dict:
    return {
        "kind": kind,
        "severity": severity,
        "botCode": bot,
        "memoryIds": [m.get("id") for m in memories],
        "keys": [m.get("key") for m in memories],
        "values": [m.get("value") for m in memories],
        "reason": reason,
        "suggestion": suggestion,
    }


def scan(bot_codes: list[str] | None = None, now: datetime | None = None,
         generated_by: str = "system") -> dict:
    """执行一次只读体检并落一份报告；返回报告（findings/summary）。"""
    now = now or _now()
    wanted = {str(c).strip() for c in (bot_codes or []) if str(c).strip()}
    memories = [m for m in bot_memory.all_memories()
                if not wanted or m.get("botCode") in wanted]

    findings: list[dict] = []
    for mem in memories:
        bot = mem.get("botCode")
        if mem.get("status") == "CONFIRMED" and bot_memory.is_expired(mem, now.date()):
            findings.append(_finding(
                "EXPIRED_CONFIRMED", "medium", bot, [mem],
                f"记忆已过期（expiresAt={mem.get('expiresAt')}），仍处于已确认状态",
                "归档或删除，避免继续注入提示"))
        elif mem.get("status") == "CANDIDATE":
            age = _age_days(mem.get("createdAt") or "", now)
            if age >= STALE_CANDIDATE_DAYS:
                findings.append(_finding(
                    "STALE_CANDIDATE", "low", bot, [mem],
                    f"候选记忆已 {age} 天未确认",
                    "确认后生效，或删除以清理候选"))

    # 同 Bot 内重复/近似重复（只看已确认）
    by_bot: dict[str, list[dict]] = {}
    for mem in memories:
        if mem.get("status") == "CONFIRMED":
            by_bot.setdefault(mem.get("botCode"), []).append(mem)
    for bot, items in by_bot.items():
        # 评审 01-S8：归一化文本与 bigram 集合各预计算一次（O(n²) 次比较不再重复构建字符集）
        prepared = [(_norm(m.get("value")), _bigrams(m.get("value")), m) for m in items]
        for i in range(len(prepared)):
            for j in range(i + 1, len(prepared)):
                norm_a, bigrams_a, a = prepared[i]
                norm_b, bigrams_b, b = prepared[j]
                if norm_a == norm_b:
                    findings.append(_finding(
                        "DUPLICATE_VALUE", "medium", bot, [a, b],
                        "两条已确认记忆内容完全相同",
                        "保留一条，删除另一条"))
                else:
                    score = _jaccard_sets(bigrams_a, bigrams_b)
                    if score >= NEAR_DUP_THRESHOLD:
                        findings.append(_finding(
                            "NEAR_DUPLICATE", "low", bot, [a, b],
                            f"内容高度相似（重合度 {score}）",
                            "复核是否可合并为一条更准确的口径"))

    # 跨 Bot 同 key 不同值：可能是不同科室的合理差异，也可能是口径冲突
    by_key: dict[str, list[dict]] = {}
    for mem in memories:
        if mem.get("status") == "CONFIRMED":
            by_key.setdefault(mem.get("key"), []).append(mem)
    for key, items in by_key.items():
        bots = {m.get("botCode") for m in items}
        values = {_norm(m.get("value")) for m in items}
        if len(bots) > 1 and len(values) > 1:
            findings.append(_finding(
                "CROSS_BOT_CONFLICT", "high", "+".join(sorted(bots)), items,
                f"不同 Bot 使用同一 key（{key}）但内容不一致",
                "人工确认是科室差异还是口径冲突；必要时统一或改名"))

    severity_order = {"high": 0, "medium": 1, "low": 2}
    findings.sort(key=lambda f: (severity_order.get(f["severity"], 9), f["botCode"], f["kind"]))
    findings = findings[:MAX_FINDINGS]

    summary = {
        "memories": len(memories),
        "bots": len({m.get("botCode") for m in memories}),
        "findings": len(findings),
        "byKind": {},
        "bySeverity": {},
    }
    for f in findings:
        summary["byKind"][f["kind"]] = summary["byKind"].get(f["kind"], 0) + 1
        summary["bySeverity"][f["severity"]] = summary["bySeverity"].get(f["severity"], 0) + 1

    report = {
        "reportId": uuid.uuid4().hex,
        "generatedAt": now.isoformat(timespec="seconds"),
        "generatedBy": generated_by,
        "filters": {"botCodes": sorted(wanted)},
        "summary": summary,
        "findings": findings,
        "note": "只读建议：不会自动修改记忆；确认/删除请在 Bot 记忆面板人工完成。",
    }
    with _lock:
        _save_report(report)
    return report


def last_report() -> dict | None:
    reports = _load_reports()
    return reports[-1] if reports else None
