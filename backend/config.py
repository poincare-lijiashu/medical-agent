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
    cors_origins: list[str] = ["http://localhost:8001", "http://127.0.0.1:8001"]

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

    # KB embed 截断窗口（任务A）：BGE-M3 最长支持 8192；256→1024 对齐结构感知切分窗口。
    # 入库与查询两侧共用 embedder 同一默认窗口，向量空间自然一致。
    # 注意 batch 32×1024 token 显存约翻倍，若 OOM 将 kb_ingest._EMBED_BATCH 降为 16。
    embed_max_length: int = 1024

    # 任务6 可移植性：嵌入设备选择。auto=有 CUDA 用 GPU 否则 CPU（部署机无 N 卡零配置可跑）；
    # 显式 cuda/cpu 强制指定（cuda 不可用时自动回落 CPU 并告警，绝不拒绝启动）。
    embed_device: str = "auto"

    # 任务4 VL 输出截断修复：视觉模型最大输出 token 数（原 openai 兼容端点未传 max_tokens，
    # 网关按各自默认值截断——影像描述在数百 token 处被剪成"心脏及"）。2000 覆盖放射科
    # 结构化描述（分点所见+建议）富余量；anthropic 分支原固定 1024 一并统一到本配置。
    vl_max_tokens: int = 2000

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
    auth_demo_password: str = ""  # 演示账号统一口令；留空则 seed 时随机生成（仅 dev 演示，生产不 seed）

    # 请求体上限（MB）：Content-Length 超限直接 413；KbUploadReq base64 需 ~30MB，默认 40 留余量
    max_body_mb: int = 40

    # 影像/病例高危征象词表：留空用内置默认；逗号分隔覆盖，供院方对齐放射科危急值清单
    imaging_risk_words: str = ""

    # 质控自动化（QC_AUTO_PASS / QC_AUTO_SIGN / QC_AUTO_SIGN_FULL 均可被同名环境变量覆盖）
    qc_auto_pass: bool = False  # True：qc/record 三档分流（确定性硬伤自动驳回 / ≥0.85 自动归档 / 其余人工）；默认关闭保持人工终审
    qc_auto_sign: bool = True   # True：药物中危规则库提示自动签发（AI·阈值自动留痕）；高危/禁忌仍人工双控
    # 任务3 留痕模式（默认 True）：全部 ask 类响应无条件入 review_queue 并立即自动签发
    # （reviewed_by=「AI·留痕模式(自动)」，含 LLM 自由文本高危——全部自动，医生侧不阻塞；
    # 低置信/高危项由提交医生知情确认）。运行中可经 admin「留痕模式」开关卡即时切换
    # （backend/core/runtime_flags.py：flags 文件优先于本启动值）。显式设 False 恢复旧语义
    # （仅高危入队 + 人工双控）。默认值由 False 改 True 属任务3 显式行为变更。
    qc_auto_sign_full: bool = True

    # 任务4 应用日志落文件 + 轮转（5MB × 5 备份，RotatingFileHandler）
    # LOG_TO_FILE：on/true/1 强制开；off/false/0 强制关；空（默认）全环境开启（开发也落文件，显式 off 才关）
    log_to_file: str = ""
    log_dir: str = "logs"

    # L1 agentic 检索循环（环境变量 LITERATURE_AGENTIC 覆盖）：开启后 grade 零命中
    # 进入 refine 决策节点（LLM 改写重检/放弃），关闭则保持旧的重检索路径
    literature_agentic: bool = True

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
