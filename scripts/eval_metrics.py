"""评测指标（纯函数，离线可测）：引用精确率、拒答正确率、置信度校准(ECE)。

供 run_eval.py 与 CI 使用；不依赖网络/模型，输入为 (预测, gold) 结构。
"""
from __future__ import annotations

from typing import Iterable, Optional


def _key(x: str) -> str:
    return str(x).split(":")[-1].strip()


def citation_precision(sources: Iterable[str], evidence_sources: Iterable[str]) -> Optional[float]:
    """回答引用的来源中，可被检索证据核验的比例。无引用返回 None（不计入）。"""
    srcs = [s for s in sources if s]
    if not srcs:
        return None
    allowed = {_key(e) for e in evidence_sources if e}
    hit = sum(1 for s in srcs if _key(s) in allowed)
    return hit / len(srcs)


def refusal_correct(needs_review: bool, confidence: float, gold_unanswerable: bool,
                    threshold: float = 0.6) -> bool:
    """该拒是否拒：gold 不可答 → 期望低置信或需复核；gold 可答 → 期望高置信且不强制复核。"""
    flagged = (not needs_review) and confidence >= threshold
    if gold_unanswerable:
        return not flagged           # 不能自信地"接受"
    return flagged or confidence >= threshold


def expected_calibration_error(pairs: list[tuple[float, int]], bins: int = 10) -> float:
    """ECE：|平均置信 - 准确率| 的分箱加权。pairs=[(confidence, 0/1 正确)]。"""
    if not pairs:
        return 0.0
    total = len(pairs)
    buckets: list[list[tuple[float, int]]] = [[] for _ in range(bins)]
    for conf, correct in pairs:
        b = min(int(conf * bins), bins - 1)
        buckets[b].append((conf, correct))
    ece = 0.0
    for bucket in buckets:
        if not bucket:
            continue
        avg_conf = sum(c for c, _ in bucket) / len(bucket)
        acc = sum(int(ok) for _, ok in bucket) / len(bucket)
        ece += (len(bucket) / total) * abs(avg_conf - acc)
    return ece


def summarize(rows: list[dict]) -> dict:
    """rows: [{citation_precision, refusal_correct, confidence, correct}] → 指标汇总。"""
    cps = [r["citation_precision"] for r in rows if r.get("citation_precision") is not None]
    rcs = [r for r in rows if r.get("refusal_correct") is not None]
    out = {
        "n": len(rows),
        "citation_precision_mean": round(sum(cps) / len(cps), 3) if cps else None,
        "refusal_accuracy": round(sum(1 for r in rcs if r["refusal_correct"]) / len(rcs), 3) if rcs else None,
        "ece": round(expected_calibration_error([(r["confidence"], int(r.get("correct", 0))) for r in rows if "confidence" in r]), 3),
    }
    return out
