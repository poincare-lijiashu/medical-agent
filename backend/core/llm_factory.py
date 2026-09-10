# backend/core/llm_factory.py
# LLM Factory：统一封装大模型调用，按 Agent 类型路由。
# 规矩：所有 Agent 必须通过此模块获取模型，禁止直接调用 init_chat_model。

from typing import Type, Any                          # 类型注解用：Type 表示「某个类本身」，Any 表示任意类型
from pydantic import BaseModel                        # 结构化输出的 Schema 都是它的子类
import httpx                                          # HTTP 客户端库（用来自定义网络行为）
from langchain.chat_models import init_chat_model     # 2.3 学的：创建聊天模型（1.x 写法）
from langchain_core.language_models import BaseChatModel  # 聊天模型的基类（类型注解用）
from langchain_core.runnables import Runnable         # 「可运行对象」基类，结构化模型属于它

from backend.config import get_settings               # 读配置（API Key、base_url 等）
from backend.core.llm_config import active_provider   # F6：管理端动态模型配置（chat/vision）
from backend.core.logger import get_logger            # 结构化日志

logger = get_logger(__name__)                         # 本模块的日志器，name 用当前模块名

# ── 自定义 httpx 客户端：绕过系统代理 ───────────────────────────
# 背景：Windows 系统代理或 HTTPS_PROXY 环境变量会被 httpx 默认探测到，
#       导致 DeepSeek 请求经代理后 TLS 握手失败。DeepSeek 国内可直连，无需代理。
# trust_env=False 表示：完全忽略系统代理和相关环境变量。
_HTTP_ASYNC_CLIENT = httpx.AsyncClient(               # 异步客户端（给 ainvoke/astream 用）
    trust_env=False,
    timeout=httpx.Timeout(120.0, connect=15.0),       # 总超时 120 秒，建立连接超时 15 秒
)
_HTTP_SYNC_CLIENT = httpx.Client(                     # 同步客户端（给 invoke 用）
    trust_env=False,
    timeout=httpx.Timeout(120.0, connect=15.0),
)
# ── Agent 类型 → 模型标识符 的路由表 ────────────────────────────
# 想给某类业务换模型(对应模型厂商)，只改这里一行即可。
# 这里的_AGENT_MODEL_ROUTING键对应的是业务名称，值是每个业务选择的哪个模型厂商
_AGENT_MODEL_ROUTING: dict[str, str] = {
    "qa":               "deepseek-chat",   # 智能问答
    "exam_subjective":  "deepseek-chat",   # 试卷-简答题批改
    "exam_code":        "deepseek-chat",   # 试卷-代码题批改（coder 已并入 chat）
    "resume":           "deepseek-chat",   # 简历审查
    "interview":        "deepseek-chat",   # 模拟面试
    "intent":           "deepseek-chat",   # 意图识别
    "summarize":        "deepseek-chat",   # 对话摘要压缩
    "medical_literature": "deepseek-chat", # 医疗-文献助手
    "medical_imaging":    "deepseek-chat", # 医疗-影像辅助
    "medical_drug":       "deepseek-chat", # 医疗-药物信息
    "medical_case":       "deepseek-chat", # 医疗-多模态病例
}


# # 模型标识符（实际模型） → DeepSeek API 实际接受的 model 名称
_MODEL_ID_MAP: dict[str, str] = {
    "deepseek-chat": "deepseek-v4-pro", # 这里面值：可以改成：deepseek_v4-pro
}
# 这里的_MODEL_ID_MAP键对应模型的厂商，值是真实的模型

class LLMFactory:
    """大模型工厂（统一获取模型的唯一入口）。
    用 @classmethod 定义方法，意味着不用创建对象、直接用 LLMFactory.get_llm(...) 调用。

    用法：
        llm = LLMFactory.get_llm("qa")                              # 普通模型
        structured = LLMFactory.get_structured_llm("resume", 某Schema)  # 结构化输出模型
        response = await llm.ainvoke(messages)
    """

    _instances: dict[str, BaseChatModel] = {}   # 类变量：模型实例缓存（缓存键 → 模型），全类共享

    @classmethod
    def _get_settings(cls):
        """内部小工具：取配置对象。"""
        return get_settings()



    @classmethod
    def _anthropic_sdk_base_url(cls, url: str) -> str:
        """管理端 anthropic base_url 约定含 /v1（连通性测试 POST {base_url}/messages）；
        anthropic SDK 的 base_url 是主机根（自行追加 /v1/messages），传给 SDK 前裁掉尾部 /v1。"""
        u = (url or "").rstrip("/")
        return u[:-3] if u.endswith("/v1") else u

    @classmethod
    def _provider_kwargs(cls, provider: dict) -> dict[str, Any]:
        """F6：管理端激活 provider → init_chat_model kwargs（openai/anthropic 双格式）。"""
        if provider.get("api_format") == "anthropic":
            return {
                "model": provider["model_id"],
                "model_provider": "anthropic",
                "temperature": 0,
                "anthropic_api_key": provider["api_key"],
                "base_url": cls._anthropic_sdk_base_url(provider["base_url"]),
                "max_tokens": 1024,
                "max_retries": 3,
            }
        # openai 兼容格式：openai SDK 会自动在 base_url 后追加 /chat/completions，
        # 故传原始 base_url（与 .env 的 QWEN_BASE_URL 用法一致，最终请求 = {base_url}/chat/completions）
        # 思考模式与 function-calling 的 tool_choice 强制互斥（Qwen3/DeepSeek V4 均 400），
        # 结构化输出依赖 function calling，必须按厂商关闭思考：
        # - DeepSeek V4（默认思考开启）：thinking={"type":"disabled"}（官方参数）
        # - 其余（Qwen3 兼容端点）：enable_thinking=False（不识别该参数的端点会忽略，无副作用）
        if "deepseek" in (provider.get("base_url") or "").lower():
            extra_body = {"thinking": {"type": "disabled"}}
        else:
            extra_body = {"enable_thinking": False}
        return {
            "model": provider["model_id"],
            "model_provider": "openai",
            "temperature": 0,
            "openai_api_key": provider["api_key"],
            "base_url": provider["base_url"],
            "max_retries": 3,
            "http_async_client": _HTTP_ASYNC_CLIENT,
            "http_client": _HTTP_SYNC_CLIENT,
            "extra_body": extra_body,
        }

    @classmethod
    def _build_model_kwargs(cls, model_key: str) -> dict[str, Any]:
        """内部方法：组装 init_chat_model 需要的所有参数。
        F6：优先使用管理端激活的 chat provider（openai/anthropic 双格式）；
        无激活配置时回落 .env 内置 Qwen 配置（行为与旧版完全一致）。"""
        provider = active_provider("chat")
        if provider:
            return cls._provider_kwargs(provider)
        settings = cls._get_settings()             # 取配置
        model_id = settings.qwen_model_chat        # 同步到通义千问：模型名来自 .env 的 QWEN_MODEL_CHAT（原 _MODEL_ID_MAP 的 deepseek 名不再用于骨干）

        return {
            "model": model_id,                     # 模型名，如 "qwen-plus"
            "model_provider": "openai",            # Qwen 走 DashScope/MaaS 的 OpenAI 兼容接口
            "temperature": 0,                      # 默认 0：评分/批改要稳定输出
            "api_key": settings.qwen_api_key,      # 来自 .env.local 的 QWEN_API_KEY
            "base_url": settings.qwen_base_url,    # QWEN_BASE_URL（兼容模式端点）
            "max_retries": 3,                      # 对连接类错误(APIConnectionError/5xx)自动退避重试，吸收 MaaS 端点间歇性抖动
            "http_async_client": _HTTP_ASYNC_CLIENT,  # 用上面绕过代理的异步客户端
            "http_client": _HTTP_SYNC_CLIENT,         # 同步客户端
        }
    @classmethod
    def get_llm(
        cls,
        agent_type: str,             # Agent 类型，必须在路由表里
        temperature: float = 0,      # 温度：对话类可传 0.3~0.7，评分类保持 0
        streaming: bool = False,     # 是否流式输出（问答/面试对话用）
    ) -> BaseChatModel:
        """按 Agent 类型获取模型实例（带缓存）。
        相同 (模型, 温度, 是否流式) 的组合只会创建一次，之后复用。"""
        if agent_type not in _AGENT_MODEL_ROUTING:        # 校验：不认识的类型直接报错（早暴露问题）
            raise ValueError(
                f"未知 agent_type: '{agent_type}'，"
                f"可用类型：{list(_AGENT_MODEL_ROUTING.keys())}"
            )
        model_key = _AGENT_MODEL_ROUTING[agent_type]  # 查路由表，拿到模型标识符(模型厂商)
        # print(f'model_key: {model_key}')
        # 用「模型_温度_是否流式」拼一个缓存键：不同组合各缓存一份
        cache_key = f"{model_key}_{temperature}_{streaming}"
        # print(f'cache_key: {cache_key}')
        if cache_key not in cls._instances:  # 缓存里没有才新建
            # print(f'cache_key: {cache_key}')
            kwargs = cls._build_model_kwargs(model_key)  # 组装基础参数
            # print(f'kwargs: {kwargs}')
            kwargs["temperature"] = temperature           # 覆盖温度
            kwargs["streaming"] = streaming               # 设置是否流式
            # Qwen3 默认思考模式不允许 function-calling 的 tool_choice=required（会 400），关掉思考以兼容结构化输出
            # （F6：仅 openai 兼容格式需要；anthropic 无该参数）。
            # 注意：_provider_kwargs 可能已按厂商写入 DeepSeek 的 thinking disabled——合并而非覆盖
            if kwargs.get("model_provider") == "openai":
                eb = dict(kwargs.get("extra_body") or {})
                eb.setdefault("enable_thinking", False)
                kwargs["extra_body"] = eb
            # print(f'kwargs: {kwargs}')
            llm = init_chat_model(**kwargs)               # 真正创建模型（** 表示把字典展开成关键字参数）
            cls._instances[cache_key] = llm               # 存进缓存
            logger.info(  # 记一条结构化日志，便于观察
                "llm_factory.model_initialized",
                agent_type=agent_type, model_key=model_key,
                temperature=temperature, streaming=streaming,
            )
        return cls._instances[cache_key]

    @classmethod
    def get_structured_llm(
        cls,
        agent_type: str,
        output_schema: Type[BaseModel],   # 期望的输出结构（一个 Pydantic 模型类）
        temperature: float = 0,
    ) -> Runnable:
        """获取「绑定了结构化输出 Schema」的模型。
        调用它的 ainvoke 后，直接返回一个 output_schema 类型的对象（不是文本）。"""
        llm = cls.get_llm(agent_type, temperature=temperature)             # 先拿普通模型
        # 绑定 Pydantic 结构；method="function_calling" 是 DeepSeek 必须的（回顾 2.3）
        return llm.with_structured_output(output_schema, method="function_calling")

    @classmethod
    def clear_cache(cls) -> None:
        """清空模型实例缓存（测试时用）。"""
        cls._instances.clear()
        logger.info("llm_factory.cache_cleared")

    @classmethod
    def get_vl_llm(cls, temperature: float = 0) -> BaseChatModel:
        """多模态视觉模型：优先使用管理端激活的 vision provider（F6，openai/anthropic 双格式；
        anthropic 视觉调用失败由上层异常兜底），无激活配置回落 Qwen-VL。单独缓存。
        任务4 截断修复：统一设置 max_tokens=settings.vl_max_tokens（默认 2000）——
        openai 兼容端点此前不传 max_tokens 被网关默认值截断（影像描述"心脏及"），
        anthropic 分支原固定 1024 同样偏小，两分支统一走配置。"""
        cache_key = f"__vl__{temperature}"
        if cache_key not in cls._instances:
            provider = active_provider("vision")
            if provider:
                kwargs = cls._provider_kwargs(provider)
                kwargs["temperature"] = temperature
                kwargs["max_tokens"] = cls._get_settings().vl_max_tokens  # 任务4：覆盖 anthropic 默认 1024 / 补 openai 缺省
                cls._instances[cache_key] = init_chat_model(**kwargs)
                logger.info("llm_factory.vl_initialized", model=provider["model_id"],
                            api_format=provider["api_format"], max_tokens=kwargs["max_tokens"])
                return cls._instances[cache_key]
            settings = cls._get_settings()
            kwargs = {
                "model": settings.qwen_model_vl,
                "model_provider": "openai",
                "temperature": temperature,
                "api_key": settings.qwen_api_key,
                "base_url": settings.qwen_base_url,
                "max_tokens": settings.vl_max_tokens,  # 任务4：不再依赖网关默认值（截断根因）
                "max_retries": 3,
                "http_async_client": _HTTP_ASYNC_CLIENT,
                "http_client": _HTTP_SYNC_CLIENT,
                "extra_body": {"enable_thinking": False},
            }
            cls._instances[cache_key] = init_chat_model(**kwargs)
            logger.info("llm_factory.vl_initialized", model=settings.qwen_model_vl,
                        max_tokens=settings.vl_max_tokens)
        return cls._instances[cache_key]

# ── 模块级便捷函数（Agent 代码里的推荐写法）────────────────────────
# 比写 LLMFactory.get_llm(...) 更简洁，直接 from llm_factory import get_llm 即可。

def get_llm(agent_type: str, temperature: float = 0, streaming: bool = False) -> BaseChatModel:
    """LLMFactory.get_llm 的便捷入口。"""
    return LLMFactory.get_llm(agent_type, temperature=temperature, streaming=streaming)


def get_structured_llm(agent_type: str, output_schema: Type[BaseModel]) -> Runnable:
    """LLMFactory.get_structured_llm 的便捷入口。"""
    return LLMFactory.get_structured_llm(agent_type, output_schema)


def get_vl_llm(temperature: float = 0) -> BaseChatModel:
    """LLMFactory.get_vl_llm 的便捷入口（视觉多模态）。"""
    return LLMFactory.get_vl_llm(temperature=temperature)

if __name__ == '__main__':

    import asyncio
    from langchain_core.messages import HumanMessage, SystemMessage
    from pydantic import Field

    async def main():
        # llm = LLMFactory.get_llm("qa")  # 通过工厂拿模型
        # resp = await llm.ainvoke([HumanMessage(content="用一句话介绍 Python")])
        # print("DeepSeek 回复:", resp.text)  # .text 取文本（回顾 2.3）
        class PersonInfo(BaseModel):
            name: str = Field(description="姓名")
            age: int = Field(description="年龄（整数）")

        llm_struct = get_structured_llm("qa", PersonInfo)
        messages = [SystemMessage(content="你负责从文本中抽取任务信息"),
                    HumanMessage(content="我叫小明，今年25岁")]

        result = await llm_struct.ainvoke(messages)
        print(f"{result}")
        print(f"{result.name}")
        print(f"{result.model_dump()}")


    asyncio.run(main())






