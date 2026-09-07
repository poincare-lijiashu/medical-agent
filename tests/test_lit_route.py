"""纯逻辑测试（不触 LLM/网络）：文献图兜底路由 + 多模态请求模型。"""
from backend.agents.medical.literature.nodes import route_after_grade


def test_route_fallback_when_insufficient_exhausted():
    # 充分 → generate
    assert route_after_grade({"sufficient": True, "attempts": 1}) == "generate"
    # 不足且未超重试、检索零命中 → 再检索
    assert route_after_grade({"sufficient": False, "attempts": 0}) == "retrieve"
    # 不足但已有证据（如宽泛症状问题）→ 仍走 generate 带出处回答（fallback 仅限检索零命中）
    assert route_after_grade({"sufficient": False, "attempts": 2, "evidence": [{"source": "KB:x"}]}) == "generate"
    # 不足且零命中且超重试 → 兜底
    assert route_after_grade({"sufficient": False, "attempts": 2}) == "fallback"


def test_multimodal_images_model_and_empty_guard():
    import asyncio
    from backend.api.v1.medical.medical_router import AskReq
    from backend.core.medical_imaging import describe_images
    assert AskReq(question="q", images=["a", "b"]).images == ["a", "b"]
    assert asyncio.run(describe_images([])) == ""  # 空图不触模型


def test_askreq_rejects_more_than_six_images():
    """images 超过 6 张应校验失败（422），而非静默截断丢弃。"""
    import pytest
    from pydantic import ValidationError

    from backend.api.v1.medical.medical_router import AskReq

    with pytest.raises(ValidationError):
        AskReq(question="q", images=["data:image/png;base64,AAA"] * 7)
    req = AskReq(question="q", images=["data:image/png;base64,AAA"] * 6)
    assert len(req.images) == 6
