"""HITL payload builder for medical Agent interrupts."""
from backend.core.medical_eval import VerifiedAnswer, aggregate_needs_review


def forced_review_payload(
    answers: list[VerifiedAnswer],
    session_id: str,
    reason: str = "confidence_below_threshold",
):
    """If any answer < 0.7 confidence -> return interrupt payload; else None."""
    if not aggregate_needs_review(answers):
        return None
    return {
        "session_id": session_id,
        "requires_human": True,
        "reason": reason,
        "low_confidence_items": [
            {
                "answer_id": i,
                "text_preview": a.text[:120],
                "confidence": a.confidence,
            }
            for i, a in enumerate(answers)
            if a.needs_human_review
        ],
    }
