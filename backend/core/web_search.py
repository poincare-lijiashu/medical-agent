"""G86 联网搜索（唯一出网执行点，默认关闭）。

边界设计：
- **默认关闭**：``WEB_SEARCH_PROVIDER=none`` 时直接拒绝，绝不发出任何请求；
- **出网前脱敏**：查询串先移除手机号/身份证/邮箱等可直接识别个人的标识，避免
  病历内容被原样带到第三方检索服务（这是"最小必要"的工程底线，不能只靠提示词）；
- **结果域名白名单**：``WEB_SEARCH_ALLOWED_DOMAINS`` 非空时仅保留命中域名的结果，
  便于医院把来源收敛到指南/药监/期刊等可信站点；
- **失败即降级**：未配置、超时、HTTP 异常统一抛 ``WebSearchUnavailable``，由调用方
  转成可读提示，不让外部检索故障拖垮主链路。

本模块**不做**临床判断，只返回标题/链接/摘要片段，供上层智能体自行引用。
"""
from __future__ import annotations

import re
from urllib.parse import urlparse

import httpx

from backend.config import settings
from backend.core import web_sources

_DEFAULT_BASE_URL = "https://api.tavily.com"
_MAX_RESULTS = 10


class WebSearchUnavailable(RuntimeError):
    """联网搜索不可用（未配置/离线/上游失败）。调用方应转成用户可读提示。"""


# 入站可识别标识：手机号、18 位身份证、邮箱（与平台 PHI 脱敏口径保持同类）
_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_ID_CARD = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")


def redact_query(text: str) -> str:
    """出网前脱敏（保留临床语义，仅替换可直接识别个人的标识）。"""
    s = _EMAIL.sub("[EMAIL]", text or "")
    s = _ID_CARD.sub("[ID]", s)
    s = _PHONE.sub("[PHONE]", s)
    return " ".join(s.split())[:500]


def _allowed_domains() -> tuple[str, ...]:
    return web_sources.allowed_domains()


def _domain_allowed(url: str) -> bool:
    allow = _allowed_domains()
    if not allow:
        return True
    host = (urlparse(url).hostname or "").lower()
    return any(host == d or host.endswith("." + d) for d in allow)


def _provider() -> str:
    return (getattr(settings, "web_search_provider", "none") or "none").strip().lower()


def search(query: str, top_k: int = 5) -> dict:
    """执行一次联网检索；不可用时抛 ``WebSearchUnavailable``。

    :return: ``{"provider","query","results":[{"title","url","content"}]}``
    """
    provider = _provider()
    if provider in ("", "none", "off"):
        raise WebSearchUnavailable("联网搜索未启用（WEB_SEARCH_PROVIDER=none）")
    api_key = (getattr(settings, "web_search_api_key", "") or "").strip()
    if not api_key:
        raise WebSearchUnavailable("联网搜索缺少访问密钥（WEB_SEARCH_API_KEY）")
    q = redact_query(query)
    if not q:
        raise WebSearchUnavailable("联网搜索查询为空")

    k = max(1, min(int(top_k or 5), _MAX_RESULTS))
    base = (getattr(settings, "web_search_base_url", "") or "").strip() or _DEFAULT_BASE_URL
    timeout = float(getattr(settings, "web_search_timeout_s", 10.0) or 10.0)

    if provider == "tavily":
        payload = {"api_key": api_key, "query": q, "max_results": k,
                   "search_depth": "basic", "include_answer": False,
                   "include_raw_content": False,
                   # 服务端也限制来源域，避免先拉全网再本地过滤
                   "include_domains": list(_allowed_domains())}
    else:
        raise WebSearchUnavailable(f"不支持的联网搜索提供方：{provider}")

    try:
        with httpx.Client(timeout=timeout, trust_env=False) as client:
            resp = client.post(base.rstrip("/") + "/search", json=payload)
            resp.raise_for_status()
            body = resp.json()
    except (httpx.HTTPError, ValueError) as e:  # 网络/解析异常统一降级
        raise WebSearchUnavailable(f"联网搜索暂不可用：{type(e).__name__}") from e

    results = []
    for item in (body.get("results") or [])[:_MAX_RESULTS]:
        url = str(item.get("url") or "")
        if not url or not _domain_allowed(url):
            continue
        results.append({
            "title": str(item.get("title") or "")[:200],
            "url": url[:2000],
            "content": str(item.get("content") or item.get("snippet") or "")[:500],
        })
        if len(results) >= k:
            break
    return {"provider": provider, "query": q, "results": results}
