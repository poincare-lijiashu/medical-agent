"""G89 模型合规策略：只允许**本地部署 / 国产模型**参与临床辅助与第二意见。

为什么单独一层：
- 医院数据与临床结论不能默认出境；"能调通"不等于"允许用"；
- 策略必须可解释、可审计：每个 provider 给出 ``local / domestic / foreign / unknown``
  分类与判定依据（base_url 主机），前端只展示分类与主机，不展示密钥；
- 失败方向固定为 **fail-closed**：外网厂商、未知域名一律拒绝；本地/国产白名单才放行。

判定规则（按 base_url 主机）：
1. ``localhost`` / 回环 / 私网地址（10/8、172.16/12、192.168/16、127/8）→ ``local``；
2. 命中国产模型/云厂商域名后缀 → ``domestic``；
3. 命中已知境外模型厂商 → ``foreign``；
4. 其余 → ``unknown``（默认拒绝，需管理员把域名加入国产白名单或改为本地地址）。
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlparse

from backend.core import llm_config

# 国产模型 / 国内云厂商（后缀匹配，覆盖其 API 子域）
DOMESTIC_SUFFIXES = (
    "deepseek.com",
    "aliyuncs.com",          # 阿里云 DashScope / ModelArts
    "dashscope.aliyuncs.com",
    "bigmodel.cn",           # 智谱
    "zhipuai.cn",
    "moonshot.cn",           # Kimi
    "baichuan-ai.com",
    "minimax.chat",
    "minimaxi.com",
    "sensetime.com",         # 商汤
    "baidubce.com",          # 百度千帆
    "qianfan.baidubce.com",
    "tencentcloudapi.com",   # 腾讯混元
    "hunyuan.tencent.com",
    "volces.com",            # 火山方舟
    "volcengine.com",
    "xfyun.cn",              # 讯飞星火
    "iflytek.com",
    "siliconflow.cn",
    "modelscope.cn",
    "gitee.ai",
)

# 已知境外模型厂商：显式拒绝，避免"未知域名"里混入
FOREIGN_SUFFIXES = (
    "openai.com",
    "anthropic.com",
    "x.ai",
    "googleapis.com",
    "google.com",
    "azure.com",
    "cohere.ai",
    "mistral.ai",
    "groq.com",
    "together.ai",
    "perplexity.ai",
    "huggingface.co",
    "openrouter.ai",
)

ALLOWED_CLASSES = ("local", "domestic")


class ModelPolicyViolation(RuntimeError):
    """provider 不在允许范围（境外/未知）。"""


def _host(provider: dict) -> str:
    return (urlparse(str(provider.get("base_url") or "")).hostname or "").lower()


def _is_private_host(host: str) -> bool:
    if host in ("localhost", "::1"):
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private


def classify(provider: dict) -> str:
    """返回 local / domestic / foreign / unknown。"""
    host = _host(provider)
    if not host:
        return "unknown"
    if _is_private_host(host):
        return "local"
    if any(host == s or host.endswith("." + s) for s in DOMESTIC_SUFFIXES):
        return "domestic"
    if any(host == s or host.endswith("." + s) for s in FOREIGN_SUFFIXES):
        return "foreign"
    return "unknown"


def is_allowed(provider: dict) -> bool:
    return classify(provider) in ALLOWED_CLASSES


def assert_allowed(provider: dict) -> None:
    cls = classify(provider)
    if cls not in ALLOWED_CLASSES:
        raise ModelPolicyViolation(
            f"模型提供方不允许用于临床辅助：{_host(provider) or '未配置主机'}"
            f"（分类={cls}；仅允许本地部署或国产模型）")


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
