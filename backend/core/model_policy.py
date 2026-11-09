"""模型合规策略（纯分类层）：只允许**本地部署 / 国产模型**参与临床辅助。

为什么下沉到 core：
- 原实现只在 `backend/agents/model_policy.py`，医疗主链路（`core/llm_factory.py`）没有引用，
  导致"agent 域拦境外、医疗域直连境外"的产品级矛盾（评测 04 P0-3）；
- `core` 不能反向依赖 `agents`（评测 E2 的架构约束），故把**无依赖的纯判定**放在这里，
  `agents/model_policy.py` 只保留需要读配置的视图函数（provider 快照/白名单解析）。

判定规则（按 base_url 主机）：
1. ``localhost`` / 回环 / 私网地址（10/8、172.16/12、192.168/16、127/8）→ ``local``；
2. 命中国产模型/云厂商域名后缀 → ``domestic``；
3. 命中已知境外模型厂商 → ``foreign``；
4. 其余 → ``unknown``（默认拒绝，需管理员把域名加入国产白名单或改为本地地址）。

失败方向固定为 **fail-closed**：境外/未知一律拒绝。
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlparse

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
