"""第 71 批：反向代理后的限流身份 + 429 协议细节。

背景：文档里的 nginx 会写 `X-Forwarded-For`，但 `_identity` 原先只取 `request.client.host`。
不改后端时，所有匿名请求（登录、健康检查）都落在 nginx 同一个 IP 桶里——60 次/分钟
被全院共享；而如果无条件采信 XFF，攻击者换个头就能绕过限流。正确做法是：
**只有直连方确实是可信代理时才采信 XFF，并从右往左取第一个非可信跳**。
"""
import pytest
from fastapi import HTTPException
from starlette.requests import Request

from backend.api import deps
from backend.config import settings


def _req(peer: str | None = "10.0.0.5", xff: str | None = None) -> Request:
    headers = []
    if xff is not None:
        headers.append((b"x-forwarded-for", xff.encode()))
    scope = {"type": "http", "method": "GET", "path": "/", "headers": headers}
    if peer is not None:
        scope["client"] = (peer, 12345)
    return Request(scope)


@pytest.fixture(autouse=True)
def _clear_proxy_cache():
    """配置在测试间变化：清掉 IP 网段解析缓存，保证每例按当前 settings 生效。"""
    deps._trusted_networks.cache_clear()
    yield
    deps._trusted_networks.cache_clear()


def _trust(monkeypatch, spec: str):
    monkeypatch.setattr(settings, "trusted_proxies", spec)
    deps._trusted_networks.cache_clear()


def test_untrusted_by_default_ignores_spoofed_header(monkeypatch):
    """默认不信任任何转发头：伪造 XFF 不能改变限流身份。"""
    _trust(monkeypatch, "")
    assert deps._client_ip(_req("203.0.113.5", "1.2.3.4")) == "203.0.113.5"


def test_trusted_proxy_uses_rightmost_untrusted_hop(monkeypatch):
    """直连方是可信代理：取最右侧非可信跳，忽略客户端自填的最左跳。"""
    _trust(monkeypatch, "10.0.0.0/8")
    # nginx 追加：客户端自填 "1.2.3.4" + 真实来源 203.0.113.7
    assert deps._client_ip(_req("10.0.0.5", "1.2.3.4, 203.0.113.7")) == "203.0.113.7"
    assert deps._client_ip(_req("10.0.0.5", "203.0.113.7")) == "203.0.113.7"


def test_untrusted_peer_cannot_use_header_even_if_configured(monkeypatch):
    """非可信直连方即使带了 XFF 也不采信（防止外网直连绕过）。"""
    _trust(monkeypatch, "10.0.0.0/8")
    assert deps._client_ip(_req("198.51.100.9", "203.0.113.7")) == "198.51.100.9"


def test_all_hops_trusted_falls_back_to_leftmost(monkeypatch):
    """多级内网代理（全部可信）时退回最左一跳；无 XFF 时退回 TCP 对端。"""
    _trust(monkeypatch, "10.0.0.0/8")
    assert deps._client_ip(_req("10.0.0.5", "10.1.1.1, 10.2.2.2")) == "10.1.1.1"
    assert deps._client_ip(_req("10.0.0.5", "")) == "10.0.0.5"


def test_invalid_proxy_config_ignored_and_ipv6_supported(monkeypatch):
    """配错的条目忽略（宁可不信任）；IPv6 地址/CIDR 正常匹配。"""
    _trust(monkeypatch, "not-an-ip, 10.0.0.0/8")
    assert deps._client_ip(_req("10.0.0.5", "203.0.113.7")) == "203.0.113.7"
    _trust(monkeypatch, "::1")
    assert deps._client_ip(_req("::1", "2001:db8::1")) == "2001:db8::1"


def test_identity_uses_real_client_ip(monkeypatch):
    """匿名请求的限流 key 用解析后的客户端 IP（登录/健康检查不再共用一个桶）。"""
    _trust(monkeypatch, "127.0.0.1")
    assert deps._identity(_req("127.0.0.1", "203.0.113.7")) == "ip:203.0.113.7"
    _trust(monkeypatch, "")
    assert deps._identity(_req("203.0.113.5", "1.2.3.4")) == "ip:203.0.113.5"


def test_429_carries_retry_after_header(monkeypatch):
    """429 必须带 Retry-After（窗口 60s），客户端/网关可据此退避而不是盲目重试。"""
    monkeypatch.setattr(deps, "allow", lambda *a, **k: False)
    with pytest.raises(HTTPException) as e1:
        deps.rate_limit(_req())
    assert e1.value.status_code == 429
    assert e1.value.headers["Retry-After"] == "60"

    monkeypatch.setattr(settings, "agent_model_quota_per_min", 30)
    with pytest.raises(HTTPException) as e2:
        deps.model_quota(_req())
    assert e2.value.status_code == 429
    assert e2.value.headers["Retry-After"] == "60"
