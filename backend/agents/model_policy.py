"""G89 模型合规策略：只允许**本地部署 / 国产模型**参与临床辅助与第二意见。

分层（评测 04 P0-3）：
- 纯判定（分类/放行/拒绝）已下沉到 `backend.core.model_policy`——医疗主链路
  `core/llm_factory.py` 也要用它，而 core 不能反向依赖 agents；
- 本模块保留需要读配置的视图函数（provider 快照、白名单解析），并**原样转出**判定
  符号，既有 `from backend.agents import model_policy` 的调用方零改动。
"""
from __future__ import annotations

from backend.core import llm_config
from backend.core.model_policy import (  # noqa: F401 —— 兼容既有导入面
    ALLOWED_CLASSES,
    DOMESTIC_SUFFIXES,
    FOREIGN_SUFFIXES,
    ModelPolicyViolation,
    _host,
    _is_private_host,
    assert_allowed,
    classify,
    is_allowed,
)


def provider_view(provider: dict) -> dict:
    """前端可见的 provider 视图：**绝不包含 api_key**。"""
    cls = classify(provider)
    return {
        "id": provider.get("id"),
        "displayName": provider.get("display_name") or provider.get("model_id") or "",
        "modelId": provider.get("model_id"),
        "apiFormat": provider.get("api_format"),
        "host": _host(provider),
        "class": cls,
        "allowed": cls in ALLOWED_CLASSES,
    }


def chat_providers() -> list[dict]:
    cfg = llm_config.load_config()
    return list(cfg.get("chat", {}).get("providers") or [])


def policy_snapshot() -> dict:
    """全部 chat provider 的分类快照（含拒绝原因），供管理端核对。"""
    providers = [provider_view(p) for p in chat_providers()]
    return {
        "allowedClasses": list(ALLOWED_CLASSES),
        "providers": providers,
        "allowed": [p for p in providers if p["allowed"]],
        "blocked": [p for p in providers if not p["allowed"]],
        "note": "仅允许本地部署或国产模型；境外厂商与未知域名默认拒绝（fail-closed）。",
    }


def allowed_chat_providers() -> list[dict]:
    return [p for p in chat_providers() if is_allowed(p)]


def resolve_allowed(provider_id: str) -> dict:
    provider = llm_config.find_provider("chat", provider_id)
    if provider is None:
        raise ModelPolicyViolation(f"未找到模型提供方：{provider_id}")
    assert_allowed(provider)
    return provider
