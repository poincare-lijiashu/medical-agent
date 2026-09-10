# -*- coding: utf-8 -*-
"""连通性测试 4xx 时的厂商建议参数提示（GLM 强制思考场景）。"""
import httpx
import pytest

from backend.core import llm_config as lc

_KEY = "sk-abc1234567890xy"


class _Resp400:
    status_code = 400
    text = '{"error":{"code":"1210","message":"该模型始终思考，不支持关闭思考；请使用 low、high 或 max。"}}'

    def json(self):
        raise ValueError()


class _FakeClient:
    def __init__(self, **kw):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def post(self, url, headers=None, json=None):
        return _Resp400()


@pytest.fixture
def _no_net(monkeypatch):
    monkeypatch.setattr(lc.httpx, "Client", _FakeClient)


def test_failure_appends_suggest_hint(_no_net):
    """4xx 且未填 extra_params → detail 追加厂商建议参数（GLM 场景）。"""
    r = lc.test_provider("openai", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash", _KEY)
    assert r["ok"] is False
    assert "建议额外参数" in r["detail"]
    assert "thinking" in r["detail"]


def test_failure_no_hint_when_user_filled(_no_net):
    """用户已填 extra_params → 4xx 不再追加建议（避免噪音）。"""
    r = lc.test_provider("openai", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash", _KEY,
                         extra_params='{"thinking":{"type":"high"}}')
    assert r["ok"] is False
    assert "建议额外参数" not in r["detail"]


def test_failure_no_hint_for_unknown_vendor(_no_net):
    """无预设的厂商 → 4xx 不追加（无建议可给）。"""
    r = lc.test_provider("openai", "https://api.example.com/v1", "whatever", _KEY)
    assert r["ok"] is False
    assert "建议额外参数" not in r["detail"]
