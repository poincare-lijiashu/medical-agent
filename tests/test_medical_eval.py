from backend.core.medical_eval import VerifiedAnswer, aggregate_needs_review


def test_low_confidence_flags_review():
    a = VerifiedAnswer(text='x', confidence=0.5)
    assert a.needs_human_review is True


def test_high_confidence_passes():
    a = VerifiedAnswer(text='x', confidence=0.9)
    assert a.needs_human_review is False


def test_aggregate_one_low_flags_all():
    ans = [
        VerifiedAnswer(text='a', confidence=0.9),
        VerifiedAnswer(text='b', confidence=0.5),
    ]
    assert aggregate_needs_review(ans) is True
