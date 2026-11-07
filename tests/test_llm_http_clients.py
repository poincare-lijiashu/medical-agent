"""第 64 批：全局 httpx 客户端的生命周期（下线关闭 + 用时自动重建）。

背景：`llm_factory` 的 `_HTTP_ASYNC_CLIENT / _HTTP_SYNC_CLIENT` 是模块级长连接池，
此前**从不 close**——uvicorn reload、反复 `TestClient` 进出 lifespan 会不断累积连接与线程。
修复后必须同时保证：关闭后再次取用能拿到新实例（否则会把已关闭的 client 递给 SDK）。
"""
import asyncio
import pathlib

from backend.core import llm_factory


def test_acclose_closes_clients_and_clears_model_cache():
    async_client, sync_client = llm_factory.http_clients()
    assert not async_client.is_closed and not sync_client.is_closed
    llm_factory.LLMFactory._instances["__sentinel__"] = object()

    asyncio.run(llm_factory.aclose_http_clients())

    assert async_client.is_closed, "异步客户端必须被关闭"
    assert sync_client.is_closed, "同步客户端必须被关闭"
    assert llm_factory.LLMFactory._instances == {}, "模型缓存必须清空（缓存里的模型持有旧 client）"


def test_http_clients_recreate_after_close():
    """关闭后再取用 → 自动重建，后续模型调用不会拿到已关闭的 client。"""
    asyncio.run(llm_factory.aclose_http_clients())
    a, s = llm_factory.http_clients()
    assert not a.is_closed and not s.is_closed
    # 清理：避免把新客户端留给后续测试（无副作用，仅状态复位）
    asyncio.run(llm_factory.aclose_http_clients())


def test_lifespan_shutdown_calls_close_hook():
    """接线检查：lifespan 下线必须真的调用清理函数（改 main.py 时防止钩子被删）。

    这里只读源码做标记锁，不真跑 lifespan——那会加载 bge-m3 嵌入模型（耗时且占显存）。
    """
    src = pathlib.Path(llm_factory.__file__).resolve().parents[2] / "backend" / "main.py"
    text = src.read_text(encoding="utf-8")
    assert "llm_factory.aclose_http_clients()" in text, "lifespan 下线应调用 llm_factory.aclose_http_clients()"
