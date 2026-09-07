"""Confidence gate + HITL trigger. M1 shared core."""
from dataclasses import dataclass, field
from typing import Iterable

CONFIDENCE_REVIEW_THRESHOLD = 0.70


@dataclass
class VerifiedAnswer:
    text: str
    confidence: float
    sources: list[str] = field(default_factory=list)
    needs_human_review: bool = False

    def __post_init__(self):
        # 显式标记的高风险复核不被低置信逻辑覆盖（OR 语义）
        self.needs_human_review = bool(self.needs_human_review) or self.confidence < CONFIDENCE_REVIEW_THRESHOLD


def aggregate_needs_review(answers: Iterable[VerifiedAnswer]) -> bool:
    """Any single low-confidence answer forces whole-submission human review."""
    return any(a.needs_human_review for a in answers)
