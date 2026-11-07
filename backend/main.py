"""MedAssist 医疗智能辅助平台 — FastAPI 入口。"""
from __future__ import annotations

import asyncio
import os
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles

from backend.config import settings
from backend.api.deps import require_metrics_access
from backend.core import observability
from backend.core.logger import get_logger
from backend.core.medical_audit import set_channel as _audit_set_channel
from backend.core.medical_audit import reset_channel as _audit_reset_channel
from backend.core.medical_audit import set_tool as _audit_set_tool
from backend.core.medical_audit import reset_tool as _audit_reset_tool
from backend.api.v1.medical.medical_router import router as medical_router
from backend.api.v1.auth.auth_router import router as auth_router
from backend.api.v1.agent.agent_router import router as agent_router
from backend.agents import catalog as agent_catalog
from backend.core import agent_flags

logger = get_logger(__name__)

# Vue3 前端构建产物目录（轮4 起唯一前端；legacy frontend/ 已删除，
# git tag v-legacy-frontend 永久保存，回退步骤见 docs/plans/vue3_migration.md）。
VUE_DIST_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "frontend-vue", "dist")

# /metrics 的短 TTL 缓存（评审 M-45）：抓取间隔远大于 5s，短缓存不会影响判读，
# 但能把"一次抓取 11 次域加载"摊薄；进程重启自然失效。
_METRICS_CACHE_TTL_S = 5.0
_metrics_cache: dict = {"at": 0.0, "text": ""}


def _docs_kwargs(env: str) -> dict:
    """生产类环境关闭交互式文档（/docs /redoc /openapi.json 不对外暴露）。

    评审 S6：统一使用 config.is_production_env（production/prod/staging）。
    """
    from backend.config import is_production_env
    if is_production_env(env):
        return {"docs_url": None, "redoc_url": None, "openapi_url": None}
    return {}


def validate_startup_security():
    """启动前安全校验：生产类环境强制高熵密钥且不允许关闭鉴权（防匿名 admin 全开放）。"""
    from backend.config import is_production_env
    if is_production_env():
        if settings.auth_jwt_secret == "dev-secret-change-in-production":
            raise RuntimeError("生产环境必须设置高熵 AUTH_JWT_SECRET（拒绝使用默认密钥启动）")
        # W3.5 轮换过渡期：旧 secret 仍参与验签，弱旧密钥等同弱主密钥（签发面虽在新
        # secret，攻击者可用旧 secret 伪造令牌走 previous 验签分支）——同样拒绝默认值。
        if settings.auth_jwt_secret_previous == "dev-secret-change-in-production":
            raise RuntimeError("生产环境 AUTH_JWT_SECRET_PREVIOUS 不得为默认弱密钥（轮换过渡期旧密钥仍参与验签）")
        if not settings.auth_enabled:
            raise RuntimeError("生产环境必须启用鉴权（AUTH_ENABLED=false 拒绝启动）")
        # W3.1：令牌只允许经 httpOnly cookie 下发。生产误开该开关会把令牌回写到
        # 响应体（JS 可达面），httpOnly 防线静默失效——拒绝启动而非降级告警。
        if settings.auth_token_in_body:
            raise RuntimeError(
                "生产环境禁止 AUTH_TOKEN_IN_BODY=true（令牌会回到 JS 可达面，"
                "绕过 httpOnly cookie 防护；该开关仅供测试/脚本调试）")


@asynccontextmanager
async def lifespan(app: FastAPI):
    validate_startup_security()
    logger.info("medical_agent.startup.begin", env=settings.app_env, port=settings.server_port)
    try:
        from backend.core.auth import seed_default_users
        if settings.app_env.lower() == "production" or not settings.auth_seed_demo:
            logger.warning("medical_agent.startup.seed_skipped",
                           detail="未注入演示账号（生产环境或 AUTH_SEED_DEMO=false）；请预先创建 data/auth/users.json")
        else:
            seed_default_users()
            logger.info("medical_agent.startup.auth_seeded")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.auth_seed_failed", error=str(exc)[:120])
    try:
        from backend.core import pg_store
        from backend.core.auth import all_users
        if await pg_store.init():
            migrated = await pg_store.migrate_json_to_pg()  # B1：PG 空表自动导入 JSON 存量（幂等）
            if migrated:
                logger.info("medical_agent.startup.pg_migrated", **migrated)
            for u, rec in all_users().items():
                await pg_store.mirror_user(u, rec.get("role", ""), rec.get("password_hash", ""))
            logger.info("medical_agent.startup.pg_ready")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup_pg_failed", error=str(exc)[:120])
    try:
        # 整改轮 B 任务3（科室-角色归属模型）：职能部门（药剂科/医务处/质控科/病案室）
        # 兜底入库（幂等）——PG 真源与 JSON 兜底两侧同步补齐，科室-角色建号校验依赖其存在。
        import asyncio as _asyncio
        from backend.core import departments as _departments
        added = await _asyncio.to_thread(_departments.ensure_functional_departments)
        if added:
            logger.info("medical_agent.startup.functional_departments_added", names="、".join(added))
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.functional_departments_failed", error=str(exc)[:120])
    try:
        from backend.core.embedder import get_bge_m3
        get_bge_m3()
        logger.info("medical_agent.startup.embedder_ready")
    except Exception as exc:  # noqa: BLE001
        # 生产环境升级为 error：权重缺失时知识库检索会整体不可用，必须让人看见
        from backend.config import is_production_env
        _prod = is_production_env()
        (logger.error if _prod else logger.warning)(
            "medical_agent.startup.embedder_failed", error=str(exc)[:240])
    try:
        # 离网自检：把"哪些能力依赖外网"在启动日志里讲清楚（不阻断启动）
        from backend.core.offline_guard import log_network_dependencies
        log_network_dependencies()
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.network_deps_failed", error=str(exc)[:120])
    try:
        # P0-5：注册到 Nacos（默认关闭）。内部已做后台重试，不阻塞启动；
        # 注册失败仅告警——AI 能力优先于"被服务发现"。
        from backend.core.nacos_registry import registry as _nacos_registry
        await _nacos_registry.start()
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.nacos_failed", error=str(exc)[:120])
    # 评审 M-28：技能运营快照必须自动生成，否则"趋势"依赖人工每天点按钮
    _snapshot_task = None
    _snapshot_stop = None
    try:
        from backend.agents import snapshot_scheduler
        _hour = int(getattr(settings, "agent_snapshot_hour", 0) or 0)
        _minute = int(getattr(settings, "agent_snapshot_minute", 10) or 0)
        if _hour > 0:
            await asyncio.to_thread(snapshot_scheduler.catch_up)
            _snapshot_stop = asyncio.Event()
            _snapshot_task = asyncio.create_task(snapshot_scheduler.run_daily(
                _snapshot_stop, _hour, _minute))
            # 记录的是**配置值**；越界时 run_daily 会回落 01:10 并另记 schedule_invalid
            logger.info("medical_agent.startup.snapshot_scheduler",
                        hour=_hour, minute=_minute)
        else:
            logger.info("medical_agent.startup.snapshot_scheduler_disabled")
    except Exception as exc:  # noqa: BLE001 —— 调度失败不阻断服务
        logger.warning("medical_agent.startup.snapshot_scheduler_failed",
                       error=str(exc)[:120])
    try:
        # 评审 M-11：清理历史软删除记忆的正文（PHI 残留），幂等
        from backend.agents import bot_memory as _bot_memory
        purged = await asyncio.to_thread(_bot_memory.purge_deleted_bodies)
        if purged:
            logger.info("medical_agent.startup.memory_bodies_purged", count=purged)
    except Exception as exc:  # noqa: BLE001 —— 清理失败不阻断启动
        logger.warning("medical_agent.startup.memory_purge_failed",
                       error=str(exc)[:120])
    try:
        # 评审 M-23：多 worker 形态下进程内 counter 不可信，启动时显式告警
        from backend.agents import consult_metrics as _cm
        _workers = _cm.worker_count()
        if _workers > 1:
            logger.warning("medical_agent.startup.multi_worker_metrics",
                           workers=_workers,
                           detail="进程内问答计量在多 worker 下会被 Prometheus 判为 reset，"
                                  "错误率告警不可信；请固定单 worker 或改用多进程聚合")
        else:
            logger.info("medical_agent.startup.metrics_worker_single")
    except Exception as exc:  # noqa: BLE001
        logger.warning("medical_agent.startup.metrics_worker_probe_failed",
                       error=str(exc)[:120])
    logger.info("medical_agent.startup.done")
    yield
    logger.info("medical_agent.shutdown")
    if _snapshot_stop is not None:
        _snapshot_stop.set()
    if _snapshot_task is not None:
        try:
            await asyncio.wait_for(_snapshot_task, timeout=5)
        except Exception:  # noqa: BLE001 —— 超时/异常直接取消，不拖慢下线
            _snapshot_task.cancel()
    try:
        # 优雅下线：先从 Nacos 摘除，避免滚动更新期间新请求打到正在停止的实例
        from backend.core.nacos_registry import registry as _nacos_registry
        await _nacos_registry.stop()
    except Exception:  # noqa: BLE001
        pass
    try:
        from backend.core import pg_store
        await pg_store.close()
    except Exception:  # noqa: BLE001
        pass


app = FastAPI(title="MedAssist · 医疗智能辅助平台 API", version=settings.app_version,
              lifespan=lifespan, **_docs_kwargs(settings.app_env))

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,  # 用 Bearer 令牌，不依赖 Cookie；关闭以收敛 CORS 面
    allow_methods=["*"],
    allow_headers=["*"],
)


class AuditChannelMiddleware:
    """入口渠道标记（MCP 接入）：请求头 X-MedAssist-Channel（MCP server 恒发 "mcp"）
    → 写入请求级 contextvar，本请求产生的审计事件 payload 自动带 channel 字段，
    用于区分入口（HTTP 前端 / MCP 外部 AI 客户端）；X-MedAssist-Tool（MCP server
    工具调用时携带工具名）同理落 payload.tool，审计可回溯触发工具。
    纯 ASGI 中间件：同一任务链内传播 contextvar（可靠进入 asyncio.to_thread 工作线程，
    审计写入在 to_thread 中也能取到渠道值）；普通 HTTP 请求无此头，行为零变化。"""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        ch = ""
        tool = ""
        for k, v in scope.get("headers") or []:
            if k == b"x-medassist-channel":  # ASGI 头名恒为小写
                ch = v.decode("latin-1", "replace").strip().lower()[:32]
            elif k == b"x-medassist-tool":
                tool = v.decode("latin-1", "replace").strip().lower()[:64]
        if not ch and not tool:
            await self.app(scope, receive, send)
            return
        token = _audit_set_channel(ch)
        tool_token = _audit_set_tool(tool)
        try:
            await self.app(scope, receive, send)
        finally:
            _audit_reset_channel(token)
            _audit_reset_tool(tool_token)


app.add_middleware(AuditChannelMiddleware)

app.include_router(medical_router, prefix="/api/v1/medical")
app.include_router(auth_router, prefix="/api/v1")
# G86 Agent 控制面：目录 / A2A 能力卡 / 路由预览 / 工具调用 / 运行时开关
app.include_router(agent_router, prefix="/api/v1/agent")


@app.get("/.well-known/agent-card.json", include_in_schema=False)
async def well_known_agent_card(request: Request):
    """A2A 标准发现入口（与 /api/v1/agent/card 同一份元数据）。"""
    if not agent_flags.get_flag("agent_a2a"):
        return JSONResponse({"detail": "A2A 能力广告已关闭"}, status_code=404)
    return agent_catalog.agent_card(
        agent_catalog.card_base_url(str(request.base_url).rstrip("/")))


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


# 基础安全响应头：所有响应统一携带。轮4 起 Vue3 前端为唯一前端：vite 构建产物全部为
# 外链 self 脚本（无内联代码），script-src 收紧为 'self'（移除 legacy 时期为内联
# onclick 处理器保留的 'unsafe-inline'）；style-src 'unsafe-inline' 为 Element Plus
# 动态样式与内联 style 属性所需；img-src data: 覆盖压缩影像的 data:image/ 白名单消费。
_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self'; "
        "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
        "frame-ancestors 'none'; base-uri 'self'"
    ),
}


@app.middleware("http")
async def _security_headers(request: Request, call_next):
    response = await call_next(request)
    for k, v in _SECURITY_HEADERS.items():
        response.headers.setdefault(k, v)  # setdefault：不覆盖端点已显式设置的同名头
    return response


@app.middleware("http")
async def _body_limit(request: Request, call_next):
    """请求体上限：Content-Length 超过 max_body_mb 时直接 413（不读 body，防大包耗资源）。
    已知边界：chunked 传输（无 Content-Length）不拦截——Starlette 中间件拿不到流式长度，
    由各请求模型字段上限（如 KbUploadReq.data_b64 max_length）兜底。"""
    try:
        n = int(request.headers.get("content-length") or 0)
    except ValueError:
        n = 0
    if n > settings.max_body_mb * 1024 * 1024:
        # FIND-13：本中间件注册在 _security_headers 之后（先于其执行），413 短路返回不经过
        # 后续中间件，必须内联补齐全套安全响应头（含简版 X-Request-Id）。
        # 诊断3：413 由纯文本改为 JSON 体（与 HTTPException 同构），前端可读 detail
        # 提示「拆分文件」，不再吞掉失败原因。
        resp = JSONResponse({"detail": f"请求体过大（>{settings.max_body_mb}MB），请拆分文件"},
                            status_code=413)
        for k, v in _SECURITY_HEADERS.items():
            resp.headers[k] = v
        resp.headers["X-Request-Id"] = uuid.uuid4().hex[:12]
        return resp
    return await call_next(request)


@app.get("/healthz", include_in_schema=False)
async def healthz():
    return {"ok": True}


@app.get("/readyz", include_in_schema=False)
async def readyz():
    return {"ready": True, "version": settings.app_version}


@app.get("/metrics", include_in_schema=False)
async def metrics(user: dict = Depends(require_metrics_access)):
    """运维指标：admin 登录态或 Prometheus 抓取专用 METRICS_TOKEN。

    指标含内部路由分布，未配置令牌时只允许 admin；静态令牌仅用于抓取。
    评审 M-14 整改：此前 `/metrics` 只导出进程指标 + 问答计量，而 G97 的运营告警规则
    依赖 `skill_trends` 的 13 个指标——Prometheus 抓的是 `/metrics`，导致 5/7 条规则
    永远无数据。此处合并为**单一口径的完整 exposition**，与 Agent 侧 Prometheus 端点一致。
    """
    from backend.agents import consult_metrics, skill_trends

    # 评审 M-45：一次抓取会触发约 11 次域加载（技能/经验/候选/建议/快照…）。
    # 抓取间隔通常 15–30s，这里做 5s 的进程内缓存 + 线程池执行，避免：
    # 1) 每个抓取都全量重算；2) 同步 PG/文件 IO 阻塞事件循环。
    now = time.monotonic()
    cached = _metrics_cache.get("text")
    if cached and now - _metrics_cache["at"] < _METRICS_CACHE_TTL_S:
        return PlainTextResponse(cached, media_type="text/plain; version=0.0.4")

    def _build() -> str:
        from backend.core import pg_store as _pg_store
        try:
            agent_ops = skill_trends.prometheus_text()
        except Exception:  # noqa: BLE001 —— 运营指标聚合失败不能拖垮抓取本身
            agent_ops = ""
        return (observability.exposition() + consult_metrics.prometheus_text()
                + agent_ops + _pg_store.prometheus_text())

    text = await asyncio.to_thread(_build)
    _metrics_cache.update(at=now, text=text)
    return PlainTextResponse(text, media_type="text/plain; version=0.0.4")


@app.get("/api/v1/health")
async def health():
    return {"status": "ok", "service": "MedAssist", "version": settings.app_version}


# ---- Vue3 前端（轮4 起唯一前端，手册 docs/plans/vue3_migration.md）----
# / 与 /app 双入口均直出 dist/index.html（显式路由避免 StaticFiles 对无尾斜杠路径的
# 307 跳转与目录列表）；/assets/* 等静态资源由文件末尾的 StaticFiles 托管（注册顺序
# 必须最后，否则 Mount 前缀匹配会吞掉 /api/*、/healthz 等显式路由）。
def _vue_index_response():
    """直出 Vue3 构建产物入口；未构建时给出可操作的 503 提示（dist 随轮入库，正常部署恒 200）。"""
    idx = os.path.join(VUE_DIST_DIR, "index.html")
    if os.path.isfile(idx):
        return FileResponse(idx, media_type="text/html")
    return JSONResponse(
        {"hint": "frontend-vue/dist 未构建；在 frontend-vue/ 下执行 npm ci && npm run build 后可用"},
        status_code=503)


@app.get("/app", include_in_schema=False)
async def vue_app_index():
    return _vue_index_response()


@app.get("/", include_in_schema=False)
@app.get("/index.html", include_in_schema=False)
async def index():
    return _vue_index_response()


# ---- SPA fallback（F5）：vue-router createWebHistory 深链刷新 404 修复 ----
# 现象：StaticFiles(html=True) 对 /review、/rx 等前端路由子路径查不到同名文件 → 404，
# 刷新/直链全挂。修复：catch-all 路由回落 dist/index.html（前端路由接管）。
# 排除清单（这些前缀不回退到 HTML 壳，保持原语义）：/api（API 未匹配路径仍 404 JSON）、
# /assets（静态资源——本路由注册先于 StaticFiles mount，静态文件在此做存在性检查直出，
# 行为与原 mount 等价）、/healthz /readyz /metrics、/docs /redoc /openapi.json、
# /js（历史残留不存在）、/app（已有显式路由与 mount，深链 /app/xxx 非法路径 404 JSON）。
# 注册顺序：具体路由（/api/...、/healthz、/、/app…）均已在此前注册，优先匹配不受影响。
_SPA_FALLBACK_EXCLUDED = ("api", "assets", "healthz", "readyz", "metrics",
                          "docs", "redoc", "openapi.json", "js", "app")


@app.get("/{full_path:path}", include_in_schema=False)
async def spa_fallback(full_path: str):
    first_seg = full_path.split("/", 1)[0].lower()
    candidate = os.path.normpath(os.path.join(VUE_DIST_DIR, full_path))
    in_dist = candidate == VUE_DIST_DIR or candidate.startswith(VUE_DIST_DIR + os.sep)
    # dist 内真实静态文件优先（/assets/*.js、favicon 等；normpath+前缀校验防目录穿越）
    if in_dist and os.path.isfile(candidate):
        return FileResponse(candidate)
    if first_seg in _SPA_FALLBACK_EXCLUDED:
        raise HTTPException(status_code=404, detail="Not Found")
    return _vue_index_response()


if os.path.isdir(VUE_DIST_DIR):
    # /app：轮1-3 的旧入口（保留兼容历史书签/文档引用）；/：轮4 起默认入口。
    # vite base=/ 与二者资源引用（/assets/*）一致；/app 下无独立资源（深链由
    # vue-router catch-all 回落 /overview）。
    app.mount("/app", StaticFiles(directory=VUE_DIST_DIR, html=True), name="frontend_vue")
    app.mount("/", StaticFiles(directory=VUE_DIST_DIR, html=True), name="frontend_vue_root")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("backend.main:app", host=settings.server_host, port=settings.server_port, reload=False)
