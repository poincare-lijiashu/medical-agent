"""MedAssist 配置：读 .env.local，暴露 settings 供全模块使用。"""
from __future__ import annotations

import os
from functools import lru_cache

from pydantic_settings import BaseSettings

_live_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_env_local = os.path.join(_live_path, ".env.local")


class Settings(BaseSettings):
    # 应用/服务
    app_env: str = "development"
    app_version: str = "1.0.0"
    log_level: str = "INFO"
    server_host: str = "127.0.0.1"
    server_port: int = 8001
    cors_origins: list[str] = ["*"]

    # LLM — Qwen（阿里云百炼，OpenAI 兼容）
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model_chat: str = "qwen-plus"
    qwen_model_reasoning: str = "qwen-plus"
    qwen_model_coder: str = "qwen-plus"
    qwen_model_vl: str = "qwen3-vl-plus"

    # DeepSeek 兜底（医疗路由不走，仅防其它路由调用时 AttributeError）
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model_reasoning: str = "deepseek-reasoner"
    deepseek_model_coder: str = "deepseek-chat"

    # 基础设施
    milvus_uri: str = "http://127.0.0.1:19531"
    milvus_token: str = ""
    pg_host: str = "127.0.0.1"
    pg_port: int = 5433
    pg_user: str = "eduagent"
    pg_password: str = ""
    pg_database: str = "eduagent_medical"
    pg_async_dsn: str = ""

    # 模型权重（相对路径按 backend/ 解析，绝对路径直用）
    bge_m3_path: str = ""
    reranker_path: str = ""
    bge_reranker_path: str = ""
    minilm_intent_model_path: str = ""

    # 鉴权
    auth_jwt_secret: str = "dev-secret-change-in-production"
    auth_token_expire_minutes: int = 720
    auth_refresh_expire_minutes: int = 10080
    rate_limit_per_min: int = 60
    auth_enabled: bool = True
    auth_seed_demo: bool = False  # 首次运行是否注入演示账号（doctor01 等）；默认关闭，仅演示环境显式开启

    # 影像/病例高危征象词表：留空用内置默认；逗号分隔覆盖，供院方对齐放射科危急值清单
    imaging_risk_words: str = ""

    @property
    def async_database_url(self) -> str:
        if self.pg_async_dsn:
            return self.pg_async_dsn
        from urllib.parse import quote_plus
        pwd = quote_plus(self.pg_password or "")  # 密码含 @:/# 等特殊字符时仍可解析
        return (f"postgresql+asyncpg://{self.pg_user}:{pwd}"
                f"@{self.pg_host}:{self.pg_port}/{self.pg_database}")

    class Config:
        env_file = _env_local
        env_file_encoding = "utf-8"
        case_sensitive = False
        extra = "ignore"


@lru_cache()
def get_settings() -> Settings:
    return Settings()


settings = get_settings()

# JWT 密钥防呆：默认密钥等同于无鉴权（任何人可伪造令牌），一律禁止。
if settings.auth_jwt_secret == "dev-secret-change-in-production":
    if settings.app_env == "production":
        raise RuntimeError("拒绝启动：生产环境必须显式设置 AUTH_JWT_SECRET（禁止使用默认密钥）")
    import secrets as _secrets
    import warnings as _warnings

    settings.auth_jwt_secret = _secrets.token_urlsafe(48)  # 进程级随机密钥；重启后旧令牌失效需重登
    _warnings.warn("AUTH_JWT_SECRET 未显式设置：已生成本进程临时随机密钥（重启后需重新登录）；生产环境必须显式配置",
                   stacklevel=1)
