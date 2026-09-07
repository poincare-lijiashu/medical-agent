"""QUALITY-001 收尾回归：async 端点内同步 IO（audit write / review 读写 / PBKDF2 登录）
包 asyncio.to_thread 后的并发冒烟测试。

20 个请求（/healthz + /api/v1/auth/login 各半）经 asyncio.gather 并发打 ASGI app，
全部应 200——证明 to_thread 改造未破坏 async 请求路径。只回归正确性，不断言时延。

（用户 seed 与口令锁定依赖 conftest 的 AUTH_DEMO_PASSWORD=Med@2026。）
"""
import asyncio

from httpx import ASGITransport, AsyncClient

from backend.core.auth import seed_default_users
from backend.main import app


def test_concurrent_healthz_and_login_all_200():
    seed_default_users()

    async def run() -> list[int]:
        transport = ASGITransport(app=app)  # 不进 lifespan，跳过模型预加载
        async with AsyncClient(transport=transport, base_url="http://test") as c:

            async def one(i: int) -> int:
                if i % 2 == 0:
                    r = await c.get("/healthz")
                else:
                    r = await c.post("/api/v1/auth/login",
                                     json={"username": "doctor01", "password": "Med@2026"})
                return r.status_code

            return list(await asyncio.gather(*(one(i) for i in range(20))))

    codes = asyncio.run(run())
    assert len(codes) == 20
    assert all(code == 200 for code in codes), codes
