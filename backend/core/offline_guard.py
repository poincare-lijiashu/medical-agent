"""离网（air-gapped）自检：启动时把"需要外网"的依赖一次性说清楚。

医院内网普遍没有外网、更没有代理，最怕的是"上线才发现某个链路在等超时"。
本模块不做拦截，只在启动时把外部依赖与当前配置打印出来，让部署方一眼看清：

- LLM：`QWEN_BASE_URL` / `DEEPSEEK_BASE_URL` 若仍是公网域名，则必须能出外网，
  院内应改为内部 vLLM / Ollama 网关地址；
- 文献：`PUBMED_ENABLED=true` 时检索会访问 eutils.ncbi.nlm.nih.gov；
- 向量模型：`EMBED_ALLOW_DOWNLOAD=false`（默认）时只从本地目录加载权重。

生产（APP_ENV=production）命中公网地址会输出 WARN，便于巡检发现"忘了改配置"。
"""
from __future__ import annotations

import os

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)

# 常见公网服务域名（命中即视为"需要外网"）
_PUBLIC_HOSTS = (
    "aliyuncs.com",
    "dashscope",
    "deepseek.com",
    "openai.com",
    "anthropic.com",
    "huggingface.co",
    "hf-mirror.com",
    "ncbi.nlm.nih.gov",
)


def _is_public(url: str) -> bool:
    u = (url or "").strip().lower()
    if not u:
        return False
    return any(h in u for h in _PUBLIC_HOSTS)


def network_dependencies() -> dict:
    """返回当前配置下的外网依赖清单（供日志/巡检/健康检查复用）。"""
    return {
        "llm_chat": settings.qwen_base_url,
        "llm_chat_public": _is_public(settings.qwen_base_url),
        "llm_backup": settings.deepseek_base_url,
        "llm_backup_public": _is_public(settings.deepseek_base_url),
        "pubmed_enabled": bool(getattr(settings, "pubmed_enabled", True)),
        "embed_allow_download": bool(getattr(settings, "embed_allow_download", False)),
        "bge_m3_path": getattr(settings, "bge_m3_path", ""),
        "hf_hub_offline": os.environ.get("HF_HUB_OFFLINE", ""),
    }


def log_network_dependencies() -> dict:
    deps = network_dependencies()
    public = [k for k, flag in (("llm_chat", deps["llm_chat_public"]),
                                ("llm_backup", deps["llm_backup_public"])) if flag]
    logger.info(
        "medical_agent.network_deps",
        llm_chat=deps["llm_chat"],
        pubmed_enabled=deps["pubmed_enabled"],
        embed_allow_download=deps["embed_allow_download"],
        bge_m3_path=deps["bge_m3_path"] or "(empty)",
        public_endpoints=",".join(public) or "none",
    )
    prod = str(getattr(settings, "app_env", "")).lower() in {"production", "prod", "staging"}
    if prod and public:
        # 只告警不阻断：可能确实走院内出网白名单；但必须让巡检看到
        logger.warning(
            "medical_agent.network_deps.public_endpoint_in_production",
            endpoints=",".join(public),
            hint="院内离网部署请把 LLM base_url 指向内部 vLLM/Ollama 网关，并设置 PUBMED_ENABLED=false",
        )
    if prod and deps["pubmed_enabled"]:
        logger.warning(
            "medical_agent.network_deps.pubmed_enabled_in_production",
            hint="纯内网环境设置 PUBMED_ENABLED=false，避免检索等待外网超时",
        )
    return deps
