"""MedAssist 医疗智能辅助平台 — FastAPI 入口。"""
from __future__ import annotations

import os
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, PlainTextResponse

from backend.config import settings
from backend.core import observability
from backend.core.logger import get_logger
from backend.api.v1.medical.medical_router import router as medical_router
from backend.api.v1.auth.auth_router import router as auth_router

logger = get_logger(__name__)

FRONTEND_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "frontend")


def validate_startup_security():
    """启动前安全校验：生产环境强制高熵密钥且不允许关闭鉴权（防匿名 admin 全开放）。"""
    if settings.app_env.lower() == "production":
        if settings.auth_jwt_secret == "dev-secret-change-in-production":
            raise RuntimeError("生产环境必须设置高熵 AUTH_JWT_SECRET（拒绝使用默认密钥启动）")
        if not settings.auth_enabled:
            raise RuntimeError("生产环境必须启用鉴权（AUTH_ENABLED=false 拒绝启动）")


@asynccontextmanager
async def lifespan(app: FastAPI):
    validate_startup_security()
    logger.info("medical_agent.startup.begin", env=settings.app_env, port=settings.server_port)
    try:
        from backend.core.auth import seed_default_users
        if settings.app_env.lower() == "production" or not settings.auth_seed_demo:
            logger.warning("medical_agent.startup.seed_skipped",
                           msg="未注入演示账号（生产环境或 AUTH_SEED_DEMO=false）；请预先创建 data/auth/users.json")
        else:
            seed_default_users()
            logger.info("medical_agent.startup.auth_seeded")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.auth_seed_failed", error=str(exc)[:120])
    try:
        from backend.core import pg_store
        from backend.core.auth import all_users
        if await pg_store.init():
            for u, rec in all_users().items():
                await pg_store.mirror_user(u, rec.get("role", ""), rec.get("password_hash", ""))
            logger.info("medical_agent.startup.pg_ready")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup_pg_failed", error=str(exc)[:120])
    try:
        from backend.core.embedder import get_bge_m3
        get_bge_m3()
        logger.info("medical_agent.startup.embedder_ready")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.embedder_failed", error=str(exc)[:120])
    logger.info("medical_agent.startup.done")
    yield
    logger.info("medical_agent.shutdown")
    try:
        from backend.core import pg_store
        await pg_store.close()
    except Exception:  # noqa: BLE001
        pass


app = FastAPI(title="MedAssist · 医疗智能辅助平台 API", version=settings.app_version, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,  # 用 Bearer 令牌，不依赖 Cookie；关闭以收敛 CORS 面
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(medical_router, prefix="/api/v1/medical")
app.include_router(auth_router, prefix="/api/v1")


@app.middleware("http")
async def _observability(request: Request, call_next):
    t0 = time.perf_counter()
    response = await call_next(request)
    dt = (time.perf_counter() - t0) * 1000.0
    route = getattr(request.scope.get("route"), "path", "unmatched")  # 用路由模板，防随机路径高基数
    observability.inc("http_requests_total")
    observability.observe_ms(route, dt)
    response.headers["X-Request-Id"] = uuid.uuid4().hex[:12]
    return response


@app.get("/healthz", include_in_schema=False)
async def healthz():
    return {"ok": True}


@app.get("/readyz", include_in_schema=False)
async def readyz():
    return {"ready": True, "version": settings.app_version}


@app.get("/metrics", include_in_schema=False)
async def metrics():
    return PlainTextResponse(observability.exposition(), media_type="text/plain; version=0.0.4")


@app.get("/api/v1/health")
async def health():
    return {"status": "ok", "service": "MedAssist", "version": settings.app_version}


# ---- 商用前端（自包含单页）----
@app.get("/", include_in_schema=False)
@app.get("/index.html", include_in_schema=False)
async def index():
    idx = os.path.join(FRONTEND_DIR, "index.html")
    if os.path.isfile(idx):
        return FileResponse(idx, media_type="text/html")
    return {"hint": "frontend/index.html 未找到；API 见 /docs"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("backend.main:app", host=settings.server_host, port=settings.server_port, reload=False)
