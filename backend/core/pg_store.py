"""Postgres 持久层（B1 数据真源化 + 合规镜像，best-effort 双写）。

架构（升级商业正式版）：
- 专用 DB 事件循环线程：asyncpg 连接池创建于该循环并仅在其上使用。同步调用方
  （auth/departments/medical_review/llm_config 的 sync 函数）经 run_coroutine_threadsafe
  桥接阻塞等待——DB 循环在独立线程，永不与调用方的事件循环互锁（deps.require_user
  等热路径在 threadpool 中调用也安全）。
- repo 层 load_*/save_*：auth/departments/medical_review/llm_config 四个模块文件读写的
  统一出入口（调用方 API 签名不变）。
  * PG 池可用 → 读走 PG 真源（空表回落 JSON，等迁移导入兜底，保守可用性优先）；
    写 = JSON 原子写（兜底安全，现状行为）+ PG 全量同步（best-effort）。
  * PG 池为 None/异常/超时 → 直接走 JSON（现状行为完全等价；测试环境无真实 PG 零回归）。
- mirror_* 合规镜像：PG 不可用→静默 no-op；异常→仅告警，绝不冒泡影响请求。lifespan 建表。
- migrate_json_to_pg：PG 表空而 JSON 有数据 → 幂等导入（ON CONFLICT DO NOTHING）；
  PG 非空时对 review_queue 按 id 差集补齐 JSON 独有记录（任务1 数据修复：PG 同步失败
  窗口内 JSON 积累的记录，重启后自动找回）；lifespan 启动时自动触发，scripts/migrate_json_to_pg.py 可手动触发。
- resync_clinical_mirrors：migrate 之后按 PG 真源重建临床域（审核队列/会诊/处方/病例库）
  JSON 镜像（评测 P0-4：镜像读-改-写并发会丢行，启动对账幂等并记录修正条数）。
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import os
import threading
import time
from concurrent.futures import TimeoutError as _FutTimeout
from datetime import datetime

import asyncpg

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)
_pool = None

# ---------- PG 连接熔断（第 50 批）----------
# 为什么需要：PG 掉线时每次读都要等连接超时（本机实测约 2s/域），一次自检/首屏
# 会串行等十几个 2s。熔断只针对**连接类**异常：连续 3 次后 15s 内直接快速回落，
# 冷却结束自动探测；成功一次立即闭合。查询/数据类错误（缺列等）不触发熔断。
_PG_CIRCUIT_FAILS = 3
_PG_CIRCUIT_COOLDOWN_S = 15.0
_circuit_lock = threading.Lock()
_pg_fail_streak = 0
_pg_circuit_until = 0.0  # time.monotonic() 截止点


def _circuit_threshold() -> int:
    """熔断阈值：settings 可覆盖（PG_CIRCUIT_FAILS），非法值回落 3。"""
    try:
        return max(1, int(getattr(settings, "pg_circuit_fails", _PG_CIRCUIT_FAILS)
                          or _PG_CIRCUIT_FAILS))
    except (TypeError, ValueError):
        return _PG_CIRCUIT_FAILS


def _circuit_cooldown() -> float:
    """冷却秒数：settings 可覆盖（PG_CIRCUIT_COOLDOWN_S），下限 1s。"""
    try:
        return max(1.0, float(getattr(settings, "pg_circuit_cooldown_s",
                                      _PG_CIRCUIT_COOLDOWN_S)))
    except (TypeError, ValueError):
        return _PG_CIRCUIT_COOLDOWN_S


# ---------- PG 连接池容量（第 59 批）----------
# 为什么可配：第 58 批实测单实例只读吞吐在 ~100 RPS 饱和，而池上限固定 5。
# 池满时请求在取连接处排队，是"吞吐上不去、延迟线性涨"的候选瓶颈之一。
# 默认值保持 1/5 不变（避免无谓的连接数上涨），压测/扩容场景再按需放开。
_PG_POOL_MIN = 1
_PG_POOL_MAX = 5


def _pool_min() -> int:
    """池下限：settings 可覆盖（PG_POOL_MIN），非法值回落 1。"""
    try:
        return max(1, int(getattr(settings, "pg_pool_min", _PG_POOL_MIN) or _PG_POOL_MIN))
    except (TypeError, ValueError):
        return _PG_POOL_MIN


def _pool_max() -> int:
    """池上限：settings 可覆盖（PG_POOL_MAX）；下限跟 min 对齐，上限 100（防手滑打爆 PG）。"""
    try:
        configured = int(getattr(settings, "pg_pool_max", _PG_POOL_MAX) or _PG_POOL_MAX)
    except (TypeError, ValueError):
        configured = _PG_POOL_MAX
    return min(100, max(_pool_min(), configured))


class PGUnavailable(RuntimeError):
    """PG 熔断期间的快速失败（不等待连接超时，由上层回落 JSON 或返回 5xx）。"""


def circuit_open() -> bool:
    return time.monotonic() < _pg_circuit_until


def _pg_error_is_connectivity(exc: BaseException) -> bool:
    """只有"连不上"类异常才计入熔断；查询错误/数据错误不应让全局快速回落。"""
    if isinstance(exc, (_FutTimeout, asyncio.TimeoutError, TimeoutError,
                        OSError, ConnectionError)):
        return True
    name = type(exc).__name__.lower()
    return any(k in name for k in ("connection", "cannotconnect", "interface", "timeout"))


def _pg_circuit_success() -> None:
    global _pg_fail_streak, _pg_circuit_until
    with _circuit_lock:
        _pg_fail_streak = 0
        _pg_circuit_until = 0.0


def _pg_circuit_failure(exc: BaseException) -> None:
    global _pg_fail_streak, _pg_circuit_until
    if not _pg_error_is_connectivity(exc):
        return
    with _circuit_lock:
        _pg_fail_streak += 1
        if _pg_fail_streak >= _circuit_threshold() and time.monotonic() >= _pg_circuit_until:
            _pg_circuit_until = time.monotonic() + _circuit_cooldown()
            _pg_fail_streak = 0
            logger.warning("pg.circuit_open", cooldown_s=_circuit_cooldown(),
                           detail="近期连续连接失败：短暂快速回落 JSON，冷却后自动探测")

# ---------- 专用 DB 事件循环（池归属该循环，同步/异步桥接均经它执行池操作） ----------
_db_loop: asyncio.AbstractEventLoop | None = None
_loop_lock = threading.Lock()
_BRIDGE_TIMEOUT = 3.0  # 单次 PG 往返桥接上限（秒）；超时视为 PG 不可用 → 调用方回落 JSON


def _get_db_loop() -> asyncio.AbstractEventLoop:
    """懒启动专用 DB 循环线程（停止/崩溃后可自动重启）；daemon 线程随进程退出。"""
    global _db_loop
    with _loop_lock:
        if _db_loop is not None and _db_loop.is_running():
            return _db_loop
        ready = threading.Event()
        box: dict = {}

        def _run():
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            box["loop"] = loop
            ready.set()
            loop.run_forever()

        threading.Thread(target=_run, daemon=True, name="pg-store-db-loop").start()
        if not ready.wait(5):
            raise RuntimeError("pg_store 专用 DB 循环启动超时")
        _db_loop = box["loop"]
        return _db_loop


def _stop_db_loop() -> None:
    global _db_loop
    loop = _db_loop
    if loop is not None and loop.is_running():
        try:
            loop.call_soon_threadsafe(loop.stop)
        except Exception:  # noqa: BLE001 —— 关停尽力而为
            pass
    _db_loop = None


def _run_pg(coro):
    """同步桥接：协程提交到专用 DB 循环并阻塞取结果（任意线程可调，含事件循环线程——
    DB 循环在独立线程，绝不与调用方互锁）。超时/异常向上抛，由 repo 层捕获回落 JSON。
    超时后尽力取消挂起协程：否则 PG 半开/挂起场景协程滞留池中（max_size=5）逐渐
    耗尽连接，后续请求全部超时降级（取消经 future 链传播为协程 CancelledError）。

    第 50 批：熔断打开时**不提交**协程，直接抛 ``PGUnavailable``——省掉每次约 2s 的
    连接超时；未提交的协程必须 ``close()``，否则产生 "never awaited" 泄漏告警。
    """
    if circuit_open():
        getattr(coro, "close", lambda: None)()
        raise PGUnavailable("PG 熔断中：快速失败（冷却后自动探测）")
    fut = asyncio.run_coroutine_threadsafe(coro, _get_db_loop())
    try:
        out = fut.result(timeout=_BRIDGE_TIMEOUT)
    except _FutTimeout:
        fut.cancel()
        _pg_circuit_failure(_FutTimeout("pg bridge timeout"))
        raise
    except Exception as e:
        _pg_circuit_failure(e)
        raise
    _pg_circuit_success()
    return out


# ---------- 第 93 批：PG 查询耗时/失败按域计量（Prometheus 导出） ----------
# 为什么：第 81–92 批把列表/计数/详情下推到 PG 后，这些查询成了首屏与角标轮询的
# 主要依赖；但它们成功/失败/耗时此前只在失败时打一条 warning。运维看不到
# "哪个域在变慢、哪个域的失败在持续（= 一直在回落 JSON）"。
# 口径：只统计**真正尝试了 PG** 的调用；离线/无池/旧编排 stale 在守卫处直接返回，
# 不计入（否则 JSON 部署会被误报成 PG 失败）。错误含熔断快速失败（PGUnavailable）。
# 基数：domain/op 全部来自本模块内部常量（不取用户输入），组合数固定且有限。
# 固定预置这些键（零样本）：Prometheus 首抓就能看到族名与标签，静态门禁（规则引用的
# 指标必须已导出）与 rate()/increase() 在无流量时也不会因"样本不存在"失真。
_PG_METRIC_KEYS = (
    ("review_queue", "pending_page"),
    ("review_queue", "history_page"),
    ("review_queue", "get_row"),
    ("review_queue", "my_rows"),
    ("review_queue", "status_counts"),
    ("consults", "list"),
    ("consults", "get_row"),
    ("prescriptions", "page"),
    ("prescriptions", "get_row"),
    ("case_archive", "list"),
    ("case_archive", "stats"),
    ("case_archive", "get_row"),
    # 第 108 批：admin 审计流读路径（分页 + 过滤后总数）纳入同口径计量
    ("audit_log", "recent_page"),
    ("audit_log", "count"),
    # 第 110 批：过滤框候选值（GROUP BY 聚合）读路径计量
    ("audit_log", "facets"),
)
# 第 94 批：耗时直方图桶（秒）。覆盖本地实测（2–8ms）到熔断前的桥接上限（3s），
# 让 P95/P99 可算；`+Inf` 不在常量里，导出时以 total 补。
_PG_LATENCY_BUCKETS = (0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
_pg_metrics_lock = threading.Lock()


def _new_pg_stat() -> dict:
    return {"total": 0.0, "errors": 0.0, "seconds": 0.0,
            "buckets": [0.0] * len(_PG_LATENCY_BUCKETS)}


_pg_query_stats: dict[tuple[str, str], dict] = {
    k: _new_pg_stat() for k in _PG_METRIC_KEYS
}


def _record_pg_query(domain: str, op: str, seconds: float, ok: bool) -> None:
    key = (str(domain), str(op))
    value = max(0.0, float(seconds))
    with _pg_metrics_lock:
        st = _pg_query_stats.get(key)
        if st is None:
            st = _new_pg_stat()
            _pg_query_stats[key] = st
        st["total"] += 1
        if not ok:
            st["errors"] += 1
        st["seconds"] += value
        for i, bound in enumerate(_PG_LATENCY_BUCKETS):  # 累积桶：所有 bound >= value
            if value <= bound:
                st["buckets"][i] += 1


def _reset_pg_query_metrics() -> None:
    """恢复为零样本（仅单测用；生产不调用）。读写两侧一起重置。"""
    with _pg_metrics_lock:
        _pg_query_stats.clear()
        for key in _PG_METRIC_KEYS:
            _pg_query_stats[key] = _new_pg_stat()
        _pg_write_stats.clear()
        for key in _PG_WRITE_METRIC_KEYS:
            _pg_write_stats[key] = _new_pg_stat()


def _pg_call(domain: str, op: str, coro):
    """带计量的 _run_pg：成功/失败都计一次，失败原样抛出（调用方回落 JSON 语义不变）。"""
    t0 = time.perf_counter()
    try:
        out = _run_pg(coro)
    except Exception:
        _record_pg_query(domain, op, time.perf_counter() - t0, False)
        raise
    _record_pg_query(domain, op, time.perf_counter() - t0, True)
    return out


async def _arun_metered(domain: str, op: str, coro):
    """第 108 批：异步读计量（与 _pg_call 同口径，供主循环内已 await 的 PG 读使用）。

    为什么单独一个：`_pg_call` 走同步桥接，在 async 端点里调用会阻塞事件循环；
    审计流读路径本就是 `await _arun(...)`，这里只补计量，不改桥接方式。"""
    t0 = time.perf_counter()
    try:
        out = await _arun(coro)
    except Exception:
        _record_pg_query(domain, op, time.perf_counter() - t0, False)
        raise
    _record_pg_query(domain, op, time.perf_counter() - t0, True)
    return out


# ---------- 第 95 批：PG 写路径同口径计量（镜像写 / 全量同步 / 单行 UPSERT） ----------
# 为什么：读路径（93/94 批）已有耗时与失败率，但写入侧仍是盲区——镜像写失败只打
# 一条 warning，全量同步失败只置 stale，单行 UPSERT 失败由调用方兜底。运维无法回答
# "哪类写失败在持续""写是不是变慢了"。本批与读路径同构：成功/失败各计一次 + 直方图。
# 口径：只统计**真正尝试写 PG** 的调用（无池/离线直接返回不计）；镜像写吞异常但
# 计 error；主写/单行 UPSERT 计 error 后原样抛出（调用方语义不变）。
_PG_WRITE_METRIC_KEYS = (
    ("repo", "full_sync"),
    ("audit_log", "mirror"),
    ("users", "mirror"),
    ("review_queue", "mirror"),
    ("review_queue", "upsert_row"),
    ("users", "upsert_row"),
    ("consults", "upsert_row"),
    ("prescriptions", "upsert_row"),
    ("case_archive", "upsert_row"),
    ("skills", "upsert_row"),
    ("review_queue", "purge"),
    ("review_queue", "purge_drug"),
    ("users", "delete_row"),
)
_pg_write_stats: dict[tuple[str, str], dict] = {
    k: _new_pg_stat() for k in _PG_WRITE_METRIC_KEYS
}
# 第 102 批：每族只保留一个"零样本锚点"（族名/标签格式恒在，静态门禁与首抓可见），
# 其余未触达键不再固定导出——见 _emit_pg_family 的体积说明。
_PG_METRIC_ANCHORS = {
    "query": {("review_queue", "pending_page")},
    "write": {("repo", "full_sync")},
}


def _record_pg_write(domain: str, op: str, seconds: float, ok: bool) -> None:
    key = (str(domain), str(op))
    value = max(0.0, float(seconds))
    with _pg_metrics_lock:
        st = _pg_write_stats.get(key)
        if st is None:
            st = _new_pg_stat()
            _pg_write_stats[key] = st
        st["total"] += 1
        if not ok:
            st["errors"] += 1
        st["seconds"] += value
        for i, bound in enumerate(_PG_LATENCY_BUCKETS):
            if value <= bound:
                st["buckets"][i] += 1


def _pg_write_call(domain: str, op: str, coro):
    """同步写计量：成功/失败都计一次，失败原样抛出。"""
    t0 = time.perf_counter()
    try:
        out = _run_pg(coro)
    except Exception:
        _record_pg_write(domain, op, time.perf_counter() - t0, False)
        raise
    _record_pg_write(domain, op, time.perf_counter() - t0, True)
    return out


async def _pg_write_async(domain: str, op: str, coro):
    """异步写计量（镜像写走主循环 → 专用 DB 循环）：失败原样抛出，由调用方 best-effort 处理。"""
    t0 = time.perf_counter()
    try:
        out = await _arun(coro)
    except Exception:
        _record_pg_write(domain, op, time.perf_counter() - t0, False)
        raise
    _record_pg_write(domain, op, time.perf_counter() - t0, True)
    return out


async def _arun(coro):
    """异步桥接：主循环协程内把池操作转投专用 DB 循环（wrap_future 支持跨循环等待）。"""
    return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(coro, _get_db_loop()))


def _dsn() -> str:
    return settings.async_database_url.replace("postgresql+asyncpg://", "postgresql://")


# ---------- 任务2：显式离线模式（PG_OFFLINE=on） ----------
# 场景：eval/脚本/单测等非服务进程没有 PG 池（从不调 init），此前每次读都会打
# read_stale_fallback_json 告警（logs/app.log 7269 行几乎全是它刷屏）。显式离线模式下
# pg_store 纯静默 JSON：init 直接跳过、JSON 是唯一真源、不置 stale、不告警。
_TRUTHY = ("on", "1", "true", "yes")


def offline_mode() -> bool:
    """PG_OFFLINE 环境变量解析（on/1/true/yes 均可，大小写不敏感）。"""
    return (os.environ.get("PG_OFFLINE") or "").strip().lower() in _TRUTHY


# ---------- 第 92 批：第 81–91 批下推查询的配套索引 ----------
# 索引表达式/列序与各查询逐字对齐（见对应 _pg_*_impl 的 WHERE/ORDER BY）：
#   review_queue  → 待核 status+ts 顺序、历史/个人 submitted_by|reviewed_by+ts 倒序、全表 ts 倒序
#   prescriptions → doctor|status + data->>'created_at' 倒序（第 81 批分页/计数）
#   consults      → status/initiator + created_at 倒序、target_depts/opinions 的 jsonb 包含（GIN）
#   case_archive  → archived_at 倒序、dept+status、status、submitted_by+status（第 90 批）
# 全部 IF NOT EXISTS，多实例重复启动幂等；小表当前可能仍选顺序扫描，随数据量增长生效。
_PG_INDEX_DDL = (
    "CREATE INDEX IF NOT EXISTS ix_review_queue_status_ts "
    "ON review_queue(status, ts, id)",
    "CREATE INDEX IF NOT EXISTS ix_review_queue_submitter_ts "
    "ON review_queue(submitted_by, ts DESC, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_review_queue_reviewer_ts "
    "ON review_queue(reviewed_by, ts DESC, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_review_queue_ts "
    "ON review_queue(ts DESC, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_prescriptions_doctor_order "
    "ON prescriptions(doctor, ((data->>'created_at')) DESC NULLS LAST, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_prescriptions_status_order "
    "ON prescriptions(status, ((data->>'created_at')) DESC NULLS LAST, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_prescriptions_doctor_status "
    "ON prescriptions(doctor, status)",
    "CREATE INDEX IF NOT EXISTS ix_consults_status_order "
    "ON consults(status, ((data->>'created_at')) DESC NULLS LAST, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_consults_initiator_order "
    "ON consults(initiator, ((data->>'created_at')) DESC NULLS LAST, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_consults_target_depts_gin "
    "ON consults USING gin ((data->'target_depts'))",
    "CREATE INDEX IF NOT EXISTS ix_consults_opinions_gin "
    "ON consults USING gin ((data->'opinions'))",
    "CREATE INDEX IF NOT EXISTS ix_case_archive_archived_order "
    "ON case_archive(((data->>'archived_at')) DESC NULLS LAST, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_case_archive_dept_status "
    "ON case_archive(dept, status)",
    "CREATE INDEX IF NOT EXISTS ix_case_archive_status "
    "ON case_archive(status)",
    "CREATE INDEX IF NOT EXISTS ix_case_archive_submitted "
    "ON case_archive(((data->>'submitted_by')), status)",
    # 第 108 批：审计流过滤（/admin/audit 与 /admin/data 的 audit_actor/audit_event）
    # 与排序键对齐——(过滤列, ts DESC, id DESC) 使 SQL 侧过滤后分页可走索引，
    # 不必"扫全表 + 排序 + 过滤"。payload.action 未建索引：实测 2159 行全为 null
    # （JSONL 侧才有真实 action），建了也不会被使用，留待该字段真有数据时再补。
    "CREATE INDEX IF NOT EXISTS ix_audit_log_actor_ts "
    "ON audit_log(actor, ts DESC, id DESC)",
    "CREATE INDEX IF NOT EXISTS ix_audit_log_event_ts "
    "ON audit_log(event_type, ts DESC, id DESC)",
)


# ---------- 第 97 批：关键字子串检索改 LIKE + pg_trgm（可走 GIN 索引） ----------
# 语义与内存 `_match_query` 完全一致：agent/question/submitted_by/risk_reason 空格拼接、
# lower、子串包含。与 POSITION 的唯一差异是"可被 trigram GIN 索引加速"：
# 本机实测 1168 行、关键词命中 12 条时，POSITION 走 ts 索引并回表过滤（~0.99ms）；
# LIKE + trgm GIN 走 Bitmap Index Scan（~0.07ms）。用户输入的 % _ 与转义符 ! 一律按
# 字面量处理（ESCAPE '!'），不会变成通配符。
_SEARCH_EXPR = ("lower(coalesce(agent,'') || ' ' || coalesce(question,'') || ' ' || "
                "coalesce(submitted_by,'') || ' ' || coalesce(risk_reason,''))")


def _like_contains(kw: str) -> str:
    """转义 LIKE 通配符（转义符 '!'）：! → !!、% → !%、_ → !_，其余原样。"""
    return str(kw).replace("!", "!!").replace("%", "!%").replace("_", "!_")


def _search_cond(n: int) -> str:
    """子串匹配条件（$n 传入 `_like_contains(lower(keyword))`）。"""
    return f"{_SEARCH_EXPR} LIKE ('%' || ${n} || '%') ESCAPE '!'"


async def _ensure_search_index(c) -> None:
    """建 pg_trgm 扩展（best-effort）与 review_queue 检索表达式 GIN 索引。

    扩展可能装在非 search_path 的 schema（本机实测 `emr_svc`），因此 opclass 必须按
    `pg_opclass` 查出的真实 schema 限定；扩展/权限缺失时跳过——LIKE 仍然正确，只是
    退化为顺序扫描。索引只在首次启动创建（IF NOT EXISTS），大表首次建索引会占用启动时间。
    """
    try:
        await c.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
    except Exception as e:  # noqa: BLE001 —— 无权限/禁用扩展时不影响建表
        logger.warning("pg.trgm_extension_skipped", error=str(e)[:120])
    schema = await c.fetchval(
        "SELECT n.nspname FROM pg_opclass o JOIN pg_namespace n ON n.oid = o.opcnamespace "
        "WHERE o.opcname = 'gin_trgm_ops' LIMIT 1")
    if not schema:
        logger.warning("pg.trgm_index_skipped", detail="gin_trgm_ops 不可用（扩展未安装）")
        return
    quoted = '"' + str(schema).replace('"', '""') + '"'
    await c.execute(
        "CREATE INDEX IF NOT EXISTS ix_review_queue_search_trgm ON review_queue "
        f"USING gin (({_SEARCH_EXPR}) {quoted}.gin_trgm_ops)")


async def _init_impl() -> bool:
    """建池 + 建表（含旧表平滑升级：users.dept / review_queue.images）。"""
    global _pool
    pool = await asyncpg.create_pool(_dsn(), min_size=_pool_min(),
                                     max_size=_pool_max(), timeout=5)
    try:
        async with pool.acquire() as c:
            await c.execute("""CREATE TABLE IF NOT EXISTS audit_log(
                id bigserial primary key, ts timestamptz default now(),
                event_type text, actor text, payload jsonb)""")
            await c.execute("""CREATE TABLE IF NOT EXISTS users(
                username text primary key, role text, password_hash text,
                created_at timestamptz default now())""")
            # B1：users 补 dept 列（旧库平滑升级；存量旧行默认空科室）
            await c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS dept text NOT NULL DEFAULT ''")
            await c.execute("""CREATE TABLE IF NOT EXISTS review_queue(
                id text primary key, ts text, agent text, question text, answer text,
                confidence double precision, risk_reason text, status text,
                submitted_by text, reviewed_by text, review_note text, resolved_at text,
                resubmit_of text, attempt integer, meta jsonb,
                updated_at timestamptz default now())""")
            # B1：review_queue 补全字段（F3 入队影像）
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS images jsonb")
            # 任务1 根修：任务3 全交互留痕的 sources 列旧库平滑升级——旧库缺列会让
            # review UPSERT（$17::jsonb sources）每次全量同步确定性失败
            # （UndefinedColumnError: column "sources" does not exist），stale 窗口内
            # 读回落 JSON，一旦被其它域成功写"洗白"stale，JSON 独有记录即从 PG 真源读
            # 中"消失"（运行环境实测 imaging 留痕丢失根因，重启后本行自动补列）。
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS sources jsonb")
            # W4.1 review_queue 行级化补列：任务3 知情确认三字段此前不入 PG——
            # PG 主模式往返即丢，「我的待确认」恒空 / 重复确认校验失效（正确性缺口）。
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS "
                            "self_confirm_required boolean NOT NULL DEFAULT false")
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS "
                            "confirmed_by_self boolean")
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS "
                            "self_confirmed_at text")
            # 任务6：质控重提链——resubmit_of=原驳回记录 id、attempt=第 N 次提交、
            # meta=原始（已脱敏）病历留档，供「我的质控驳回」重新提交预填（旧库平滑升级）
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS resubmit_of text")
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS attempt integer")
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS meta jsonb")
            # 任务5：admin 审计流按 ts 倒序分页查询（audit_recent_pg）的排序索引
            await c.execute("CREATE INDEX IF NOT EXISTS ix_audit_log_ts ON audit_log(ts DESC)")
            # B1：科室字典（id=顺序号，保序）；llm_providers（data 内嵌 _seq 保序）
            await c.execute("CREATE TABLE IF NOT EXISTS departments(id integer primary key, name text)")
            await c.execute("""CREATE TABLE IF NOT EXISTS llm_providers(
                kind text, pid text, data jsonb, active bool)""")
            # 跨科室会诊（id data jsonb 全量留痕 + initiator/status 冗余列便于运维排查）
            await c.execute("""CREATE TABLE IF NOT EXISTS consults(
                id text primary key, data jsonb, initiator text, status text,
                updated_at timestamptz default now())""")
            # 第 124 批：会诊意见子表（**派生索引**，jsonb 仍是唯一真源）。
            # 为什么：收件箱"待本科室填意见"是 `NOT (opinions @> …)`，取反无法走 GIN
            # （§127 实测），而子表上的 `NOT EXISTS` 天然可索引。先双写 + 回填，读路径暂不切换。
            await c.execute(_CONSULT_OPINIONS_DDL)
            await c.execute("CREATE INDEX IF NOT EXISTS ix_consult_opinions_dept "
                            "ON consult_opinions(consult_id, dept)")
            # 评测 A4：补外键（先清孤儿派生行；失败只告警，旧库权限不足不影响启动）
            try:
                await c.execute(_CONSULT_OPINIONS_FK_DDL)
            except Exception as e:  # noqa: BLE001
                logger.warning("pg.consult_opinions_fk_skipped", error=str(e)[:120])
            # 阶段2.1：智能开药处方（id=rx-* 业务键；data jsonb 全量留痕 + doctor/status
            # 冗余列便于药剂科工作清单与运维排查；状态机语义在 prescriptions 域模块）
            await c.execute("""CREATE TABLE IF NOT EXISTS prescriptions(
                id text primary key, data jsonb, doctor text, status text,
                updated_at timestamptz default now())""")
            # 阶段4：病例库合规归档（id=arch-* 业务键；data jsonb 全量留痕 + dept/status
            # 冗余列便于科室筛选与软删除排查；归档/软删除语义在 case_archive 域模块）
            await c.execute("""CREATE TABLE IF NOT EXISTS case_archive(
                id text primary key, data jsonb, dept text, status text,
                updated_at timestamptz default now())""")
            # 阶段1.1：药品字典（name=规范名唯一键；aliases/brand_names=别名/商品名数组；
            # category=药理类别；level=处方等级/OTC）。1.3 起 medical_drug 读本表做规则匹配。
            await c.execute("""CREATE TABLE IF NOT EXISTS drug_dict(
                id serial primary key, name text unique, aliases jsonb,
                brand_names jsonb, category text, level text)""")
            # 阶段1.1：药物相互作用规则（severity 限定 高危/中危；mechanism=机制；
            # source 记录数据来源，默认 AI 辅助生成待药师核对）。药对唯一索引保证
            # upsert 幂等与查重（存储层统一规范化 a<b）。
            await c.execute("""CREATE TABLE IF NOT EXISTS drug_rules(
                id serial primary key, drug_a text, drug_b text,
                severity text CHECK (severity IN ('高危','中危')), mechanism text,
                source text default 'AI辅助生成·待药师核对')""")
            # 阶段1.2：management（处置建议）列——旧库平滑升级（机制/处置分列，管理页分字段展示）
            await c.execute("ALTER TABLE drug_rules ADD COLUMN IF NOT EXISTS management text")
            await c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_drug_rules_pair "
                            "ON drug_rules(drug_a, drug_b)")
            await c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_llm_providers_kind_pid "
                            "ON llm_providers(kind,pid)")
            # 轮 A3：运行时开关（key text pk / value jsonb / updated_at）——runtime_flags
            # 双写 PG 真源（JSON 兜底保留，见 load_runtime_flags/save_runtime_flags）
            await c.execute("""CREATE TABLE IF NOT EXISTS runtime_flags(
                key text primary key, value jsonb, updated_at timestamptz default now())""")
            # G87：临床技能卡（code 主键 + 全量 JSON + 状态冗余列，便于按状态筛选/运维排查）
            await c.execute("""CREATE TABLE IF NOT EXISTS agent_skills(
                code text primary key, data jsonb NOT NULL, status text NOT NULL,
                updated_at timestamptz default now())""")
            # G88：Bot / Bot 团队 / Bot 长期记忆（同一形态：主键 + JSON + 状态）
            for table, key in (("agent_bots", "code"), ("agent_bot_teams", "code"),
                               ("agent_bot_memories", "id"),
                               ("agent_skill_experiences", "id"),
                               ("agent_skill_proposals", "id"),
                               ("agent_skill_metrics", "id"),
                               ("agent_skill_advices", "id"),
                               # 评审 M-39：睡眠记忆报告此前只写 JSON，多实例读不到
                               ("agent_sleep_reports", "reportId")):
                await c.execute(
                    f"""CREATE TABLE IF NOT EXISTS {table}(
                        {key} text primary key, data jsonb NOT NULL, status text NOT NULL,
                        updated_at timestamptz default now())""")
            # 第 92 批：上述表建好后统一补索引（幂等，见 _PG_INDEX_DDL 注释）
            for _ddl in _PG_INDEX_DDL:
                await c.execute(_ddl)
            # 第 97 批：关键字检索的 trigram GIN 索引（扩展/权限缺失时跳过，检索照常）
            try:
                await _ensure_search_index(c)
            except Exception as e:  # noqa: BLE001 —— 检索加速失败绝不影响启动
                logger.warning("pg.search_index_skipped", error=str(e)[:120])
    except Exception:
        try:
            await pool.close()
        except Exception:  # noqa: BLE001
            pass
        raise
    _pool = pool
    # W4.1：池就绪即清 stale——stale 的语义是"本进程内 PG 写失败过"（读回落 JSON）。
    # 启动期 seed 判空读发生在池就绪前，会被误置 stale 且无后续写来清除（冷启动+
    # PG 非空场景下该域永远回落旧 JSON——实测登录 401 根因）。池刚就绪时本进程
    # 尚无写失败，清空是安全的；读路径有空表/异常回落 JSON 的既有保护兜底。
    _repo_pg_stale.clear()
    _stale_warned.clear()
    logger.info("pg_store.ready")
    return True


async def init() -> bool:
    """建池+建表（池归属专用 DB 循环）。成功 True；任何异常降级 JSON 模式（_pool=None）。
    任务2：PG_OFFLINE=on（显式离线模式）→ 直接跳过建池（纯静默 JSON 模式，
    eval/脚本/测试进程用），返回 False 且不产生告警。"""
    global _pool
    if offline_mode():
        logger.info("pg_store.offline_mode", detail="PG_OFFLINE=on：跳过 PG 初始化，JSON 为唯一真源")
        _pool = None
        return False
    try:
        return bool(await _arun(_init_impl()))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg_store.unavailable", error=str(e)[:150])
        _pool = None
        return False


async def _close_pool_impl() -> None:
    if _pool:
        try:
            await _pool.close()
        except Exception:  # noqa: BLE001
            pass


async def close() -> None:
    global _pool
    if _pool:
        try:
            await _arun(_close_pool_impl())
        except Exception:  # noqa: BLE001
            pass
    _pool = None
    _stop_db_loop()


def available() -> bool:
    return _pool is not None


def storage_status() -> dict:
    """进程内存储真源状态（只读；不含连接串/凭据/账号）。

    ``mode``：
    - ``offline``：``PG_OFFLINE=on``，JSON 是唯一真源（脚本/单测）；
    - ``pg``：池已建立，读走 PG 真源；
    - ``json-fallback``：未显式离线但池不可用（掉线/初始化失败），读回落 JSON。

    ``staleDomains``：本进程内"PG 写失败 → 读回落 JSON"的域（文件名）。
    这是多副本漂移的早期信号——单实例仍能读写，但其它实例看不到这些域的写入。
    """
    return {
        "mode": "offline" if offline_mode() else ("pg" if _pool else "json-fallback"),
        "ready": _pool is not None,
        "staleDomains": sorted(_repo_pg_stale),
        # 第 50 批：连接熔断状态（打开期间读快速回落 JSON，不再每次等连接超时）
        "circuitOpen": circuit_open(),
    }


# ---------- stale 域恢复探测（第 54 批）----------
# 记录"上次读失败的 loader"，抓取心跳时每域重试一次：成功即清 stale、告警自动解除，
# 不再依赖用户碰巧打开页面。只保存可调用对象，不存数据/凭据。
_pg_stale_probe: dict[str, object] = {}
_probe_lock = threading.Lock()


def _remember_stale_probe(domain: str, load_fn) -> None:
    with _probe_lock:
        _pg_stale_probe[domain] = load_fn


def _forget_stale_probe(domain: str) -> None:
    with _probe_lock:
        _pg_stale_probe.pop(domain, None)


def probe_stale_domains(limit: int = 3) -> dict:
    """对 stale 域做一次只读重探（每次最多 limit 个），成功即清除标记。

    返回 ``{probed, cleared, remaining}``；熔断打开时返回 ``skipped=circuit_open``，
    避免探测本身又把请求拖进连接超时。
    """
    remaining = len(_repo_pg_stale)
    if circuit_open():
        return {"probed": 0, "cleared": 0, "remaining": remaining, "skipped": "circuit_open"}
    with _probe_lock:
        items = [(d, fn) for d, fn in _pg_stale_probe.items()
                 if d in _repo_pg_stale and fn is not None][:max(1, int(limit))]
    probed = cleared = 0
    for domain, load_fn in items:
        probed += 1
        try:
            _run_pg(load_fn())
        except Exception:  # noqa: BLE001 —— 探测失败保持 stale，下次心跳再试
            continue
        _repo_pg_stale.discard(domain)
        _stale_warned.discard(domain)
        _forget_stale_probe(domain)
        cleared += 1
    return {"probed": probed, "cleared": cleared, "remaining": len(_repo_pg_stale)}


def _require_pg_for_rebuild() -> None:
    """重建镜像只允许在 PG 真源可用时进行——否则会把空/旧数据写回镜像。"""
    if offline_mode() or not _pool:
        raise PGUnavailable("PG 不可用：拒绝重建镜像（避免用空数据覆盖）")


def rebuild_skills_mirror(path: str) -> int:
    """用 PG 真源重写技能卡 JSON 镜像；返回写入条数。"""
    _require_pg_for_rebuild()
    items = _run_pg(_pg_load_skills()) or []
    _write_json_atomic(path, items)
    return len(items)


def rebuild_json_mirror(table: str, path: str) -> int:
    """用 PG 真源重写通用 JSON 列表镜像（表名走白名单）；返回写入条数。"""
    _require_pg_for_rebuild()
    key = _JSON_LIST_KEYS[table]
    items = _run_pg(_pg_load_json_list(table, key)) or []
    _write_json_atomic(path, items)
    return len(items)


# ---------- 评测 P0-4：临床域镜像对账（PG 真源 → JSON 镜像） ----------
# 医学域四个列表（审核队列/会诊/处方/病例库）不走 _JSON_LIST_KEYS 通用表，各有一
# 专属读函数。镜像写路径（mirror_json_upsert）已加锁；这里补"按 PG 全量重建"的
# 修复入口，供启动对账（resync_clinical_mirrors）与运维手动重建共用。

def _clinical_mirror_sources() -> dict[str, tuple[str, str, object]]:
    """域名 → (PG 表名, JSON 镜像路径, PG 全量读协程函数)。

    路径在调用时解析（便于测试 monkeypatch，也避免模块级导入环）。"""
    from backend.core.case_archive import CASE_ARCHIVE_FILE
    from backend.core.consults import CONSULTS_FILE
    from backend.core.medical_review import QUEUE_FILE
    from backend.core.prescriptions import PRESCRIPTIONS_FILE
    return {
        "reviewQueue": ("review_queue", QUEUE_FILE, _pg_load_queue),
        "consults": ("consults", CONSULTS_FILE, _pg_load_consults),
        "prescriptions": ("prescriptions", PRESCRIPTIONS_FILE, _pg_load_prescriptions),
        "caseArchive": ("case_archive", CASE_ARCHIVE_FILE, _pg_load_case_archive),
    }


def rebuild_clinical_mirror(domain: str, path: str | None = None,
                            allow_empty: bool = False) -> int:
    """用 PG 真源重写临床域 JSON 镜像；返回写入条数。

    默认 PG 该域为空 → 抛 ``PGUnavailable`` 拒绝（无法区分"真没数据"与"迁移没跑"，
    宁可不写也不要用空表覆盖镜像）；purge 场景显式传 ``allow_empty=True``——
    此时 PG 已清空、镜像必须跟着清空，否则必然分叉。
    镜像仅供人工排查/灾难参考；权威恢复走 pg_dump。"""
    _require_pg_for_rebuild()
    src = _clinical_mirror_sources().get(domain)
    if src is None:
        raise KeyError(f"未知临床镜像域：{domain}")
    _, default_path, loader = src
    target = path or default_path
    items = _run_pg(loader()) or []
    if not items and not allow_empty:
        raise PGUnavailable(f"{domain} 在 PG 中为空：拒绝用空数据覆盖 JSON 镜像")
    with _MIRROR_LOCK, _mirror_file_lock(target):
        _write_json_atomic(target, items)
    return len(items)


def _rebuild_queue_mirror_safe() -> None:
    """purge 后按 PG 重建审核队列 JSON 镜像（best-effort）。

    评测 P0-4 第 4 条：purge_*_reviews 只删 PG，镜像保留被清行 → PG/JSON 必然分叉，
    回落路径会把已清理的 drug/pending 行"复活"。"""
    from backend.core.medical_review import QUEUE_FILE
    try:
        rebuild_clinical_mirror("reviewQueue", QUEUE_FILE, allow_empty=True)
    except Exception as e:  # noqa: BLE001 —— 镜像修复失败不影响 purge 结果
        logger.warning("pg.purge_mirror_resync_failed", error=str(e)[:120])


async def resync_clinical_mirrors() -> dict:
    """PG 真源 → 临床域 JSON 镜像对账重建（启动时调用；幂等）。

    评测 P0-4：镜像写是 read-modify-write，多实例并发会丢行，全量镜像又可能用过期
    内存快照——实测 PG 有、JSON 无（consults 17 vs 15，review_queue 70 行）以及
    同一单状态回退（open vs closed）。启动时（migrate 把 JSON 独有行补进 PG 之后）
    按 PG 全量投影回镜像，可把历史分叉一次性纠正并写入修正条数日志。

    安全边界：① 仅 pg_primary=true（PG 为真源）时执行，旧编排 JSON 才是真源，
    投影会删掉尚未同步 PG 的新数据；② PG 空表跳过（保留镜像）；③ 写盘仍走镜像锁，
    与运行中的单行镜像写互斥。返回 {域名: 统计}；任何异常只告警不影响启动。"""
    if offline_mode() or not _pool or not bool(getattr(settings, "pg_primary", True)):
        return {}
    out: dict[str, dict] = {}
    for name, (_, path, loader) in _clinical_mirror_sources().items():
        try:
            rows = await _arun(loader())
        except Exception as e:  # noqa: BLE001 —— 单域失败不影响其它域
            logger.warning("pg.mirror_resync_read_failed", domain=name,
                           error=str(e)[:120])
            continue
        if not rows:
            continue  # 空表：PG 无权威数据，保留镜像不动
        try:
            before = _read_json(path, [])
            before_list = [r for r in before if isinstance(r, dict)] \
                if isinstance(before, list) else []
            before_by_id = {r.get("id"): r for r in before_list if r.get("id")}
            after_ids = {r.get("id") for r in rows if r.get("id")}
            stats = {
                "pg": len(rows),
                "json_before": len(before_list),
                "added": len(after_ids - set(before_by_id)),
                "removed": len(set(before_by_id) - after_ids),
                "changed": sum(1 for r in rows
                               if r.get("id") in before_by_id
                               and before_by_id[r["id"]] != r),
            }
            with _MIRROR_LOCK, _mirror_file_lock(path):
                _write_json_atomic(path, rows)
        except Exception as e:  # noqa: BLE001 —— 镜像写失败只告警
            logger.warning("pg.mirror_resync_write_failed", domain=name,
                           error=str(e)[:120])
            continue
        out[name] = stats
        if stats["added"] or stats["removed"] or stats["changed"]:
            logger.info("pg.mirror_resync", domain=name, **stats)
    return out


def _emit_pg_family(lines: list, prefix: str, stats: dict, anchors: set) -> None:
    """把一组 (domain, op) 计量写成 Prometheus 文本（计数器 + 直方图）。

    prefix 为族名前缀（``…pg_query`` / ``…pg_write``），由本模块内部常量固定传入。
    第 102 批：**只导出已触达的键**（total>0）外加每族一个零样本锚点——
    否则 25 个键 × 12 行会固定占掉 ~30KB 抓取体积，而绝大多数键长期为 0。
    锚点保证族名/标签格式始终存在（静态门禁与首抓可见性不受影响）。
    """
    with _pg_metrics_lock:
        snapshot = [(d, o, {**stat, "buckets": list(stat["buckets"])})
                    for (d, o), stat in sorted(stats.items())]
    for domain, op, stat in snapshot:
        if stat["total"] <= 0 and (domain, op) not in anchors:
            continue
        safe_d = str(domain).replace("\\", "\\\\").replace('"', '\\"')
        safe_o = str(op).replace("\\", "\\\\").replace('"', '\\"')
        labels = f'domain="{safe_d}",op="{safe_o}"'
        lines.append(f"{prefix}_total{{{labels}}} {int(stat['total'])}")
        lines.append(f"{prefix}_errors_total{{{labels}}} {int(stat['errors'])}")
        for bound, n in zip(_PG_LATENCY_BUCKETS, stat["buckets"]):
            lines.append(f"{prefix}_duration_seconds_bucket"
                         f'{{{labels},le="{bound}"}} {int(n)}')
        lines.append(f"{prefix}_duration_seconds_bucket"
                     f'{{{labels},le="+Inf"}} {int(stat["total"])}')
        lines.append(f"{prefix}_duration_seconds_sum{{{labels}}} {stat['seconds']:.6f}")
        lines.append(f"{prefix}_duration_seconds_count{{{labels}}} {int(stat['total'])}")


def prometheus_text() -> str:
    """存储真源指标（Prometheus 文本）：池可用 / 模式 / 按域 stale。

    为什么导出：stale 只在**本进程**内可见；多副本部署时，某个实例写 PG 失败回落
    JSON，其它实例完全无感。导出为带 ``domain`` 标签的 gauge 后，集中 Prometheus
    可以跨实例发现"有域在漂移"。

    基数与安全：域数量固定且有限（不取用户输入）；只输出域文件名，不含 DSN/账号。
    无 stale 域时不输出样本——``sum(...) > 0`` 的告警此时不触发（正确语义）。
    """
    st = storage_status()
    lines = [
        "# HELP medassist_agent_storage_ready 存储真源池可用（1=PG 池已建立，0=回落 JSON）",
        "# TYPE medassist_agent_storage_ready gauge",
        f"medassist_agent_storage_ready {1 if st['ready'] else 0}",
        "# HELP medassist_agent_storage_mode 存储模式（offline/pg/json-fallback，值为 1）",
        "# TYPE medassist_agent_storage_mode gauge",
        f'medassist_agent_storage_mode{{mode="{st["mode"]}"}} 1',
        "# HELP medassist_agent_storage_stale_domains 本进程内 PG 写失败、读回落 JSON 的域（每个域一条）",
        "# TYPE medassist_agent_storage_stale_domains gauge",
        "# HELP medassist_agent_storage_circuit_open PG 连接熔断是否打开（1=快速回落中）",
        "# TYPE medassist_agent_storage_circuit_open gauge",
        f"medassist_agent_storage_circuit_open {1 if circuit_open() else 0}",
        "# HELP medassist_agent_pg_query_total 真正尝试过的 PG 查询次数（domain/op）",
        "# TYPE medassist_agent_pg_query_total counter",
        "# HELP medassist_agent_pg_query_errors_total PG 查询失败次数（已回落 JSON，含熔断快速失败）",
        "# TYPE medassist_agent_pg_query_errors_total counter",
        "# HELP medassist_agent_pg_query_duration_seconds PG 查询耗时直方图（秒，可算 P95/P99）",
        "# TYPE medassist_agent_pg_query_duration_seconds histogram",
        "# HELP medassist_agent_pg_write_total 真正尝试过的 PG 写次数（镜像/全量同步/单行 UPSERT）",
        "# TYPE medassist_agent_pg_write_total counter",
        "# HELP medassist_agent_pg_write_errors_total PG 写失败次数（镜像写失败不冒泡，主写失败由调用方处理）",
        "# TYPE medassist_agent_pg_write_errors_total counter",
        "# HELP medassist_agent_pg_write_duration_seconds PG 写耗时直方图（秒，可算 P95/P99）",
        "# TYPE medassist_agent_pg_write_duration_seconds histogram",
    ]
    _emit_pg_family(lines, "medassist_agent_pg_query", _pg_query_stats,
                    _PG_METRIC_ANCHORS["query"])
    _emit_pg_family(lines, "medassist_agent_pg_write", _pg_write_stats,
                    _PG_METRIC_ANCHORS["write"])
    for domain in st["staleDomains"]:
        safe = str(domain).replace("\\", "\\\\").replace('"', '\\"')
        lines.append(f'medassist_agent_storage_stale_domains{{domain="{safe}"}} 1')
    return "\n".join(lines) + "\n"


async def ping() -> bool:
    """只读连通性探针（admin 运维卡片用）：SELECT 1，任何异常/未建池一律 False。
    绝不写库、绝不重试（面板展示需快速失败），与 mirror_* 的 best-effort 写路径互不影响。"""
    if not _pool:
        return False

    async def _impl() -> bool:
        async with _pool.acquire() as c:
            return bool(await c.fetchval("SELECT 1"))

    try:
        return await _arun(_impl())
    except Exception:  # noqa: BLE001 —— 探针只报状态，绝不冒泡
        return False


# ---------- 合规镜像写（best-effort；池操作全部经专用 DB 循环） ----------

# 第 112 批：镜像审计事件名是"域.动作"（review.approved），而 JSONL 侧 payload.action 是
# 动作名（resolved_approved）。此前镜像 payload 不带 action → PG 部署下 admin 审计流的
# "按动作过滤/候选值"恒为空（第 107–110 批交付的 action 维度实际不可用）。
# 现在写入时自动补 action：默认取点号后半段，跨源命名不一致的用别名表纠正。
_ACTION_ALIASES = {
    "review.approved": "resolved_approved",
    "review.rejected": "resolved_rejected",
    "consult.created": "consult_created",
    "consult.opinion": "consult_opinion",
    "consult.closed": "consult_closed",
}


def audit_action_for(event_type: str, action: str | None = None) -> str:
    """镜像事件 → payload.action（显式传入优先，其次别名表，最后取点号后半段）。"""
    if action:
        return action
    ev = str(event_type or "")
    if ev in _ACTION_ALIASES:
        return _ACTION_ALIASES[ev]
    act = ev.split(".", 1)[-1] if "." in ev else ev
    # 历史答案事件是 `<agent>.answer`，JSONL 侧 action 统一为 answered（评测 A3 同构）
    return "answered" if act == "answer" else act


def _audit_payload_with_action(event_type: str, payload: dict | None,
                               action: str | None = None) -> dict:
    """补 action 但不覆盖调用方已有值（已有视为权威，避免双向改写）。"""
    p = dict(payload or {})
    if not p.get("action"):
        p["action"] = audit_action_for(event_type, action)
    return p


async def _mirror_audit_impl(event_type: str, actor: str, payload: dict) -> None:
    async with _pool.acquire() as c:
        await c.execute("INSERT INTO audit_log(event_type,actor,payload) VALUES($1,$2,$3::jsonb)",
                        event_type, actor, json.dumps(payload, ensure_ascii=False))


async def mirror_audit(event_type: str, actor: str, payload: dict,
                       action: str | None = None) -> None:
    """镜像写审计（best-effort）。action 可显式指定；缺省按事件名/别名表补进 payload。"""
    if not _pool:
        return
    try:
        await _pg_write_async("audit_log", "mirror",
                              _mirror_audit_impl(event_type, actor,
                                                 _audit_payload_with_action(event_type, payload,
                                                                            action)))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_audit_failed", error=str(e)[:120])


def mirror_audit_sync(event_type: str, actor: str, payload: dict,
                      action: str | None = None) -> None:
    """同步审计镜像（**审计写入的唯一挂钩点**；任意线程可调，best-effort）。

    评测 02 P0-3（A3）：此前 PG 审计只在 28 个显式调用点镜像（覆盖率约 7%），且事件名
    是"域.动作"（review.approved），与 JSONL 的"域 + payload.action"不同构。改为由
    `medical_audit.AuditLog._emit` 在每次 JSONL 落盘后统一调用本函数——
    - event_type 与 JSONL 完全一致（PG/JSONL 天然 1:1，新增审计点自动进 PG）；
    - payload.action 由 `write(action=...)` 写入，历史点号名由读侧 `_normalize_audit_row` 归一。
    失败只告警（JSONL 仍是权威记录），PG 未建池/离线直接 no-op。
    """
    if offline_mode() or not _pool:
        return
    try:
        _pg_write_call("audit_log", "mirror",
                       _mirror_audit_impl(event_type, actor,
                                          _audit_payload_with_action(event_type, payload,
                                                                     action)))
    except PGUnavailable:
        return  # 熔断期：JSONL 已落盘，恢复后新事件自然补齐
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_audit_failed", error=str(e)[:120])


async def _mirror_user_impl(username: str, role: str, pw_hash: str) -> None:
    async with _pool.acquire() as c:
        # 不更新 dept：镜像写以登录态为准，dept 由 repo 层 save_users 全量同步，避免被置空
        await c.execute("INSERT INTO users(username,role,password_hash) VALUES($1,$2,$3) "
                        "ON CONFLICT(username) DO UPDATE SET role=EXCLUDED.role, password_hash=EXCLUDED.password_hash",
                        username, role, pw_hash)


async def mirror_user(username: str, role: str, pw_hash: str) -> None:
    if not _pool:
        return
    try:
        await _pg_write_async("users", "mirror",
                              _mirror_user_impl(username, role, pw_hash))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_user_failed", error=str(e)[:120])


_REVIEW_COLS = ("id", "ts", "agent", "question", "answer", "confidence", "risk_reason",
                "status", "submitted_by", "reviewed_by", "review_note", "resolved_at",
                "resubmit_of", "attempt", "meta", "sources",
                "self_confirm_required", "confirmed_by_self", "self_confirmed_at")

_REVIEW_UPSERT = """INSERT INTO review_queue(id,ts,agent,question,answer,confidence,risk_reason,
   status,submitted_by,reviewed_by,review_note,resolved_at,images,resubmit_of,attempt,meta,sources,
   self_confirm_required,confirmed_by_self,self_confirmed_at)
   VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13::jsonb,$14,$15,$16::jsonb,$17::jsonb,$18,$19,$20)
   ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status, reviewed_by=EXCLUDED.reviewed_by,
   review_note=EXCLUDED.review_note, resolved_at=EXCLUDED.resolved_at,
   images=EXCLUDED.images, sources=EXCLUDED.sources, updated_at=now(),
   self_confirm_required=EXCLUDED.self_confirm_required,
   confirmed_by_self=EXCLUDED.confirmed_by_self,
   self_confirmed_at=EXCLUDED.self_confirmed_at"""


def _review_row(item: dict) -> tuple:
    """队列条目 → INSERT 参数元组（images/meta/sources 序列化为 jsonb 文本；空/缺失一律 NULL；
    resubmit_of/attempt 为任务6重提链字段，普通提交为 NULL/NULL；sources 为任务3留痕溯源）。"""
    images = item.get("images")
    meta = item.get("meta")
    sources = item.get("sources")
    try:
        attempt = int(item["attempt"]) if item.get("attempt") is not None else None
    except (TypeError, ValueError):
        attempt = None
    return (item.get("id"), item.get("ts"), item.get("agent"), item.get("question"),
            item.get("answer"), item.get("confidence"), item.get("risk_reason"),
            item.get("status", "pending"), item.get("submitted_by"),
            item.get("reviewed_by"), item.get("review_note"), item.get("resolved_at"),
            json.dumps(images, ensure_ascii=False) if images else None,
            item.get("resubmit_of"), attempt,
            json.dumps(meta, ensure_ascii=False) if meta else None,
            json.dumps(sources, ensure_ascii=False) if sources else None,
            bool(item.get("self_confirm_required")),
            bool(item["confirmed_by_self"]) if item.get("confirmed_by_self") else None,
            item.get("self_confirmed_at"))


async def _mirror_review_impl(item: dict) -> None:
    async with _pool.acquire() as c:
        await c.execute(_REVIEW_UPSERT, *_review_row(item))


async def mirror_review(item: dict | None) -> None:
    if not _pool or not item:
        return
    try:
        await _pg_write_async("review_queue", "mirror", _mirror_review_impl(item))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_review_failed", error=str(e)[:120])


def upsert_review_row(item: dict) -> None:
    """W4.1 单行 UPSERT（review_queue）：medical_review 域写点的行级落盘——多实例并发
    各自改不同条目时不再整表互相覆盖（save_queue 全量同步 last-writer-wins 的根除）。
    无池/离线/失败抛 RuntimeError：调用方（域锁内）决定回落 save_queue 全量。
    与 upsert_user_row 同纪律：本函数只管 PG 行，JSON 镜像由调用方 mirror_json_file。"""
    if offline_mode() or not _pool:
        raise RuntimeError("upsert_review_row 需要 PG 池（离线/单机形态请走 save_queue 全量同步）")
    _pg_write_call("review_queue", "upsert_row", _mirror_review_impl(item))


async def _counts_impl() -> dict:
    out = {}
    async with _pool.acquire() as c:
        for t in ("audit_log", "users", "review_queue"):
            try:
                out[t] = await c.fetchval(f"SELECT count(*) FROM {t}")  # noqa: S608 (固定表名)
            except Exception:  # noqa: BLE001
                out[t] = -1
    return out


async def counts() -> dict:
    """验证用：返回各表行数（PG 不可用返回空）。"""
    if not _pool:
        return {}
    try:
        return await _arun(_counts_impl())
    except Exception:  # noqa: BLE001
        return {}


# ---------- 任务5：admin 审计流 PG 真源查询（audit_log 按 ts 倒序分页） ----------

def _audit_where(actor: str, event_type: str, action: str,
                 start: datetime | None = None,
                 end: datetime | None = None) -> tuple[str, list]:
    """第 108/109 批：审计流过滤 SQL 片段（分页 / 计数复用）。

    返回 (where_sql, params)；全部条件为空 → ("", [])，SQL 与旧版逐字一致。
    时间区间为**半开** [start, end)：调用方把"截止日"换算成次日 00:00，边界不重不漏。
    值一律走 $n 占位符，条件列名是固定常量（无注入面）。"""
    conds, params = [], []
    if actor:
        params.append(actor)
        conds.append(f"actor = ${len(params)}")
    if event_type:
        params.append(event_type)
        # 评测 02 P0-3（A3）：历史 PG 行存"域.动作"（review.approved），JSONL 存"域"+
        # payload.action。按域过滤时必须同时命中两种形态，否则面板在 PG/JSONL 间切换
        # 命中数完全不同；按旧点号名精确过滤仍然可用（等值分支）。
        conds.append(f"(event_type = ${len(params)} OR event_type LIKE ${len(params)} || '.%')")
    if action:
        params.append(action)
        conds.append(f"payload->>'action' = ${len(params)}")
    if start is not None:
        params.append(start)
        conds.append(f"ts >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"ts < ${len(params)}")
    return ((" WHERE " + " AND ".join(conds)) if conds else ""), params


def _normalize_audit_row(event_type: str, payload: dict) -> tuple[str, dict]:
    """把历史的"域.动作"行归一成与 JSONL 同构的 (域名, payload.action)（评测 A3）。

    第 112 批起新行本就带 payload.action；更早的行只有 event_type=review.approved。
    读时归一（不改库里历史数据）→ 同一过滤值在 PG/JSONL 两侧命中同一批记录。
    """
    ev = str(event_type or "")
    if "." not in ev:
        return ev, payload
    domain, _, _rest = ev.partition(".")
    p = dict(payload or {})
    if not p.get("action"):
        p["action"] = audit_action_for(ev)
    return domain, p


async def _audit_recent_pg_impl(limit: int, offset: int, actor: str = "",
                                event_type: str = "", action: str = "",
                                start: datetime | None = None,
                                end: datetime | None = None) -> list:
    """第 107 批：审计流可选过滤（actor/event_type/payload.action，全部精确匹配）。

    过滤在 SQL 侧完成，offset 是"过滤后"的偏移（与前端分页一致）；全部条件为空时
    SQL 与旧版逐字一致（无 WHERE）。第 109 批增加半开时间区间 [start, end)。
    评测 A3：输出统一归一为"域名 + payload.action"（历史点号行不再与 JSONL 语义分裂）。
    """
    where, params = _audit_where(actor, event_type, action, start, end)
    n = len(params)
    async with _pool.acquire() as c:
        rows = await c.fetch(  # noqa: S608 —— 片段均为固定常量，值走 $n
            "SELECT ts, event_type, actor, payload FROM audit_log" + where
            + f" ORDER BY ts DESC, id DESC LIMIT ${n + 1} OFFSET ${n + 2}",
            *params, limit, offset)
    out = []
    for r in rows:
        p = r["payload"]
        if isinstance(p, (str, bytes)):  # asyncpg 默认 jsonb 返回 str；兼容驱动直返 dict
            try:
                p = json.loads(p)
            except Exception:  # noqa: BLE001 —— 单行 payload 损坏降级空对象，不拖垮整页
                p = {}
        ts = r["ts"]
        event_name, payload_out = _normalize_audit_row(r["event_type"], p)
        out.append({"ts": ts.isoformat() if hasattr(ts, "isoformat") else str(ts),
                    "event_type": event_name, "actor": r["actor"], "payload": payload_out})
    return out


async def _audit_count_pg_impl(actor: str = "", event_type: str = "", action: str = "",
                               start: datetime | None = None,
                               end: datetime | None = None) -> int:
    """第 108 批：过滤后的审计总条数（前端据此显示"共 N 条"并精确禁用下一页）。"""
    where, params = _audit_where(actor, event_type, action, start, end)
    async with _pool.acquire() as c:
        return int(await c.fetchval("SELECT count(*) FROM audit_log" + where, *params) or 0)  # noqa: S608


async def audit_recent_pg(limit: int = 50, offset: int = 0, actor: str = "",
                          event_type: str = "", action: str = "",
                          start: datetime | None = None,
                          end: datetime | None = None) -> list | None:
    """任务5：admin 审计流数据源——PG audit_log 表按 ts 倒序分页。

    第 107 批：支持 actor/event_type/action 精确过滤（SQL 侧过滤后再分页）。
    第 108 批：读路径纳入 Prometheus 计量（domain=audit_log, op=recent_page）。
    第 109 批：支持半开时间区间 [start, end)。
    返回与 AuditLog.recent(JSONL) 相同结构 [{ts,event_type,actor,payload}]（payload
    内含 action/role 等字段）；未建池/异常/超时一律返回 None（调用方回落 JSONL 现状），
    绝不冒泡影响 admin 面板。"""
    if not _pool:
        return None
    try:
        return await _arun_metered("audit_log", "recent_page",
                                   _audit_recent_pg_impl(max(1, int(limit)), max(0, int(offset)),
                                                         str(actor or ""), str(event_type or ""),
                                                         str(action or ""), start, end))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.audit_recent_failed", error=str(e)[:120])
        return None


async def audit_count_pg(actor: str = "", event_type: str = "", action: str = "",
                         start: datetime | None = None,
                         end: datetime | None = None) -> int | None:
    """第 108 批：过滤后的审计总条数；PG 不可用/异常 → None（调用方按 JSONL 计数）。

    与 audit_recent_pg 同纪律：best-effort，绝不冒泡影响 admin 面板。"""
    if not _pool:
        return None
    try:
        return await _arun_metered("audit_log", "count",
                                   _audit_count_pg_impl(str(actor or ""), str(event_type or ""),
                                                        str(action or ""), start, end))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.audit_count_failed", error=str(e)[:120])
        return None


# ---------- 第 110 批：审计过滤框候选值（GROUP BY 聚合） ----------
_AUDIT_FACET_LIMIT = 100


async def _audit_facets_pg_impl(start: datetime | None = None,
                                end: datetime | None = None) -> dict:
    """过滤框候选值：actor / event_type / payload.action 按出现次数倒序（各前 100）。

    与分页/计数共用 `_audit_where` 的时间窗；空值/空串排除（否则候选里出现空选项）。
    三类各一次 GROUP BY（本地各 ~1ms），比前端跨页收集稳定且完整。
    """
    where, params = _audit_where("", "", "", start, end)
    out: dict[str, list] = {"actors": [], "events": [], "actions": []}
    async with _pool.acquire() as c:
        # 评测 A3：event_type 候选按"域"聚合（历史 review.approved 与新的 review 合并成
        # 一个选项）；action 取 payload.action（第 112 批起所有镜像行都有）。
        for key, expr in (("actors", "actor"),
                          ("events", "split_part(event_type, '.', 1)"),
                          ("actions", "payload->>'action'")):
            cond = f"{expr} IS NOT NULL AND {expr} <> ''"
            clause = (where + " AND " + cond) if where else (" WHERE " + cond)
            rows = await c.fetch(  # noqa: S608 —— 列名是固定常量，值走 $n
                f"SELECT {expr} AS v, count(*) AS n FROM audit_log" + clause
                + f" GROUP BY 1 ORDER BY 2 DESC, 1 ASC LIMIT {_AUDIT_FACET_LIMIT}",
                *params)
            out[key] = [{"v": r["v"], "n": int(r["n"])} for r in rows]
    return out


async def audit_facets_pg(start: datetime | None = None,
                          end: datetime | None = None) -> dict | None:
    """审计过滤框候选值；PG 不可用/异常 → None（调用方按 JSONL 聚合）。

    与分页/导出同纪律：best-effort，绝不冒泡影响面板。"""
    if not _pool:
        return None
    try:
        return await _arun_metered("audit_log", "facets", _audit_facets_pg_impl(start, end))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.audit_facets_failed", error=str(e)[:120])
        return None


async def _purge_pending_impl() -> str:
    async with _pool.acquire() as c:
        return await c.execute("DELETE FROM review_queue WHERE status='pending'")


async def purge_pending_reviews() -> str:
    """镜像一致性：清空 review_queue 中 pending 行（与 review.purge_pending 配套）。best-effort。"""
    if not _pool:
        return ""
    try:
        res = await _pg_write_async("review_queue", "purge", _purge_pending_impl())
    except Exception:  # noqa: BLE001
        return ""
    await asyncio.to_thread(_rebuild_queue_mirror_safe)  # 评测 P0-4：镜像跟随 PG 清理
    return res


def purge_pending_reviews_strict() -> str:
    """W4.1 行级清空（严格版）：与 purge_pending_reviews 同 SQL，但无池/失败抛出——
    purge 是批量行级删除（按 status），PG 删失败必须让调用方感知（不产生
    PG 留 pending / JSON 已清 的分裂）；JSON 镜像由调用方 mirror_json_file。"""
    if offline_mode() or not _pool:
        raise RuntimeError("purge_pending_reviews_strict 需要 PG 池")
    return _pg_write_call("review_queue", "purge", _purge_pending_impl())


async def _purge_drug_impl() -> str:
    async with _pool.acquire() as c:
        return await c.execute("DELETE FROM review_queue WHERE agent='drug'")


async def purge_drug_reviews() -> str:
    """问题2：清理 review_queue 中遗留 drug 行（两药快查已下线；与 scripts/
    purge_drug_review_items.py 的 JSON 清理配套，PG 镜像同纪律）。best-effort：
    未建池返回空串，异常不冒泡。审计 jsonl 不在本函数职责内（合规留痕永不清）。"""
    if not _pool:
        return ""
    try:
        res = await _pg_write_async("review_queue", "purge_drug", _purge_drug_impl())
    except Exception:  # noqa: BLE001
        return ""
    await asyncio.to_thread(_rebuild_queue_mirror_safe)  # 评测 P0-4：镜像跟随 PG 清理
    return res


# ---------- B1 repo 层：四个模块文件读写的统一出入口（PG 真源 + JSON 兜底） ----------

def _read_json(path: str, default):
    """通用 JSON 读（repo 独立使用/迁移脚本）；调用方自有损坏兜底语义经 json_loader 传入。"""
    try:
        if not os.path.isfile(path):
            return default
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return default


def _write_json_atomic(path: str, data) -> None:
    """通用原子写（tmp + os.replace），与既有各模块写盘形态一致（ensure_ascii=False/indent=2）。

    临时文件名带 pid/线程号：多进程/多线程同时写同一目标时不会共用同一个 `.tmp`
    而互相截断中间态（固定名在并发下会把对方写了一半的文件 replace 上线）。"""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = f"{path}.tmp.{os.getpid()}.{threading.get_ident()}"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):  # replace 成功后不存在；异常时清理半成品
            try:
                os.remove(tmp)
            except OSError:
                pass


# ---------- JSON 镜像写：进程内锁 + 跨进程文件锁（评测 P0-4）----------
# 背景：mirror_json_upsert 是"读整文件 → 改一行 → 重写"的 read-modify-write；两个写者
# 同时读到同一份旧文件时后写覆盖先写，镜像静默丢行（生产实测 consults PG 17 / JSON 15、
# review_queue PG 1168 / JSON 1098）。修复：① 所有镜像读改写串行化（进程内 RLock +
# 跨进程 advisory 锁，后者 best-effort、超时只告警）；② 启动时按 PG 真源重建镜像
# （resync_clinical_mirrors）。镜像定位也修正为"仅供人工排查/灾难参考"，
# 权威恢复走 pg_dump——不再承诺逐行完整。
_MIRROR_LOCK = threading.RLock()
_MIRROR_FILE_LOCK_TIMEOUT_S = 2.0


def _try_lock_fd(fd: int) -> bool:
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


def _unlock_fd(fd: int) -> None:
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_UN)
    except OSError:
        pass


@contextlib.contextmanager
def _mirror_file_lock(path: str):
    """跨进程镜像锁（best-effort）：`<path>.lock` 旁路文件 + msvcrt/fcntl。

    拿不到锁（打开失败/等待超时）仍继续写——镜像不是业务主路径，可用性优先；
    同进程内互斥由 `_MIRROR_LOCK` 保证，本锁只用于多实例场景。"""
    fd = None
    try:
        fd = os.open(path + ".lock", os.O_CREAT | os.O_RDWR)
    except OSError as e:
        logger.debug("pg.mirror_lock_open_failed", file=os.path.basename(path),
                     error=str(e)[:80])
    acquired = False
    if fd is not None:
        deadline = time.monotonic() + _MIRROR_FILE_LOCK_TIMEOUT_S
        while True:
            if _try_lock_fd(fd):
                acquired = True
                break
            if time.monotonic() >= deadline:
                logger.warning("pg.mirror_lock_timeout", file=os.path.basename(path),
                               detail="跨进程镜像锁等待超时：继续写（可用性优先）")
                break
            time.sleep(0.01)
    try:
        yield
    finally:
        if fd is not None:
            if acquired:
                _unlock_fd(fd)
            try:
                os.close(fd)
            except OSError:
                pass


_repo_pg_stale: set[str] = set()  # 任务1：repo 层 PG 全量同步失败「域」集合（键=文件名）。
# 置位域的读回落 JSON（最新已落盘数据），直到该域下一次成功写清除——防「JSON 已写新、
# PG 仍旧」窗口内读回滚旧真源。此前是全局布尔：任何域（users/consults 等）写成功都会
# 洗白全部域的 stale，导致队列域 PG 同步失败后读切回 PG 旧真源，JSON 独有新提交
# （imaging ask 留痕）从审核中心"消失"——已改为按域隔离，互不影响。

_stale_warned: set[str] = set()  # 任务2：read_stale_fallback_json 进程内去重集合（键=文件名）。
# 非服务进程（eval/脚本/单测无 PG 池）此前每次读都 WARN——logs/app.log 7269 行刷屏根因。
# 现同一 file 仅首次 WARN（后续 DEBUG 静默）；该域 stale 被成功写清除后重置去重位，
# 再次进入 stale/无池状态时重新 WARN 一次（告警语义保留、刷屏消除）。


def _warn_stale_fallback_once(domain: str) -> None:
    """任务2：读回落 JSON 的去重告警——每 file 进程内首次 WARN，之后 DEBUG；
    stale 清除（成功写）时由 _json_then_pg 重置，重新具备告警能力。"""
    if domain in _stale_warned:
        logger.debug("pg.repo.read_stale_fallback_json", file=domain,
                     detail="同 file 已告警过，静默（dedup）")
        return
    _stale_warned.add(domain)
    logger.warning("pg.repo.read_stale_fallback_json", file=domain)


def _pg_or_json(load_fn, json_loader, path: str, default):
    """repo 读编排：PG 池可用且本域非 stale → 读 PG（非空即真源）；None/stale/异常/超时/
    空表 → JSON（现状行为；旧编排下 stale 时 JSON 恒为最新已落盘数据，读它保证不回滚）。

    pg_primary=true 时 stale 只作可观测标记（读仍继续尝试 PG，成功后自动清除），
    这样"运行中 PG 掉线"能被 stale 指标/告警看到，恢复后也会自动消失。
    任务2：PG_OFFLINE=on → 直接 JSON 纯静默（不告警）；无池/stale 读回落首次 WARN
    后同 file 去重静默（DEBUG）。"""
    domain = os.path.basename(path)
    if offline_mode():
        # 显式离线模式：JSON 是唯一真源，读它不是异常窗口，绝不告警
        if json_loader is not None:
            return json_loader()
        return _read_json(path, default)
    # pg_primary：JSON 只是镜像，不存在"JSON 领先 PG"的回滚风险——stale 此时是
    # **可观测标记**（PG 读失败过），读仍应继续尝试 PG，成功后自动清除。
    # 旧编排（pg_primary=false）：stale 是数据安全闸门，读必须回落 JSON 防回滚。
    primary = bool(getattr(settings, "pg_primary", True))
    if _pool and (primary or domain not in _repo_pg_stale):
        try:
            data = _run_pg(load_fn())
        except Exception as e:  # noqa: BLE001 —— PG 任何异常都降级 JSON，绝不影响调用方
            if primary:
                # 运行中 PG 掉线：不置位则 stale 指标与告警永远看不到（本轮演练实测）
                _repo_pg_stale.add(domain)
                _remember_stale_probe(domain, load_fn)  # 供抓取心跳重探（第 54 批）
            if isinstance(e, PGUnavailable):
                # 熔断期的快速失败是预期行为，circuit_open 已 WARN 一次；此处降为 DEBUG
                logger.debug("pg.repo.load_fallback_json", error=str(e)[:120],
                             file=os.path.basename(path))
            else:
                logger.warning("pg.repo.load_fallback_json", error=str(e)[:120],
                               file=os.path.basename(path))
        else:
            if primary:
                _repo_pg_stale.discard(domain)
                _stale_warned.discard(domain)
                _forget_stale_probe(domain)
            if data is not None:
                return data
    else:
        # 任务1：stale 读回落从 debug 升级为 warning——这是"数据暂存 JSON 未同步 PG"
        # 的异常窗口，运维必须可在日志中看到（写失败时已有 save_pg_failed 告警配对）。
        # 任务2：同 file 首次 WARN 后去重静默（DEBUG），stale 清除后重置。
        _warn_stale_fallback_once(domain)
    if json_loader is not None:
        return json_loader()
    return _read_json(path, default)


def _json_then_pg(save_fn, path: str, data) -> None:
    """repo 写编排（W4.1 真源翻转，按 settings.pg_primary 分派）：

    **pg_primary=true（新编排，PG 主写）**：PG 事务写先行——失败直接抛出（上层 500，
    不产生"PG 没写进但 JSON 写了"的分裂状态）；成功后 JSON 降为 best-effort 镜像快照
    （失败仅告警，不影响主路径；它是 PG 灾难时最近的恢复源）。
    **pg_primary=false（旧编排，JSON 主写）**：JSON 原子写先行，PG 可用再全量同步
    （best-effort，异常置本域 stale——读回落 JSON 最新数据；任务1 域级隔离）。

    共同语义：PG_OFFLINE=on 或无连接池 → JSON 是唯一持久层（单机/测试形态，两编排等价）；
    成功写清除本域 stale 并重置该 file 的告警去重位。"""
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        _write_json_atomic(path, data)
        if offline_mode():
            return
        # 无池但有 PG 配置：JSON 落盘 + 置 stale（读回落 JSON，与旧编排一致）
        _repo_pg_stale.add(domain)
        return
    # 读模块级 settings（与其它模块同一单例）：get_settings() 会新建 Settings 实例，
    # 既浪费（每次写都重解析环境）又会绕过测试/运行期对 settings 的 patch（实测回归）。
    if settings.pg_primary:
        try:
            _pg_write_call("repo", "full_sync", save_fn(data))
        except Exception as e:
            # 真源写失败：不写 JSON（避免分裂），向上抛出由调用方决定语义（HTTP 500）
            logger.warning("pg.repo.primary_write_failed", error=str(e)[:120],
                           file=os.path.basename(path))
            raise
        _repo_pg_stale.discard(domain)
        _stale_warned.discard(domain)
        try:
            # 镜像快照：仅供人工排查/灾难参考（不承诺逐行完整；权威恢复走 pg_dump）。
            # 完整对账由 resync_clinical_mirrors 在启动时按 PG 重建。
            _write_json_atomic(path, data)
        except Exception as e:  # noqa: BLE001
            logger.warning("pg.repo.mirror_json_failed", error=str(e)[:120],
                           file=os.path.basename(path))
        return
    # ---- 旧编排（JSON 主写，pg_primary=false 灰度回滚用）----
    _write_json_atomic(path, data)
    try:
        _pg_write_call("repo", "full_sync", save_fn(data))
        _repo_pg_stale.discard(domain)
        _stale_warned.discard(domain)  # 任务2：stale 已清除，下次再 stale 重新 WARN
    except Exception as e:  # noqa: BLE001
        _repo_pg_stale.add(domain)
        logger.warning("pg.repo.save_pg_failed", error=str(e)[:120],
                       file=os.path.basename(path))


# ---- users（PG 表补 dept 列；兼容旧 users.json 无 dept 字段） ----

_USER_UPSERT = """INSERT INTO users(username,role,dept,password_hash) VALUES($1,$2,$3,$4)
   ON CONFLICT(username) DO UPDATE SET role=EXCLUDED.role, dept=EXCLUDED.dept,
   password_hash=EXCLUDED.password_hash"""

_USER_IMPORT = """INSERT INTO users(username,role,dept,password_hash) VALUES($1,$2,$3,$4)
   ON CONFLICT(username) DO NOTHING"""


async def _pg_load_users() -> dict | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT username, role, dept, password_hash FROM users")
    if not rows:
        return None  # 空表 → 回落 JSON（迁移未跑/新库），保守可用性优先
    return {r["username"]: {"role": r["role"] or "", "dept": r["dept"] or "",
                            "password_hash": r["password_hash"] or ""} for r in rows}


async def _pg_save_users(users: dict) -> None:
    rows = [(u, rec.get("role", ""), rec.get("dept", ""), rec.get("password_hash", ""))
            for u, rec in (users or {}).items() if isinstance(rec, dict)]
    async with _pool.acquire() as c:
        async with c.transaction():
            if not rows:
                # 空集不清库：空用户集几乎必为异常状态（如 PG 抖动读回落空 JSON 后触发
                # 全量同步），DELETE 全表会把 PG 真实用户清光且不可恢复；跳过仅告警，
                # JSON 已如实落盘，下次任一非空全量同步自然补齐一致性。
                logger.warning("pg.save_users.skip_empty_wipe")
                return
            # W4.1 事故护栏（2026-10-30）：全量同步的差集 DELETE 在"新集合异常骤缩"时
            # 等同清空真源（实测：集成测试误连生产库，17 行账号被清成 1 行 → 全站 401）。
            # 旧集合 ≥5 行且新集合 <50% → 拒绝执行（真要清空属运维操作，走显式 SQL）。
            # 空集跳过（下方既有语义）是本护栏的特例（0% < 50%）。
            old_rows = await c.fetch("SELECT count(*) AS n FROM users")
            old_n = old_rows[0]["n"] if old_rows else 0
            if old_n >= 5 and len(rows) < old_n * 0.5:
                logger.error("pg.save_users.destructive_sync_rejected",
                             old_count=old_n, new_count=len(rows))
                raise RuntimeError(
                    f"users 全量同步被拒绝：{old_n} 行 → {len(rows)} 行属异常骤缩"
                    f"（>50% 删减）。真要清空请走显式运维 SQL，不走应用同步路径。")
            await c.execute("DELETE FROM users WHERE NOT (username = ANY($1))",
                            [r[0] for r in rows])
            await c.executemany(_USER_UPSERT, rows)


def load_users(path: str, json_loader=None) -> dict:
    """用户表读：PG 可用→PG 真源；不可用/异常/空表→JSON。json_loader=调用方自有 JSON 读语义。"""
    return _pg_or_json(_pg_load_users, json_loader, path, {})


def save_users(users: dict, path: str) -> None:
    """用户表写：JSON 原子写兜底 + PG 全量同步（含 dept；不在表中的 username 一并清除）。"""
    _json_then_pg(_pg_save_users, path, users)


# ---- W4.1 行级增量 API（users 域）：跨实例严格不丢更新的正解 ----
# 全量同步 save_users 的语义是"整域 DELETE 差集 + UPSERT"——多写者各持局部视图时
# 天然互相覆盖（实测 17 行账号被清成 1 行）。行级 API 只触达目标行，PG 行级锁
# 保证并发 UPSERT 全部生效；镜像快照由调用方按需刷新（覆盖无害，PG 是真源）。

async def _pg_upsert_user_row(username: str, role: str, dept: str, password_hash: str) -> None:
    async with _pool.acquire() as c:
        await c.execute(_USER_UPSERT, username, role, dept, password_hash)


def upsert_user_row(username: str, role: str, dept: str, password_hash: str) -> None:
    """单行 UPSERT（行级锁，并发多写者互不覆盖）。pg_primary=true：PG 失败抛出防分裂；
    无池/离线：调用方须自行保证 JSON 真源（本函数只管 PG 行，不落盘）。"""
    if offline_mode() or not _pool:
        raise RuntimeError("upsert_user_row 需要 PG 池（离线/单机形态请走 save_users 全量同步）")
    _pg_write_call("users", "upsert_row",
                   _pg_upsert_user_row(username, role, dept, password_hash))


def delete_user_row(username: str) -> int:
    """单行 DELETE（返回删除行数）。池/离线语义同 upsert_user_row。"""
    if offline_mode() or not _pool:
        raise RuntimeError("delete_user_row 需要 PG 池（离线/单机形态请走 save_users 全量同步）")

    async def _impl():
        async with _pool.acquire() as c:
            res = await c.execute("DELETE FROM users WHERE username=$1", username)
            return int(res.split()[-1]) if res else 0

    return _pg_write_call("users", "delete_row", _impl())


def has_pool() -> bool:
    """PG 池是否可用（W4.1 行级 API 的调用方判据：有池走行级，无池走 JSON 全量）。"""
    return bool(_pool) and not offline_mode()


def mirror_json_file(path: str, data) -> None:
    """JSON 镜像快照（原子写，best-effort）：PG 主写成功后由调用方刷新。

    定位（评测 P0-4 修正）：镜像仅供人工排查/灾难参考，**不承诺逐行完整**——权威
    恢复走 pg_dump；完整对账由 `resync_clinical_mirrors` 在启动时按 PG 重建。
    写失败/锁超时只告警，绝不让已成功的 PG 写变成业务 500。"""
    try:
        with _MIRROR_LOCK, _mirror_file_lock(path):
            _write_json_atomic(path, data)
    except Exception as e:  # noqa: BLE001 —— 镜像失败不影响 PG 真源
        logger.warning("pg.mirror_json_file_failed", file=os.path.basename(path),
                       error=str(e)[:120])


def mirror_json_upsert(path: str, item: dict, key: str = "id") -> None:
    """JSON 镜像**就地更新**（第 87 批，PG 主模式的单行写配套）：

    读现有镜像 → 同 key 替换（不存在则追加）→ 原子写。与 `mirror_json_file(全量覆盖)`
    相比，调用方无需为了刷新镜像而先读全表；镜像本身只是灾难恢复参考（PG 仍是真源），
    故失败仅告警、绝不冒泡影响已成功的 PG 写。

    评测 P0-4：读-改-写必须串行化——进程内 `_MIRROR_LOCK` + 跨进程 `<path>.lock`。
    此前无锁并发时两个写者各持旧快照、后写覆盖先写，镜像静默丢行（生产实测
    consults PG 17 / JSON 15）。"""
    try:
        with _MIRROR_LOCK, _mirror_file_lock(path):
            data = _read_json(path, [])
            if not isinstance(data, list):
                data = []
            kid = item.get(key)
            for idx, old in enumerate(data):
                if isinstance(old, dict) and old.get(key) == kid:
                    data[idx] = item
                    break
            else:
                data.append(item)
            _write_json_atomic(path, data)
    except Exception as e:  # noqa: BLE001 —— 镜像失败不影响 PG 真源
        logger.warning("pg.mirror_json_upsert_failed", file=os.path.basename(path),
                       error=str(e)[:120])


# ---- departments（id=顺序号保序，还原 {"list":[...]} 形态） ----

async def _pg_load_departments() -> dict | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT name FROM departments ORDER BY id")
    if not rows:
        return None
    return {"list": [r["name"] for r in rows]}


async def _pg_save_departments(d: dict) -> None:
    lst = [str(n) for n in ((d or {}).get("list") or [])]
    async with _pool.acquire() as c:
        async with c.transaction():
            await c.execute("DELETE FROM departments")
            if lst:
                await c.executemany("INSERT INTO departments(id,name) VALUES($1,$2)",
                                    list(enumerate(lst, start=1)))


def load_departments(path: str, json_loader=None) -> dict:
    return _pg_or_json(_pg_load_departments, json_loader, path, {"list": []})


def save_departments(d: dict, path: str) -> None:
    _json_then_pg(_pg_save_departments, path, d)


# ---- review_queue（含 images jsonb；ts+id 排序还原追加顺序） ----

def _review_item_from_row(r) -> dict:
    """review_queue 行 → 队列条目（images/meta/sources jsonb 解码，兼容驱动直返 dict）。"""
    d = {k: r[k] for k in _REVIEW_COLS}
    img = r["images"]
    d["images"] = json.loads(img) if img else None
    meta = d.get("meta")
    d["meta"] = json.loads(meta) if isinstance(meta, str) and meta else meta
    src = d.get("sources")
    d["sources"] = json.loads(src) if isinstance(src, str) and src else src
    return d


async def _pg_load_queue() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch(f"""SELECT {','.join(_REVIEW_COLS)}, images
            FROM review_queue ORDER BY ts, id""")  # noqa: S608 (固定列名)
    if not rows:
        return None
    return [_review_item_from_row(r) for r in rows]


async def _pg_save_queue(items: list) -> None:
    rows = [_review_row(i) for i in (items or []) if isinstance(i, dict)]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM review_queue WHERE NOT (id = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM review_queue")
            if rows:
                await c.executemany(_REVIEW_UPSERT, rows)


def load_queue(path: str, json_loader=None) -> list:
    """审核队列读：PG 可用→PG 真源；不可用/异常/空表→JSON（PermissionError 重试语义
    保留在调用方 json_loader 内，锁语义不变）。"""
    return _pg_or_json(_pg_load_queue, json_loader, path, [])


def save_queue(items: list, path: str) -> None:
    _json_then_pg(_pg_save_queue, path, items)


# ---- 第 82 批：待核对列表分页/筛选/计数下推 PG ----
# 为什么：/review/pending 是唯一仍"读全量队列再过滤切片"的高频端点——侧栏角标每 60s
# 调一次 limit=0 只为拿一个数字，实测 600+ 条队列要整表解码。这里把 status/角色收窄/
# 关键字/分页/计数全部下推 PG，内存 pending() 仅作 JSON 兜底。

async def _pg_review_pending_impl(exclude_drug: bool, keyword: str,
                                  limit: int | None, offset: int) -> dict | None:
    """待核对分页：返回 {items,total}；空表 → None（回落 JSON 真源）。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM review_queue"):
            return None
        conds = ["status = 'pending'"]
        params: list = []
        if exclude_drug:  # 问题4：qc 不见 drug 项（与内存过滤语义一致；agent 为 NULL 时保留）
            conds.append("agent IS DISTINCT FROM 'drug'")
        kw = (keyword or "").strip().lower()
        if kw:
            # 与 _match_query 完全同构（第 97 批改为 LIKE，可走 trgm GIN；% _ ! 按字面量转义）
            params.append(_like_contains(kw))
            conds.append(_search_cond(len(params)))
        where = " WHERE " + " AND ".join(conds)
        total = int(await c.fetchval(  # noqa: S608 —— 片段均为固定常量
            "SELECT count(*) FROM review_queue" + where, *params) or 0)
        sql = (f"SELECT {','.join(_REVIEW_COLS)}, images FROM review_queue" + where
               + " ORDER BY ts, id")  # noqa: S608 —— 与 _pg_load_queue 同序（队列先进先出）
        if limit is None:
            rows = await c.fetch(sql, *params)
        else:
            n = len(params)
            rows = await c.fetch(sql + f" LIMIT ${n + 1} OFFSET ${n + 2}",  # noqa: S608
                                 *params, max(0, int(limit)), max(0, int(offset)))
        return {"items": [_review_item_from_row(r) for r in rows], "total": total}


def review_pending_page(exclude_drug: bool = False, keyword: str = "",
                        limit: int | None = None, offset: int = 0) -> dict | None:
    """待核对分页同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "queue.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("review_queue", "pending_page",
                        _pg_review_pending_impl(bool(exclude_drug), keyword, limit,
                                                max(0, int(offset))))
    except Exception as e:  # noqa: BLE001 —— 分页失败回落内存，绝不影响待核列表
        logger.warning("pg.review_pending_page_failed", error=str(e)[:120])
        return None


# ---- 第 83 批：历史列表分页/筛选/计数下推 PG ----
# 语义必须与内存实现逐条一致：
#   doctor/pharmacist → list_involved(me) = (submitted_by=me OR reviewed_by=me) 最新 200 条；
#   qc/admin          → list_all(limit=50) = 全表最新 50 条；
#   **先截断再过滤**（hide_pending/hide_drug/q），total=过滤后条数，最后才切片。
# 因此分两步：先取"基础集合 id 上限"，再在该集合内过滤/计数/分页（避免把过滤提前到
# 截断之前，导致"能看到 200 条之外的历史"这一行为漂移）。


async def _pg_review_history_impl(involves: str | None, base_limit: int,
                                  hide_pending: bool, hide_drug: bool, keyword: str,
                                  limit: int | None, offset: int) -> dict | None:
    """历史列表：返回 {items,total}；空表 → None（回落 JSON 真源）。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM review_queue"):
            return None
        cap = max(1, int(base_limit))
        if involves:
            id_rows = await c.fetch(
                "SELECT id FROM review_queue WHERE (submitted_by = $1 OR reviewed_by = $1) "
                "ORDER BY ts DESC, id DESC LIMIT $2", involves, cap)
        else:
            id_rows = await c.fetch(
                "SELECT id FROM review_queue ORDER BY ts DESC, id DESC LIMIT $1", cap)
        ids = [r["id"] for r in id_rows]
        if not ids:  # 本人无参与记录（表非空）→ 空页，但仍由 PG 回答
            return {"items": [], "total": 0}
        conds = ["id = ANY($1)"]
        params: list = [ids]
        if hide_pending:
            conds.append("status <> 'pending'")
        if hide_drug:
            conds.append("agent IS DISTINCT FROM 'drug'")
        kw = (keyword or "").strip().lower()
        if kw:
            params.append(_like_contains(kw))
            conds.append(_search_cond(len(params)))
        where = " WHERE " + " AND ".join(conds)
        total = int(await c.fetchval(  # noqa: S608 —— 片段均为固定常量
            "SELECT count(*) FROM review_queue" + where, *params) or 0)
        sql = (f"SELECT {','.join(_REVIEW_COLS)}, images FROM review_queue" + where
               + " ORDER BY ts DESC, id DESC")  # noqa: S608 —— 与内存 [::-1] 同序
        if limit is None:
            rows = await c.fetch(sql, *params)
        else:
            n = len(params)
            rows = await c.fetch(sql + f" LIMIT ${n + 1} OFFSET ${n + 2}",  # noqa: S608
                                 *params, max(0, int(limit)), max(0, int(offset)))
        return {"items": [_review_item_from_row(r) for r in rows], "total": total}


def review_history_page(involves: str | None = None, base_limit: int = 50,
                        hide_pending: bool = False, hide_drug: bool = False,
                        keyword: str = "", limit: int | None = None,
                        offset: int = 0) -> dict | None:
    """历史列表分页同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "queue.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("review_queue", "history_page",
                        _pg_review_history_impl(involves, base_limit, bool(hide_pending),
                                                bool(hide_drug), keyword, limit,
                                                max(0, int(offset))))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，历史列表照常可用
        logger.warning("pg.review_history_page_failed", error=str(e)[:120])
        return None


# ---- 第 84 批：单条复核详情按主键直查（替代"读全表再线性查找"） ----

async def _pg_get_review_row_impl(rid: str) -> tuple[bool, dict | None]:
    """(PG 是否给出答案, 条目)。空表 → (False, None)：JSON 才是真源，调用方回退线性查找。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM review_queue"):
            return (False, None)
        rows = await c.fetch(  # noqa: S608 —— 固定列名
            f"SELECT {','.join(_REVIEW_COLS)}, images FROM review_queue WHERE id = $1", rid)
    if not rows:
        return (True, None)  # 表非空且无此 id → 确定不存在（不再回落 JSON）
    return (True, _review_item_from_row(rows[0]))


def get_review_row(rid: str) -> tuple[bool, dict | None]:
    """单条详情同步入口：无池/离线/旧编排 stale/异常 → (False, None)（调用方走 JSON 现状）。"""
    if not rid or offline_mode() or not _pool:
        return (False, None)
    if not bool(getattr(settings, "pg_primary", True)) and "queue.json" in _repo_pg_stale:
        return (False, None)
    try:
        return _pg_call("review_queue", "get_row", _pg_get_review_row_impl(rid))
    except Exception as e:  # noqa: BLE001 —— 单条查询失败回落 JSON，不影响详情页
        logger.warning("pg.get_review_row_failed", error=str(e)[:120])
        return (False, None)


# ---- 第 86 批：个人视角列表（我的待确认 / 我的质控驳回 / 药物驳回）按人过滤下推 PG ----
# 这三个列表原本都是 `_load()[::-1]` 全表扫描后按 submitted_by 过滤；医生端每次进页面
# 都会拉一遍。where 片段由域层以固定常量传入，值走 $n 占位符；返回已剥离 images（与内存同纪律）。

async def _pg_review_my_rows_impl(where: str, params: list) -> list | None:
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM review_queue"):
            return None  # 空表 → JSON 才是真源
        rows = await c.fetch(  # noqa: S608 —— where 为域层固定片段
            f"SELECT {','.join(_REVIEW_COLS)}, images FROM review_queue"
            + " WHERE " + where + " ORDER BY ts DESC, id DESC", *params)
    return [{k: v for k, v in _review_item_from_row(r).items() if k != "images"}
            for r in rows]


def review_my_rows(where: str, params: tuple = ()) -> list | None:
    """个人列表同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存实现）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "queue.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("review_queue", "my_rows",
                        _pg_review_my_rows_impl(where, list(params or ())))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，个人列表照常可用
        logger.warning("pg.review_my_rows_failed", error=str(e)[:120])
        return None


# ---- 第 91 批：复核状态计数（概览面板用，替代「读全表再计数」） ----

async def _pg_review_status_counts_impl() -> dict | None:
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM review_queue"):
            return None  # 空表 → JSON 才是真源
        rows = await c.fetch(
            "SELECT status, count(*) AS n FROM review_queue GROUP BY status")
    counts = {str(r["status"] or "unknown"): int(r["n"]) for r in rows}
    return {"pending": counts.get("pending", 0),
            "resolved": counts.get("approved", 0) + counts.get("rejected", 0),
            "counts": counts}


def review_status_counts() -> dict | None:
    """复核状态计数：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存计数）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "queue.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("review_queue", "status_counts", _pg_review_status_counts_impl())
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，概览照常可用
        logger.warning("pg.review_status_counts_failed", error=str(e)[:120])
        return None


# ---- llm_providers（kind/pid/data/active；data 内嵌 _seq 还原 providers 顺序） ----

_LLM_IMPORT = """INSERT INTO llm_providers(kind,pid,data,active) VALUES($1,$2,$3::jsonb,$4)
   ON CONFLICT(kind,pid) DO NOTHING"""


def _llm_rows_from_cfg(cfg: dict) -> list[tuple]:
    """配置 → INSERT 参数行（_seq 内嵌进 data jsonb 以还原 providers 展示顺序）。"""
    rows = []
    for kind in ("chat", "vision"):
        block = (cfg or {}).get(kind) or {}
        active = block.get("active")
        for i, p in enumerate(block.get("providers") or []):
            if isinstance(p, dict) and p.get("id"):
                rows.append((kind, str(p["id"]),
                             json.dumps({**p, "_seq": i}, ensure_ascii=False),
                             active == p.get("id")))
    return rows


async def _pg_load_llm_providers() -> dict | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT kind, data, active FROM llm_providers")
    if not rows:
        return None
    out = {k: {"active": None, "providers": []} for k in ("chat", "vision")}
    for r in rows:
        kind = r["kind"]
        if kind not in out:
            continue
        try:
            data = json.loads(r["data"])
        except Exception:  # noqa: BLE001 —— 单行损坏跳过，不拖垮整个配置
            continue
        if not isinstance(data, dict):
            continue
        seq = data.pop("_seq", None)  # 顺序号仅存储用，运行时配置中剥离
        out[kind]["providers"].append((seq if isinstance(seq, int) else 1 << 30, data))
        if r["active"]:
            out[kind]["active"] = data.get("id")
    for k in out:
        out[k]["providers"] = [p for _, p in sorted(out[k]["providers"], key=lambda t: t[0])]
    return out


async def _pg_save_llm_providers(cfg: dict) -> None:
    rows = _llm_rows_from_cfg(cfg)
    async with _pool.acquire() as c:
        async with c.transaction():
            await c.execute("DELETE FROM llm_providers")
            if rows:
                await c.executemany(
                    "INSERT INTO llm_providers(kind,pid,data,active) VALUES($1,$2,$3::jsonb,$4)",
                    rows)


def load_llm_providers(path: str, json_loader=None) -> dict:
    return _pg_or_json(_pg_load_llm_providers, json_loader, path,
                       {"chat": {"active": None, "providers": []},
                        "vision": {"active": None, "providers": []}})


def save_llm_providers(cfg: dict, path: str) -> None:
    _json_then_pg(_pg_save_llm_providers, path, cfg)


# ---- consults（跨科室会诊；data jsonb 全量留痕，created_at 内嵌 data 中，读时按其排序还原追加顺序） ----

_CONSULT_UPSERT = """INSERT INTO consults(id,data,initiator,status) VALUES($1,$2::jsonb,$3,$4)
   ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data, initiator=EXCLUDED.initiator,
   status=EXCLUDED.status, updated_at=now()"""

# ---- 第 124 批：会诊意见子表（派生索引） ----
# jsonb 的 `opinions` 仍是唯一真源；子表只用于"待本科室填意见"这类**取反**查询
# （jsonb 取反走不了 GIN，见 §127）。双写 + 回填先落地，读路径暂不切换。
_CONSULT_OPINIONS_DDL = """CREATE TABLE IF NOT EXISTS consult_opinions(
    consult_id text NOT NULL, dept text NOT NULL, doctor text NOT NULL,
    content text NOT NULL DEFAULT '', ts timestamptz,
    primary key (consult_id, doctor))"""

# 评测 A4：派生表补外键（jsonb 仍是唯一真源，子表可随时重建）。
# 幂等 DO 块：首次执行先清孤儿行（主单已被删除、子表残留），再加 FK；
# ON DELETE CASCADE 从根上消除"删主单留孤儿"，避免待填意见统计被污染。
# 任一步失败只告警（旧库权限不足等），不影响启动与主数据。
_CONSULT_OPINIONS_FK_DDL = """DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_consult_opinions_consult') THEN
        DELETE FROM consult_opinions o
         WHERE NOT EXISTS (SELECT 1 FROM consults c WHERE c.id = o.consult_id);
        ALTER TABLE consult_opinions
            ADD CONSTRAINT fk_consult_opinions_consult
            FOREIGN KEY (consult_id) REFERENCES consults(id) ON DELETE CASCADE;
    END IF;
END $$"""

_CONSULT_OPINIONS_UPSERT = """INSERT INTO consult_opinions(consult_id,dept,doctor,content,ts)
    VALUES($1,$2,$3,$4,$5)
    ON CONFLICT(consult_id,doctor) DO UPDATE SET dept=EXCLUDED.dept,
    content=EXCLUDED.content, ts=EXCLUDED.ts"""

# 评测 A4：全量重建改走 Python 口径（见 _rebuild_consult_opinions）。原 SQL 版用
# `(o->>'ts')::timestamptz`，正则只校验前缀形状不校验值域：遇到
# `2026-13-45T99:99:99` 抛 DatetimeFieldOverflowError → 整段重建回滚（主单却写成功）。
# ts 只用于展示排序、不参与业务判定，非法值置 NULL 即可，不能因此丢派生行。


def _opinion_ts(v) -> datetime | None:
    """意见时间戳 → datetime（非法/缺失 → None，时间只用于展示排序，不参与业务判定）。"""
    s = str(v or "").strip()
    if not s:
        return None
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        return None


def consult_opinion_rows(item: dict) -> list[tuple]:
    """会诊单 → 子表行（双写与回填脚本共用的**唯一**映射口径）。

    跳过没有 doctor 的意见（子表主键是 (consult_id, doctor)，空 doctor 会互相覆盖）。
    """
    cid = item.get("id")
    if not cid:
        return []
    out = []
    for o in (item.get("opinions") or []):
        if not isinstance(o, dict) or not str(o.get("doctor") or "").strip():
            continue
        out.append((str(cid), str(o.get("dept") or ""), str(o.get("doctor") or ""),
                    str(o.get("content") or ""), _opinion_ts(o.get("ts"))))
    return out


async def _sync_consult_opinions(c, item: dict) -> None:
    """单条会诊的意见子表同步（先删后插，包在事务/保存点里）。

    派生索引失败**绝不阻断主单写入**：子表丢掉的行可由 `scripts/backfill_consult_opinions.py`
    从 jsonb 重建；因此这里只告警。
    """
    cid = item.get("id")
    if not cid:
        return
    rows = consult_opinion_rows(item)
    try:
        async with c.transaction():
            await c.execute("DELETE FROM consult_opinions WHERE consult_id = $1", str(cid))
            if rows:
                await c.executemany(_CONSULT_OPINIONS_UPSERT, rows)
    except Exception as e:  # noqa: BLE001 —— 派生索引失败不回滚主数据
        logger.warning("pg.consult_opinions_sync_failed", cid=str(cid)[:64],
                       error=str(e)[:120])


async def _rebuild_consult_opinions(c, items: list) -> None:
    """按 jsonb 真源整体重建意见子表（全量同步路径；**Python 唯一映射口径**）。

    评测 A4：替换原 SQL 全量重建——SQL 侧 `(o->>'ts')::timestamptz` 遇到值域非法
    时间戳会抛 DatetimeFieldOverflowError 并回滚整段重建。这里复用
    `consult_opinion_rows` 的安全解析（非法/缺失 ts → NULL，只影响展示排序）。
    """
    rows: list[tuple] = []
    for it in (items or []):
        if isinstance(it, dict):
            rows.extend(consult_opinion_rows(it))
    await c.execute("DELETE FROM consult_opinions")
    if rows:
        await c.executemany(_CONSULT_OPINIONS_UPSERT, rows)


async def _pg_load_consults() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM consults ORDER BY data->>'created_at', id")
    if not rows:
        return None
    return [d for d in (_json_data_row(r) for r in rows) if d is not None]


async def _pg_save_consults(items: list) -> None:
    rows = [(i.get("id"), json.dumps(i, ensure_ascii=False), i.get("initiator", ""),
             i.get("status", "open"))
            for i in (items or []) if isinstance(i, dict) and i.get("id")]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM consults WHERE NOT (id = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM consults")
            if rows:
                await c.executemany(_CONSULT_UPSERT, rows)
            # 第 124 批：派生索引随全量同步整体重建（事务内，失败只告警不影响主数据）
            # 评测 A4：改走 Python 口径（非法 ts 不再炸掉整段重建）
            try:
                async with c.transaction():
                    await _rebuild_consult_opinions(c, items)
            except Exception as e:  # noqa: BLE001 —— 派生索引可回填重建
                logger.warning("pg.consult_opinions_resync_failed", error=str(e)[:120])


def load_consults(path: str, json_loader=None) -> list:
    """会诊单读：PG 可用→PG 真源；不可用/异常/空表→JSON（调用方 json_loader 自有重试语义）。"""
    return _pg_or_json(_pg_load_consults, json_loader, path, [])


def save_consults(items: list, path: str) -> None:
    _json_then_pg(_pg_save_consults, path, items)


def upsert_consult_row(item: dict) -> None:
    """W4.1 单行 UPSERT（consults）：会诊单为单据级变更（create/opinion/close 各改一张单），
    行级写根除多实例全量同步互覆。无池/离线/失败抛 RuntimeError（同 upsert_review_row 纪律：
    调用方按 settings.pg_primary 决定回落 save_consults 全量）。"""
    if offline_mode() or not _pool:
        raise RuntimeError("upsert_consult_row 需要 PG 池（离线/单机形态请走 save_consults 全量同步）")

    async def _impl():
        async with _pool.acquire() as c:
            await c.execute(_CONSULT_UPSERT, item["id"], json.dumps(item, ensure_ascii=False),
                            item.get("initiator", ""), item.get("status", "open"))
            # 第 124 批：意见子表随单行写同步（派生索引，失败不抛）
            await _sync_consult_opinions(c, item)

    _pg_write_call("consults", "upsert_row", _impl())


# ---- 第 89 批：会诊列表（收件箱/我发起/我参与）下推 PG ----
# 三个列表原本都是 `_load()[::-1]` 全表扫描后内存过滤；收件箱还被角标每 60s 拉一次。
# where 片段由 consults 域层以固定常量传入，值走 $n（科室/用户名用 jsonb 数组包含匹配）。
_CONSULT_WHERE_SELECT = "SELECT data FROM consults WHERE "
# 新→旧：created_at 缺失的行排最后（NULLS LAST），同刻按 id 倒序保证分页稳定
_CONSULT_ORDER = " ORDER BY data->>'created_at' DESC NULLS LAST, id DESC"


async def _pg_consult_list_impl(where: str, params: list) -> list | None:
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM consults"):
            return None  # 空表 → JSON 才是真源
        rows = await c.fetch(  # noqa: S608 —— where 为域层固定片段
            _CONSULT_WHERE_SELECT + where + _CONSULT_ORDER, *params)
    return [d for d in (_json_data_row(r) for r in rows) if d is not None]


def consult_list(where: str, params: tuple = ()) -> list | None:
    """会诊列表同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "consults.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("consults", "list", _pg_consult_list_impl(where, list(params or ())))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，会诊列表照常可用
        logger.warning("pg.consult_list_failed", error=str(e)[:120])
        return None


# ---- 第 121 批：会诊列表分页下推（LIMIT/OFFSET + 同口径 COUNT） ----
# 第 119 批把「我的会诊」在应用层切片：PG 仍返回过滤后的**整表**，`total` 用 len 算。
# 单页 10 条时前端无感，但会诊量上千后每次翻页都要搬运全量 JSONB。这里把切片与计数
# 一起下推；`limit=None` 保留整表语义（旧调用方不受影响，只是多一次 COUNT）。

async def _pg_consult_page_impl(where: str, params: list, limit: int | None,
                                offset: int) -> dict | None:
    """{"items": [...], "total": n}；空表 → None（调用方回落 JSON）。

    COUNT 与取数用同一个 where / 同一组参数，保证"共 N 条"与页内数据同口径（第 119 批
    的 total 语义不变，只是改为库内统计）。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM consults"):
            return None  # 空表 → JSON 才是真源
        total = int(await c.fetchval(  # noqa: S608 —— where 为域层固定片段
            "SELECT count(*) FROM consults WHERE " + where, *params) or 0)
        sql = _CONSULT_WHERE_SELECT + where + _CONSULT_ORDER  # noqa: S608
        if limit is None:
            rows = await c.fetch(sql, *params)
        else:
            n = len(params)
            rows = await c.fetch(  # noqa: S608 —— 参数化 LIMIT/OFFSET，非拼接
                sql + f" LIMIT ${n + 1} OFFSET ${n + 2}",
                *params, max(0, int(limit)), max(0, int(offset)))
    items = [d for d in (_json_data_row(r) for r in rows) if d is not None]
    return {"items": items, "total": total}


def consult_page(where: str, params: tuple = (), limit: int | None = None,
                 offset: int = 0) -> dict | None:
    """会诊分页同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "consults.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("consults", "page",
                        _pg_consult_page_impl(where, list(params or ()), limit, offset))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，会诊分页照常可用
        logger.warning("pg.consult_page_failed", error=str(e)[:120])
        return None


# 第 122/123 批：收件箱角标"待本科室填意见"的库内计数。
# 语义与前端原 `opinions.some(dept)` 取反一致：该会诊的 opinions 里**没有**本科室的意见。
# 第 123 批：① 与 total 合并成一次聚合（`count(*) FILTER (...)`），少一次往返；
# ② 用等价的 jsonb 包含判断取反代替逐元素 NOT EXISTS（真机 10 个科室逐一对账一致）。
# 注意（实测结论）：`NOT (@>)` **无法走 GIN**——GIN 不支持取反，我们试过强制
# enable_seqscan=off 后代价被罚到 1e10 仍回退顺扫。因此这里省的是"行内函数调用 +
# 一次数据库往返"，不是索引；上量后若要索引支撑，需要把 opinions 规范化为子表。
_CONSULT_PENDING_FILTER = (
    "count(*) FILTER (WHERE NOT (coalesce(data->'opinions', '[]'::jsonb)"
    " @> ${n}::jsonb))"
)

# 第 125 批：读派生子表（`settings.consult_opinions_pending`）时的同口径计数。
# `NOT EXISTS` 是关系表上的反连接，可以走 `ix_consult_opinions_dept`——这是 §127 证明
# jsonb 取反做不到的事；子表由双写维护，切换前须 `backfill --check` 无差异。
_CONSULT_PENDING_FILTER_SUBTABLE = (
    "count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM consult_opinions o"
    " WHERE o.consult_id = consults.id AND o.dept = ${n}))"
)


async def _pg_consult_inbox_page_impl(where: str, params: list, limit: int | None,
                                      offset: int, dept: str,
                                      use_subtable: bool = False) -> dict | None:
    """收件箱分页：{"items", "total", "pending"}；空表 → None。

    `pending` 是**整个过滤结果**里"本科室还没填意见"的条数（不是当前页），与第 77 批
    的应用层口径一致——角标要的是"还有几单等我"，翻页不该改变它。
    第 125 批：`use_subtable=True` 时改读 `consult_opinions`（可走索引），否则读 jsonb。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM consults"):
            return None
        n = len(params)
        # total 与 pending 一次聚合出（同一条语句、同一份扫描结果）
        if use_subtable:
            filter_sql = _CONSULT_PENDING_FILTER_SUBTABLE.format(n=n + 1)
            probe = dept
        else:
            filter_sql = _CONSULT_PENDING_FILTER.format(n=n + 1)
            probe = json.dumps([{"dept": dept}], ensure_ascii=False)
        agg = await c.fetchrow(  # noqa: S608 —— where 为域层固定片段
            "SELECT count(*) AS total, " + filter_sql
            + " AS pending FROM consults WHERE " + where, *params, probe)
        total = int((agg or {}).get("total") or 0)
        pending = int((agg or {}).get("pending") or 0)
        sql = _CONSULT_WHERE_SELECT + where + _CONSULT_ORDER  # noqa: S608
        if limit is None:
            rows = await c.fetch(sql, *params)
        else:
            # 占位符按**本语句**编号（与上面聚合语句各自独立，不接着往后排）
            rows = await c.fetch(  # noqa: S608 —— 参数化 LIMIT/OFFSET
                sql + f" LIMIT ${n + 1} OFFSET ${n + 2}",
                *params, max(0, int(limit)), max(0, int(offset)))
    items = [d for d in (_json_data_row(r) for r in rows) if d is not None]
    return {"items": items, "total": total, "pending": pending}


def consult_inbox_page(where: str, params: tuple = (), limit: int | None = None,
                       offset: int = 0, dept: str = "",
                       use_subtable: bool = False) -> dict | None:
    """收件箱分页同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。

    第 125 批：`use_subtable` 由域层按 `settings.consult_opinions_pending` 传入。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "consults.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("consults", "inbox_page",
                        _pg_consult_inbox_page_impl(where, list(params or ()), limit,
                                                    offset, dept, use_subtable))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，收件箱照常可用
        logger.warning("pg.consult_inbox_page_failed", error=str(e)[:120])
        return None


# ---- 第 85 批：单据类（consults/prescriptions）按主键直查 ----
# 与第 84 批复核详情同一模式：三态 (found, item)——(True, dict) 命中 / (True, None) 确定不存在 /
# (False, None) PG 无法回答（调用方回落 JSON 线性查找）。表名只接受本模块内部常量。

def _json_data_row(r) -> dict | None:
    """data jsonb 行 → dict（驱动可能直返 str/dict；单行损坏返回 None）。"""
    try:
        d = json.loads(r["data"]) if isinstance(r["data"], str) else r["data"]
    except Exception:  # noqa: BLE001 —— 单行损坏不拖垮调用方
        return None
    return d if isinstance(d, dict) else None


async def _pg_get_data_row_impl(table: str, rid: str) -> tuple[bool, dict | None]:
    async with _pool.acquire() as c:
        if not await c.fetchval(f"SELECT count(*) FROM {table}"):  # noqa: S608 (内部常量表名)
            return (False, None)  # 空表 → JSON 才是真源
        rows = await c.fetch(  # noqa: S608
            f"SELECT data FROM {table} WHERE id = $1", rid)
    if not rows:
        return (True, None)  # 表非空且无此 id → 确定不存在（不再回落 JSON）
    return (True, _json_data_row(rows[0]))


def _get_data_row(table: str, rid: str) -> tuple[bool, dict | None]:
    if not rid or offline_mode() or not _pool:
        return (False, None)
    if (not bool(getattr(settings, "pg_primary", True))
            and f"{table}.json" in _repo_pg_stale):
        return (False, None)
    try:
        return _pg_call(table, "get_row", _pg_get_data_row_impl(table, rid))
    except Exception as e:  # noqa: BLE001 —— 单条查询失败回落 JSON，不影响详情页
        logger.warning("pg.get_data_row_failed", table=table, error=str(e)[:120])
        return (False, None)


def get_consult_row(cid: str) -> tuple[bool, dict | None]:
    """会诊单按主键直查（三态语义见 _get_data_row 注释）。"""
    return _get_data_row("consults", cid)


def get_prescription_row(rid: str) -> tuple[bool, dict | None]:
    """处方按主键直查（三态语义见 _get_data_row 注释）。"""
    return _get_data_row("prescriptions", rid)


# ---- 阶段2.1：智能开药处方（data jsonb 全量留痕，created_at 内嵌 data 中，读时按其排序还原追加顺序） ----

_RX_UPSERT = """INSERT INTO prescriptions(id,data,doctor,status) VALUES($1,$2::jsonb,$3,$4)
   ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data, doctor=EXCLUDED.doctor,
   status=EXCLUDED.status, updated_at=now()"""


def _rx_rows_to_items(rows) -> list[dict]:
    """prescriptions 行 → 处方 dict（单行 jsonb 损坏跳过，不拖垮列表/整页）。"""
    return [d for d in (_json_data_row(r) for r in rows) if d is not None]


async def _pg_load_prescriptions() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM prescriptions ORDER BY data->>'created_at', id")
    if not rows:
        return None
    return _rx_rows_to_items(rows)


async def _pg_save_prescriptions(items: list) -> None:
    rows = [(i.get("id"), json.dumps(i, ensure_ascii=False), i.get("doctor", ""),
             i.get("status", "pending_pharm"))
            for i in (items or []) if isinstance(i, dict) and i.get("id")]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM prescriptions WHERE NOT (id = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM prescriptions")
            if rows:
                await c.executemany(_RX_UPSERT, rows)


def load_prescriptions(path: str, json_loader=None) -> list:
    """处方读：PG 可用→PG 真源；不可用/异常/空表→JSON（调用方 json_loader 自有重试语义）。"""
    return _pg_or_json(_pg_load_prescriptions, json_loader, path, [])


def save_prescriptions(items: list, path: str) -> None:
    _json_then_pg(_pg_save_prescriptions, path, items)


def upsert_prescription_row(item: dict) -> None:
    """W4.1 单行 UPSERT（prescriptions）：处方为单据级变更（create/submit/approve/reject/
    rewrite 经 _mutate 各改一张单）。无池/离线/失败抛 RuntimeError（同上纪律）。"""
    if offline_mode() or not _pool:
        raise RuntimeError("upsert_prescription_row 需要 PG 池（离线/单机形态请走 save_prescriptions 全量同步）")

    async def _impl():
        async with _pool.acquire() as c:
            await c.execute(_RX_UPSERT, item["id"], json.dumps(item, ensure_ascii=False),
                            item.get("doctor", ""), item.get("status", "pending_pharm"))

    _pg_write_call("prescriptions", "upsert_row", _impl())


# ---- 第 81 批：处方列表分页/计数下推 PG ----
# 为什么：第 75 批的 limit/offset/summary 只是"读全量再内存切片"——处方随年限无界增长，
# 角标轮询仍要全表解码。这里把 WHERE/ORDER/LIMIT/OFFSET/COUNT 全部下推 PG。
# where 片段由 prescriptions 域层以**固定常量**传入（不拼外部字符串），值走 $n 占位符。

_RX_ORDER = (" ORDER BY data->>'created_at' DESC NULLS LAST, id DESC"
             )  # 新→旧（与内存 list_* 的 [::-1] 同序）；旧行缺 created_at 时排最后（PG 默认 DESC 会把 NULL 排最前）

# 第 103 批：处方关键字检索（病例摘要/医生/科室 + 药品名）。
# 与内存实现同构：全部 lower + 子串包含；`%` `_` `!` 由 ESCAPE '!' 保持字面量。
_RX_SEARCH_EXPR = ("lower(coalesce(data->>'case_text','') || ' ' || "
                   "coalesce(data->>'doctor','') || ' ' || coalesce(data->>'dept',''))")


def rx_search_cond(n: int) -> str:
    """处方关键字条件（$n 传 `_like_contains(lower(keyword))`）。"""
    pattern = f"('%' || ${n} || '%') ESCAPE '!'"
    return (f"({_RX_SEARCH_EXPR} LIKE {pattern} OR EXISTS ("
            "SELECT 1 FROM jsonb_array_elements(coalesce(data->'drugs', '[]'::jsonb)) e "
            f"WHERE lower(coalesce(e->>'name', '')) LIKE {pattern}))")


# 第 116 批：会诊关键字下推（与 `consults.matches_query` 同口径：病例 / AI 意见 /
# 发起人 / 受邀科室 / 已填意见正文）。此前 q 在应用层过滤（先把整箱拉回来再筛），
# 会诊量上量后等于每次搜索全表传输；下推后由 PG 先筛选。
_CONSULT_SEARCH_EXPR = (
    "lower(coalesce(data->>'question', '') || ' ' || coalesce(data->>'ai_analysis', '')"
    " || ' ' || coalesce(data->>'initiator', '')"
    " || ' ' || coalesce(data->>'target_depts', '')"
    " || ' ' || coalesce((SELECT string_agg(e->>'content', ' ')"
    " FROM jsonb_array_elements(coalesce(data->'opinions', '[]'::jsonb)) e), ''))"
)


def consult_search_cond(n: int) -> str:
    """会诊关键字条件（$n 传 `_like_contains(lower(keyword))`）。"""
    return f"{_CONSULT_SEARCH_EXPR} LIKE ('%' || ${n} || '%') ESCAPE '!'"


async def _pg_rx_page_impl(where: str, params: list, limit: int | None,
                           offset: int, count_by_status: bool) -> dict | None:
    """处方分页/计数查询：返回 {items,total[,counts]}；空表 → None（与 load 同语义回落 JSON）。"""
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM prescriptions"):
            return None
        cond = (" WHERE " + where) if where else ""
        args = list(params)
        total = None
        counts = None
        if count_by_status:
            counts = {}
            for r in await c.fetch(  # noqa: S608 —— cond 为域层固定片段
                    "SELECT status, count(*) AS n FROM prescriptions" + cond
                    + " GROUP BY status", *args):
                counts[str(r["status"] or "unknown")] = int(r["n"])
            total = sum(counts.values())
        sql = "SELECT data FROM prescriptions" + cond + _RX_ORDER  # noqa: S608
        if limit is None:
            rows = await c.fetch(sql, *args)
        else:
            n = len(args)
            rows = await c.fetch(sql + f" LIMIT ${n + 1} OFFSET ${n + 2}",  # noqa: S608
                                 *args, max(0, int(limit)), max(0, int(offset)))
            if total is None:
                total = int(await c.fetchval(  # noqa: S608
                    "SELECT count(*) FROM prescriptions" + cond, *args) or 0)
        items = _rx_rows_to_items(rows)
        if total is None:  # limit=None 且未计数：全量即 total
            total = len(items)
        out = {"items": items, "total": int(total)}
        if counts is not None:
            out["counts"] = counts
        return out


def prescriptions_page(where: str = "", params: tuple = (), limit: int | None = None,
                       offset: int = 0, count_by_status: bool = False) -> dict | None:
    """处方分页同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存逻辑）。

    返回 {"items": [...], "total": n, "counts": {...}?}：items 已按新→旧排好且已切片，
    total 为**过滤后总数**（与切片无关）；count_by_status 时按 status 分组计数，
    且 total=各分组之和（summary 角标语义）。"""
    if offline_mode() or not _pool:
        return None
    if not bool(getattr(settings, "pg_primary", True)) and "prescriptions.json" in _repo_pg_stale:
        return None
    try:
        return _pg_call("prescriptions", "page",
                        _pg_rx_page_impl(where, list(params or ()), limit,
                                         max(0, int(offset)), count_by_status))
    except Exception as e:  # noqa: BLE001 —— 分页失败回落内存，绝不影响列表可用性
        logger.warning("pg.prescriptions_page_failed", error=str(e)[:120])
        return None


# ---- 阶段4：病例库合规归档（data jsonb 全量留痕，archived_at 内嵌 data 中，读时按其排序还原追加顺序） ----

_CASE_UPSERT = """INSERT INTO case_archive(id,data,dept,status) VALUES($1,$2::jsonb,$3,$4)
   ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data, dept=EXCLUDED.dept,
   status=EXCLUDED.status, updated_at=now()"""


async def _pg_load_case_archive() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM case_archive ORDER BY data->>'archived_at', id")
    if not rows:
        return None
    out = []
    for r in rows:
        try:
            d = json.loads(r["data"]) if isinstance(r["data"], str) else r["data"]
        except Exception:  # noqa: BLE001 —— 单行损坏跳过，不拖垮整个列表
            continue
        if isinstance(d, dict):
            out.append(d)
    return out


async def _pg_save_case_archive(items: list) -> None:
    rows = [(i.get("id"), json.dumps(i, ensure_ascii=False), i.get("dept", ""),
             i.get("status", "active"))
            for i in (items or []) if isinstance(i, dict) and i.get("id")]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM case_archive WHERE NOT (id = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM case_archive")
            if rows:
                await c.executemany(_CASE_UPSERT, rows)


def load_case_archive(path: str, json_loader=None) -> list:
    """病例库读：PG 可用→PG 真源；不可用/异常/空表→JSON（调用方 json_loader 自有重试语义）。"""
    return _pg_or_json(_pg_load_case_archive, json_loader, path, [])


def save_case_archive(items: list, path: str) -> None:
    _json_then_pg(_pg_save_case_archive, path, items)


# ---- 第 90 批：病例库列表/统计/详情/单行写 下推 PG ----
# 与 consults/prescriptions 同模式：where 由域层以固定常量传入，值走 $n；
# 排序 archived_at DESC NULLS LAST, id DESC（与内存 _load()[::-1] 的「新→旧」一致）。

async def _pg_case_list_impl(where: str, params: list) -> list | None:
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM case_archive"):
            return None
        rows = await c.fetch(  # noqa: S608 —— where 为域层固定片段
            "SELECT data FROM case_archive WHERE " + where
            + " ORDER BY data->>'archived_at' DESC NULLS LAST, id DESC", *params)
    return [d for d in (_json_data_row(r) for r in rows) if d is not None]


def case_archive_list(where: str = "TRUE", params: tuple = ()) -> list | None:
    """病例库列表同步入口：无池/离线/旧编排 stale/异常/空表 → None（调用方回落内存）。"""
    if offline_mode() or not _pool:
        return None
    if (not bool(getattr(settings, "pg_primary", True))
            and "case_archive.json" in _repo_pg_stale):
        return None
    try:
        return _pg_call("case_archive", "list", _pg_case_list_impl(where, list(params or ())))
    except Exception as e:  # noqa: BLE001 —— 失败回落内存，病例库照常可用
        logger.warning("pg.case_archive_list_failed", error=str(e)[:120])
        return None


async def _pg_case_stats_impl() -> dict | None:
    async with _pool.acquire() as c:
        if not await c.fetchval("SELECT count(*) FROM case_archive"):
            return None
        row = await c.fetchrow(
            "SELECT count(*) AS total, "
            "count(*) FILTER (WHERE status = 'active') AS active FROM case_archive")
    total = int(row["total"] or 0)
    active = int(row["active"] or 0)
    return {"total": total, "active": active, "removed": total - active}


def case_archive_stats() -> dict | None:
    """病例库统计（总数/在库/已移除）：无池/离线/stale/异常/空表 → None。"""
    if offline_mode() or not _pool:
        return None
    if (not bool(getattr(settings, "pg_primary", True))
            and "case_archive.json" in _repo_pg_stale):
        return None
    try:
        return _pg_call("case_archive", "stats", _pg_case_stats_impl())
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.case_archive_stats_failed", error=str(e)[:120])
        return None


def get_case_archive_row(arch_id: str) -> tuple[bool, dict | None]:
    """病例库单条按主键直查（三态语义同第 85 批）。"""
    return _get_data_row("case_archive", arch_id)


def upsert_case_archive_row(item: dict) -> None:
    """W4.x 单行 UPSERT（case_archive）：归档/软删除为单据级变更，行级写根除多实例
    全量同步互覆。无池/离线/失败抛 RuntimeError（调用方按 pg_primary 决定回落全量）。"""
    if offline_mode() or not _pool:
        raise RuntimeError("upsert_case_archive_row 需要 PG 池"
                           "（离线/单机形态请走 save_case_archive 全量同步）")

    async def _impl():
        async with _pool.acquire() as c:
            await c.execute(_CASE_UPSERT, item["id"], json.dumps(item, ensure_ascii=False),
                            item.get("dept", ""), item.get("status", "active"))

    _pg_write_call("case_archive", "upsert_row", _impl())


# ---- 阶段1.1：药品字典 drug_dict / 相互作用规则 drug_rules（PG 真源 + JSON 兜底） ----
# 语义与 users/review_queue 完全一致：读 = PG 池可用且域非 stale → PG 真源（空表回落
# JSON）；写 = JSON 原子写先行 + PG 全量同步（best-effort，域级 stale）。
# JSON 兜底文件路径常量在 medical_drug（DRUG_DICT_FILE / DRUG_RULES_FILE）——调用方传入。

_DICT_UPSERT = """INSERT INTO drug_dict(name,aliases,brand_names,category,level)
   VALUES($1,$2::jsonb,$3::jsonb,$4,$5)
   ON CONFLICT(name) DO UPDATE SET aliases=EXCLUDED.aliases,
   brand_names=EXCLUDED.brand_names, category=EXCLUDED.category, level=EXCLUDED.level"""

_RULE_UPSERT = """INSERT INTO drug_rules(drug_a,drug_b,severity,mechanism,management,source)
   VALUES($1,$2,$3,$4,$5,$6)
   ON CONFLICT(drug_a,drug_b) DO UPDATE SET severity=EXCLUDED.severity,
   mechanism=EXCLUDED.mechanism, management=EXCLUDED.management, source=EXCLUDED.source"""

_RULE_DEFAULT_SOURCE = "AI辅助生成·待药师核对"


def norm_pair(a, b) -> tuple[str, str]:
    """药对规范化：两药按字典序固定 a<b，保证 (drug_a,drug_b) 唯一键稳定
    （查重/幂等 upsert/差集补齐都以规范对为准，与输入顺序无关）。
    终评 F9：提升为公共名（drug_dict/medical_router 等外部模块统一引用）。"""
    a, b = str(a).strip(), str(b).strip()
    return (a, b) if a <= b else (b, a)


# 向后兼容别名：历史调用方（旧测试/脚本）仍可用 _norm_pair
_norm_pair = norm_pair


def _norm_severity(sev) -> str:
    """严重度归一到表 CHECK 枚举（高危/中危）：legacy「禁忌」语义上属于必须阻断的
    最高档，归为高危（1.3 迁移 curated_v2 时沿用）；其余未知值保守归中危。"""
    s = str(sev or "").strip()
    if s == "高危" or s == "禁忌":
        return "高危"
    return "中危"


def _dict_row(i: dict) -> tuple:
    """字典条目 → UPSERT 参数（aliases/brand_names 序列化为 jsonb 文本，缺失置空数组）。"""
    aliases = i.get("aliases") or []
    brand = i.get("brand_names") or []
    return (str(i["name"]).strip(),
            json.dumps([str(a) for a in aliases if str(a).strip()], ensure_ascii=False),
            json.dumps([str(b) for b in brand if str(b).strip()], ensure_ascii=False),
            str(i.get("category") or "其他").strip(),
            str(i.get("level") or "处方药").strip())


def _rule_row(i: dict) -> tuple:
    """规则条目 → UPSERT 参数（药对规范化 + severity 归一枚举 + 处置建议缺省空串）。"""
    a, b = _norm_pair(i["drug_a"], i["drug_b"])
    return (a, b, _norm_severity(i.get("severity")),
            str(i.get("mechanism") or "").strip(),
            str(i.get("management") or "").strip(),
            str(i.get("source") or _RULE_DEFAULT_SOURCE).strip())


async def _pg_load_drug_dict() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT name, aliases, brand_names, category, level "
                             "FROM drug_dict ORDER BY id")
    if not rows:
        return None  # 空表 → 回落 JSON（迁移未跑/新库），保守可用性优先
    out = []
    for r in rows:
        al, br = r["aliases"], r["brand_names"]
        out.append({"name": r["name"],
                    "aliases": json.loads(al) if isinstance(al, str) and al else (al or []),
                    "brand_names": json.loads(br) if isinstance(br, str) and br else (br or []),
                    "category": r["category"] or "其他",
                    "level": r["level"] or "处方药"})
    return out


async def _pg_save_drug_dict(items: list) -> None:
    rows = [_dict_row(i) for i in (items or [])
            if isinstance(i, dict) and str(i.get("name") or "").strip()]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM drug_dict WHERE NOT (name = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM drug_dict")
            await c.executemany(_DICT_UPSERT, rows)


def load_drug_dict(path: str, json_loader=None) -> list:
    """药品字典读：PG 可用→PG 真源；不可用/异常/空表→JSON 兜底文件（path）。"""
    return _pg_or_json(_pg_load_drug_dict, json_loader, path, [])


def save_drug_dict(items: list, path: str) -> None:
    """药品字典写：JSON 原子写兜底 + PG 全量同步（不在清单中的规范名一并清除）。"""
    _json_then_pg(_pg_save_drug_dict, path, items)


async def _pg_load_drug_rules() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT drug_a, drug_b, severity, mechanism, management, source "
                             "FROM drug_rules ORDER BY id")
    if not rows:
        return None
    return [{"drug_a": r["drug_a"], "drug_b": r["drug_b"], "severity": r["severity"],
             "mechanism": r["mechanism"] or "",
             "management": r["management"] or "",
             "source": r["source"] or _RULE_DEFAULT_SOURCE} for r in rows]


async def _pg_save_drug_rules(items: list) -> None:
    rows = [_rule_row(i) for i in (items or [])
            if isinstance(i, dict) and str(i.get("drug_a") or "").strip()
            and str(i.get("drug_b") or "").strip()]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                # 全量同步删除语义：按规范药对保留（unnest 双数组逐对匹配）
                await c.execute("""DELETE FROM drug_rules WHERE NOT EXISTS (
                    SELECT 1 FROM unnest($1::text[], $2::text[]) AS p(drug_a, drug_b)
                    WHERE p.drug_a = drug_rules.drug_a AND p.drug_b = drug_rules.drug_b)""",
                                [r[0] for r in rows], [r[1] for r in rows])
            else:
                await c.execute("DELETE FROM drug_rules")
            await c.executemany(_RULE_UPSERT, rows)


def load_drug_rules(path: str, json_loader=None) -> list:
    """相互作用规则读：PG 可用→PG 真源；不可用/异常/空表→JSON 兜底文件（path）。"""
    return _pg_or_json(_pg_load_drug_rules, json_loader, path, [])


def save_drug_rules(items: list, path: str) -> None:
    """相互作用规则写：JSON 原子写兜底 + PG 全量同步（不在清单中的药对一并清除）。"""
    _json_then_pg(_pg_save_drug_rules, path, items)


# ---- 轮 A3：运行时开关 runtime_flags（PG 真源 + JSON 兜底；接口在 runtime_flags.py） ----

_FLAGS_UPSERT = """INSERT INTO runtime_flags(key,value) VALUES($1,$2::jsonb)
   ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()"""


async def _pg_load_runtime_flags() -> dict | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT key, value FROM runtime_flags")
    if not rows:
        return None  # 空表 → 回落 JSON（迁移未跑/新库），保守可用性优先
    return {r["key"]: (json.loads(r["value"]) if isinstance(r["value"], str) else r["value"])
            for r in rows}


async def _pg_save_runtime_flags(d: dict) -> None:
    rows = [(str(k), json.dumps(bool(v))) for k, v in (d or {}).items()
            if str(k).strip()]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM runtime_flags WHERE NOT (key = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM runtime_flags")
            await c.executemany(_FLAGS_UPSERT, rows)


def load_runtime_flags(json_loader=None, path: str = "runtime_flags.json") -> dict:
    """运行时开关读：PG 可用→PG 真源；不可用/异常/空表→JSON 兜底（runtime_flags._load）。"""
    return _pg_or_json(_pg_load_runtime_flags, json_loader, path, {})


def save_runtime_flags(d: dict, path: str) -> None:
    """运行时开关写：JSON 原子写兜底 + PG 全量同步（不在键集的一并清除）。

    **多副本注意**：全量同步会删除"不在本次键集"的 PG 行——只有明确知道完整键集时
    才能调用（迁移/运维脚本）。运行时单键切换请用 ``save_runtime_flag``。
    """
    _json_then_pg(_pg_save_runtime_flags, path, d)


async def _pg_upsert_runtime_flag(key: str, value: bool) -> None:
    async with _pool.acquire() as c:
        await c.executemany(_FLAGS_UPSERT, [(str(key), json.dumps(bool(value)))])


def save_runtime_flag(key: str, value: bool) -> bool:
    """运行时开关**单键**写（评测 E6 多副本安全）。

    只 UPSERT 本键，不清除其它键：两个副本同时切换不同开关时，不会再出现
    "A 写入后被 B 的全量同步 DELETE 掉"的丢失更新。
    返回 True=PG 已写；False=PG 不可用（调用方走 JSON 落盘路径）。
    同键并发切换仍是"后到覆盖"——完整 CAS 需 PG 行锁/advisory lock，列为后续项。
    """
    if offline_mode() or not _pool:
        return False
    try:
        _pg_write_call("runtime_flags", "upsert_one",
                       _pg_upsert_runtime_flag(str(key), bool(value)))
    except Exception as e:  # noqa: BLE001 —— 写失败由调用方回落 JSON，不中断开关切换
        logger.warning("pg.runtime_flags.upsert_one_failed", error=str(e)[:120])
        return False
    return True


def mirror_runtime_flags_json(d: dict, path: str) -> None:
    """JSON 镜像写（PG 已单键落库时用）：避免全量同步把其它副本刚写的键删掉。"""
    _write_json_atomic(path, d)


# ---- G87：临床技能卡（PG 真源 + JSON 兜底，语义同 runtime_flags） ----

_SKILLS_UPSERT = """INSERT INTO agent_skills(code,data,status,updated_at)
   VALUES($1,$2::jsonb,$3,now())
   ON CONFLICT(code) DO UPDATE SET data=EXCLUDED.data, status=EXCLUDED.status, updated_at=now()"""


async def _pg_load_skills() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM agent_skills ORDER BY code")
    if not rows:
        return None  # 空表 → 回落 JSON（新库/未迁移），与其它域同纪律
    out = []
    for r in rows:
        try:
            out.append(json.loads(r["data"]) if isinstance(r["data"], str) else r["data"])
        except Exception:  # noqa: BLE001 —— 单行损坏跳过，不拖垮整个列表
            continue
    return out


async def _pg_save_skills(items: list) -> None:
    rows = [(i.get("code"), json.dumps(i, ensure_ascii=False), i.get("status", "DRAFT"))
            for i in (items or []) if isinstance(i, dict) and str(i.get("code") or "").strip()]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute("DELETE FROM agent_skills WHERE NOT (code = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute("DELETE FROM agent_skills")
            await c.executemany(_SKILLS_UPSERT, rows)


def load_skills(path: str, json_loader=None) -> list:
    """技能卡读：PG 可用→PG 真源；不可用/异常/空表→JSON（调用方 json_loader）。"""
    return _pg_or_json(_pg_load_skills, json_loader, path, [])


def save_skills(items: list, path: str) -> None:
    _json_then_pg(_pg_save_skills, path, items)


# ---- G88：Bot / Bot 团队 / Bot 记忆（通用 JSON 列表域，表名与主键走白名单） ----

_JSON_LIST_KEYS = {
    "agent_bots": "code",
    "agent_bot_teams": "code",
    "agent_bot_memories": "id",
    "agent_skill_experiences": "id",
    "agent_skill_proposals": "id",
    "agent_skill_metrics": "id",
    "agent_skill_advices": "id",
    "agent_sleep_reports": "reportId",
}


async def _pg_load_json_list(table: str, key: str) -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch(f"SELECT data FROM {table} ORDER BY updated_at DESC")
    if not rows:
        return None  # 空表 → 回落 JSON（与其它域同纪律）
    out = []
    for r in rows:
        try:
            out.append(json.loads(r["data"]) if isinstance(r["data"], str) else r["data"])
        except Exception:  # noqa: BLE001 —— 单行损坏跳过
            continue
    return out


async def _pg_save_json_list(table: str, key: str, items: list) -> None:
    rows = [(str(i.get(key)), json.dumps(i, ensure_ascii=False), str(i.get("status") or ""))
            for i in (items or []) if isinstance(i, dict) and str(i.get(key) or "").strip()]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute(f"DELETE FROM {table} WHERE NOT ({key} = ANY($1))",
                                [r[0] for r in rows])
            else:
                await c.execute(f"DELETE FROM {table}")
            await c.executemany(
                f"""INSERT INTO {table}({key},data,status,updated_at)
                    VALUES($1,$2::jsonb,$3,now())
                    ON CONFLICT({key}) DO UPDATE
                    SET data=EXCLUDED.data, status=EXCLUDED.status, updated_at=now()""",
                rows)


def load_json_list(table: str, path: str, json_loader=None) -> list:
    """通用 JSON 列表域读：PG 真源 → JSON 兜底（表名/主键白名单，非法表名直接报错）。"""
    key = _JSON_LIST_KEYS[table]

    async def _load() -> list | None:
        return await _pg_load_json_list(table, key)

    return _pg_or_json(_load, json_loader, path, [])


def save_json_list(table: str, path: str, items: list) -> None:
    key = _JSON_LIST_KEYS[table]

    async def _save(data: list) -> None:
        await _pg_save_json_list(table, key, data)

    _json_then_pg(_save, path, items)


# ---- 行级 upsert（G96）：多实例下快照/建议按主键幂等，避免全量覆盖互相丢数据 ----

_JSON_LIST_LOCK = threading.Lock()


async def _pg_upsert_json_item(table: str, key: str, item: dict,
                               ignore_existing: bool) -> None:
    conflict = ("DO NOTHING" if ignore_existing else
                "DO UPDATE SET data=EXCLUDED.data, status=EXCLUDED.status, updated_at=now()")
    async with _pool.acquire() as c:
        await c.execute(
            f"""INSERT INTO {table}({key},data,status,updated_at)
                VALUES($1,$2::jsonb,$3,now())
                ON CONFLICT({key}) {conflict}""",
            str(item.get(key)), json.dumps(item, ensure_ascii=False),
            str(item.get("status") or ""))


def _json_upsert_item(path: str, key: str, item: dict, ignore_existing: bool) -> None:
    with _JSON_LIST_LOCK, _mirror_file_lock(path):
        data = _read_json(path, [])
        exists = any(i.get(key) == item.get(key) for i in data)
        if exists and ignore_existing:
            return
        data = [i for i in data if i.get(key) != item.get(key)]
        data.append(item)
        _write_json_atomic(path, data)


async def _pg_upsert_skill(item: dict) -> None:
    async with _pool.acquire() as c:
        await c.execute(_SKILLS_UPSERT, str(item.get("code")),
                        json.dumps(item, ensure_ascii=False),
                        str(item.get("status") or "DRAFT"))


def upsert_skill_item(item: dict, path: str) -> None:
    """技能卡按 code 行级 upsert（PG 真源 + JSON 镜像）。

    评审 M-32：技能卡的写路径不再整表覆盖，多实例并发只触达目标行。
    """
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        _json_upsert_item(path, "code", item, False)
        if not offline_mode():
            _repo_pg_stale.add(domain)
        return
    try:
        _pg_write_call("skills", "upsert_row", _pg_upsert_skill(item))
        _repo_pg_stale.discard(domain)
    except Exception as e:  # noqa: BLE001 —— 行级写失败降级 JSON，读路径按 stale 回落
        logger.warning("pg.repo.upsert_skill_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
    _json_upsert_item(path, "code", item, False)


# ---- M-32 剩余：行级 CAS（比较并交换）--------------------------------------
# 行级 upsert 只解决"互相删除他行"；同一行被两个实例同时改时仍会后写覆盖。
# 这里把 rowVersion 比较下推到 SQL：只有 `data->>'rowVersion'` 等于调用方读到的
# 版本才更新，影响行数为 0 → 调用方按"已被他人修改"处理。

async def _pg_cas_skill(item: dict, expected: int) -> int:
    async with _pool.acquire() as c:
        return await c.fetchval(
            """WITH u AS (
                   UPDATE agent_skills SET data=$3::jsonb, status=$4, updated_at=now()
                   WHERE code=$1 AND (data->>'rowVersion')::int = $2
                   RETURNING 1)
               SELECT count(*) FROM u""",
            str(item.get("code")), int(expected),
            json.dumps(item, ensure_ascii=False), str(item.get("status") or "DRAFT"))


async def _pg_cas_json_item(table: str, key: str, item: dict, expected: int) -> int:
    async with _pool.acquire() as c:
        return await c.fetchval(
            f"""WITH u AS (
                    UPDATE {table} SET data=$3::jsonb, status=$4, updated_at=now()
                    WHERE {key}=$1 AND (data->>'rowVersion')::int = $2
                    RETURNING 1)
                SELECT count(*) FROM u""",
            str(item.get(key)), int(expected),
            json.dumps(item, ensure_ascii=False), str(item.get("status") or ""))


def _json_cas_item(path: str, key: str, item: dict, expected: int) -> bool:
    with _JSON_LIST_LOCK:
        data = _read_json(path, [])
        target = next((i for i in data if i.get(key) == item.get(key)), None)
        if target is None or int(target.get("rowVersion", 0)) != int(expected):
            return False
        data = [i for i in data if i.get(key) != item.get(key)] + [item]
        _write_json_atomic(path, data)
        return True


def cas_update_skill_item(item: dict, expected: int, path: str) -> bool:
    """技能卡按 code + rowVersion 原子更新；False 表示版本已变（冲突）。"""
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        return _json_cas_item(path, "code", item, expected)
    try:
        ok = bool(_run_pg(_pg_cas_skill(item, expected)))
        _repo_pg_stale.discard(domain) if ok else _repo_pg_stale.add(domain)
    except Exception as e:  # noqa: BLE001 —— PG 异常时退回 JSON CAS（语义一致）
        logger.warning("pg.repo.cas_skill_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
        return _json_cas_item(path, "code", item, expected)
    return ok and _json_cas_item(path, "code", item, expected)


def cas_update_json_item(table: str, path: str, item: dict, expected: int) -> bool:
    """通用 JSON 列表域按主键 + rowVersion 原子更新；False 表示版本已变。"""
    key = _JSON_LIST_KEYS[table]
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        return _json_cas_item(path, key, item, expected)
    try:
        ok = bool(_run_pg(_pg_cas_json_item(table, key, item, expected)))
        _repo_pg_stale.discard(domain) if ok else _repo_pg_stale.add(domain)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.repo.cas_item_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
        return _json_cas_item(path, key, item, expected)
    return ok and _json_cas_item(path, key, item, expected)


async def _pg_insert_skill(item: dict) -> int:
    async with _pool.acquire() as c:
        return await c.fetchval(
            """WITH i AS (
                   INSERT INTO agent_skills(code,data,status,updated_at)
                   VALUES($1,$2::jsonb,$3,now()) ON CONFLICT(code) DO NOTHING
                   RETURNING 1)
               SELECT count(*) FROM i""",
            str(item.get("code")), json.dumps(item, ensure_ascii=False),
            str(item.get("status") or "DRAFT"))


async def _pg_insert_json_item(table: str, key: str, item: dict) -> int:
    async with _pool.acquire() as c:
        return await c.fetchval(
            f"""WITH i AS (
                    INSERT INTO {table}({key},data,status,updated_at)
                    VALUES($1,$2::jsonb,$3,now()) ON CONFLICT({key}) DO NOTHING
                    RETURNING 1)
                SELECT count(*) FROM i""",
            str(item.get(key)), json.dumps(item, ensure_ascii=False),
            str(item.get("status") or ""))


def _json_insert_item(path: str, key: str, item: dict) -> bool:
    with _JSON_LIST_LOCK:
        data = _read_json(path, [])
        if any(i.get(key) == item.get(key) for i in data):
            return False
        data.append(item)
        _write_json_atomic(path, data)
        return True


def insert_skill_item(item: dict, path: str) -> bool:
    """只在不存在时插入（冲突返回 False），用于创建路径的跨实例去重。"""
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        return _json_insert_item(path, "code", item)
    try:
        ok = bool(_run_pg(_pg_insert_skill(item)))
        _repo_pg_stale.discard(domain) if ok else _repo_pg_stale.add(domain)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.repo.insert_skill_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
        return _json_insert_item(path, "code", item)
    if not ok:
        return False
    # 第 56 批：PG 已是真源，JSON 只是镜像——多进程并发写同一文件可能失败/被锁，
    # 镜像失败只告警，绝不让业务写返回 500（此前 40 并发创建有 12 个假 500）。
    try:
        _json_insert_item(path, "code", item)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.repo.json_mirror_failed", error=str(e)[:120], file=domain)
    return True


def insert_json_item(table: str, path: str, item: dict) -> bool:
    key = _JSON_LIST_KEYS[table]
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        return _json_insert_item(path, key, item)
    try:
        ok = bool(_run_pg(_pg_insert_json_item(table, key, item)))
        _repo_pg_stale.discard(domain) if ok else _repo_pg_stale.add(domain)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.repo.insert_item_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
        return _json_insert_item(path, key, item)
    if not ok:
        return False
    # 第 56 批：同 insert_skill_item——PG 成功即成功，JSON 镜像失败只告警
    try:
        _json_insert_item(path, key, item)
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.repo.json_mirror_failed", error=str(e)[:120], file=domain)
    return True


def upsert_json_item(table: str, path: str, item: dict,
                     ignore_existing: bool = False) -> None:
    """按主键幂等写单条（PG 行级 upsert + JSON 镜像/兜底）。

    ``ignore_existing=True``：已存在则完全不动（用于"同一建议只保留首次状态"）。
    """
    key = _JSON_LIST_KEYS[table]
    domain = os.path.basename(path)
    if offline_mode() or not _pool:
        _json_upsert_item(path, key, item, ignore_existing)
        if not offline_mode():
            _repo_pg_stale.add(domain)
        return
    try:
        _pg_write_call(table, "upsert_row",
                       _pg_upsert_json_item(table, key, item, ignore_existing))
        _repo_pg_stale.discard(domain)
    except Exception as e:  # noqa: BLE001 —— 行级写失败降级 JSON，读路径按 stale 回落
        logger.warning("pg.repo.upsert_failed", error=str(e)[:120], file=domain)
        _repo_pg_stale.add(domain)
    _json_upsert_item(path, key, item, ignore_existing)


async def _seed_drug_tables_impl(dict_items: list, rule_items: list) -> dict:
    out = {"drug_dict": 0, "drug_rules": 0}
    async with _pool.acquire() as c:
        d_rows = [_dict_row(i) for i in (dict_items or [])
                  if isinstance(i, dict) and str(i.get("name") or "").strip()]
        if d_rows:
            await c.executemany(_DICT_UPSERT, d_rows)
            out["drug_dict"] = len(d_rows)
        r_rows = [_rule_row(i) for i in (rule_items or [])
                  if isinstance(i, dict) and str(i.get("drug_a") or "").strip()
                  and str(i.get("drug_b") or "").strip()]
        if r_rows:
            await c.executemany(_RULE_UPSERT, r_rows)
            out["drug_rules"] = len(r_rows)
    return out


async def seed_drug_tables(dict_items: list, rule_items: list) -> dict:
    """阶段1.2：seed 种子 → drug_dict/drug_rules 幂等 upsert（ON CONFLICT DO UPDATE，
    只增量补齐/更新，不删除表内已有行）。返回 {drug_dict: n, drug_rules: n}；
    PG 不可用/异常返回 {}（绝不冒泡）。scripts/seed_drug_dict.py 调用。"""
    if not _pool:
        return {}
    try:
        return await _arun(_seed_drug_tables_impl(dict_items, rule_items))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.seed_drug_tables_failed", error=str(e)[:150])
        return {}


# ---------- B1 迁移：JSON → PG（表空才导，幂等 ON CONFLICT DO NOTHING） ----------

async def _migrate_impl() -> dict:
    out: dict = {}
    async with _pool.acquire() as c:
        # users（兼容旧 users.json 无 dept 字段：rec.get 缺省空串）
        from backend.core.auth import USERS_FILE
        users = _read_json(USERS_FILE, {})
        if isinstance(users, dict) and users and not await c.fetchval("SELECT count(*) FROM users"):
            rows = [(u, rec.get("role", ""), rec.get("dept", ""), rec.get("password_hash", ""))
                    for u, rec in users.items() if isinstance(rec, dict)]
            if rows:
                await c.executemany(_USER_IMPORT, rows)
                out["users"] = len(rows)
        # departments
        from backend.core.departments import DEPARTMENTS_FILE
        d = _read_json(DEPARTMENTS_FILE, {})
        lst = d.get("list") if isinstance(d, dict) else None
        if isinstance(lst, list) and lst and not await c.fetchval("SELECT count(*) FROM departments"):
            rows = [(i, str(n)) for i, n in enumerate(lst, start=1)]
            await c.executemany("INSERT INTO departments(id,name) VALUES($1,$2) "
                                "ON CONFLICT(id) DO NOTHING", rows)
            out["departments"] = len(rows)
        # review_queue
        from backend.core.medical_review import QUEUE_FILE
        items = _read_json(QUEUE_FILE, [])
        if isinstance(items, list) and items:
            if not await c.fetchval("SELECT count(*) FROM review_queue"):
                rows = [_review_row(i) for i in items if isinstance(i, dict)]
                if rows:
                    await c.executemany(_REVIEW_UPSERT, rows)
                    out["review_queue"] = len(rows)
            else:
                # 任务1 数据修复：repo 写编排 JSON 先行、PG 同步 best-effort，PG 同步失败
                # 窗口（如旧库缺 sources 列致 UPSERT 确定性失败）内 JSON 会积累 PG 缺失的
                # 记录。此前仅「表空才导」：重启后内存 stale 集合清空、读切回 PG 旧真源，
                # 下一次全量同步写（读 PG 子集后追加回写）会把 JSON 独有记录从两个真源
                # 同时抹掉（运行环境实测 31 条，含 imaging 留痕 rev-8820df1757）。
                # JSON ⊇ PG 恒成立（JSON 先写且不回滚），按 id 差集只增不覆盖，幂等可重复跑。
                existing = {r["id"] for r in await c.fetch("SELECT id FROM review_queue")}
                rows = [_review_row(i) for i in items
                        if isinstance(i, dict) and i.get("id") and i["id"] not in existing]
                if rows:
                    await c.executemany(_REVIEW_UPSERT, rows)
                    out["review_queue_reconciled"] = len(rows)
        # llm_providers
        from backend.core.llm_config import LLM_CONFIG_FILE
        cfg = _read_json(LLM_CONFIG_FILE, {})
        rows = _llm_rows_from_cfg(cfg if isinstance(cfg, dict) else {})
        if rows and not await c.fetchval("SELECT count(*) FROM llm_providers"):
            await c.executemany(_LLM_IMPORT, rows)
            out["llm_providers"] = len(rows)
        # 阶段1.1：药品字典/规则——repo JSON 兜底文件 ⊇ PG（JSON 先写不回滚）：
        # 表空 → 全量导入；PG 非空 → 按 name/规范药对差集只增不覆盖补齐（幂等可重复跑，
        # 与 review_queue 同纪律，防"PG 同步失败窗口内 JSON 独有数据被全量同步抹掉"）。
        from backend.core.medical_drug import DRUG_DICT_FILE, DRUG_RULES_FILE
        d_items = _read_json(DRUG_DICT_FILE, [])
        if isinstance(d_items, list) and d_items:
            if not await c.fetchval("SELECT count(*) FROM drug_dict"):
                rows = [_dict_row(i) for i in d_items
                        if isinstance(i, dict) and str(i.get("name") or "").strip()]
                if rows:
                    await c.executemany(_DICT_UPSERT, rows)
                    out["drug_dict"] = len(rows)
            else:
                existing = {r["name"] for r in await c.fetch("SELECT name FROM drug_dict")}
                rows = [_dict_row(i) for i in d_items
                        if isinstance(i, dict) and str(i.get("name") or "").strip()
                        and str(i["name"]).strip() not in existing]
                if rows:
                    await c.executemany(_DICT_UPSERT, rows)
                    out["drug_dict_reconciled"] = len(rows)
        r_items = _read_json(DRUG_RULES_FILE, [])
        if isinstance(r_items, list) and r_items:
            if not await c.fetchval("SELECT count(*) FROM drug_rules"):
                rows = [_rule_row(i) for i in r_items
                        if isinstance(i, dict) and str(i.get("drug_a") or "").strip()
                        and str(i.get("drug_b") or "").strip()]
                if rows:
                    await c.executemany(_RULE_UPSERT, rows)
                    out["drug_rules"] = len(rows)
            else:
                existing = {(r["drug_a"], r["drug_b"]) for r in
                            await c.fetch("SELECT drug_a, drug_b FROM drug_rules")}
                rows = []
                for i in r_items:
                    if isinstance(i, dict) and str(i.get("drug_a") or "").strip() \
                            and str(i.get("drug_b") or "").strip():
                        key = _norm_pair(i["drug_a"], i["drug_b"])
                        if key not in existing:  # 只增不覆盖，幂等
                            rows.append(_rule_row(i))
                            existing.add(key)
                if rows:
                    await c.executemany(_RULE_UPSERT, rows)
                    out["drug_rules_reconciled"] = len(rows)
        # 阶段2.1：智能开药处方——与 review_queue 同纪律（表空才导；PG 非空按 id 差集
        # 只增不覆盖补齐，JSON ⊇ PG 恒成立，幂等可重复跑）。
        from backend.core.prescriptions import PRESCRIPTIONS_FILE
        rx_items = _read_json(PRESCRIPTIONS_FILE, [])
        if isinstance(rx_items, list) and rx_items:
            if not await c.fetchval("SELECT count(*) FROM prescriptions"):
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("doctor", ""), i.get("status", "pending_pharm"))
                        for i in rx_items if isinstance(i, dict) and i.get("id")]
                if rows:
                    await c.executemany(_RX_UPSERT, rows)
                    out["prescriptions"] = len(rows)
            else:
                existing = {r["id"] for r in await c.fetch("SELECT id FROM prescriptions")}
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("doctor", ""), i.get("status", "pending_pharm"))
                        for i in rx_items
                        if isinstance(i, dict) and i.get("id") and i["id"] not in existing]
                if rows:
                    await c.executemany(_RX_UPSERT, rows)
                    out["prescriptions_reconciled"] = len(rows)
        # 阶段4：病例库合规归档——与 review_queue/prescriptions 同纪律（表空才导；PG 非空
        # 按 id 差集只增不覆盖补齐，JSON ⊇ PG 恒成立，幂等可重复跑）。
        from backend.core.case_archive import CASE_ARCHIVE_FILE
        ca_items = _read_json(CASE_ARCHIVE_FILE, [])
        if isinstance(ca_items, list) and ca_items:
            if not await c.fetchval("SELECT count(*) FROM case_archive"):
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("dept", ""), i.get("status", "active"))
                        for i in ca_items if isinstance(i, dict) and i.get("id")]
                if rows:
                    await c.executemany(_CASE_UPSERT, rows)
                    out["case_archive"] = len(rows)
            else:
                existing = {r["id"] for r in await c.fetch("SELECT id FROM case_archive")}
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("dept", ""), i.get("status", "active"))
                        for i in ca_items
                        if isinstance(i, dict) and i.get("id") and i["id"] not in existing]
                if rows:
                    await c.executemany(_CASE_UPSERT, rows)
                    out["case_archive_reconciled"] = len(rows)
        # 第 132 批（评测 A1）：会诊单此前缺席迁移清单——PG 表非空后读路径不再回落 JSON，
        # 旧会诊单永久不可见，且换环境时静默丢数据。与 prescriptions/case_archive 同纪律：
        # 表空 → 全量导入；PG 非空 → 按 id 差集只增不覆盖（幂等可重复跑）。
        # 会诊意见子表由 jsonb 重建（FULL_SYNC 幂等），避免列表/待填意见查询缺行。
        from backend.core.consults import CONSULTS_FILE
        cs_items = _read_json(CONSULTS_FILE, [])
        if isinstance(cs_items, list) and cs_items:
            if not await c.fetchval("SELECT count(*) FROM consults"):
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("initiator", ""), i.get("status", "open"))
                        for i in cs_items if isinstance(i, dict) and i.get("id")]
                if rows:
                    await c.executemany(_CONSULT_UPSERT, rows)
                    # 评测 A4：逐单同步派生行（Python 口径，非法 ts 置 NULL 不中断）
                    for i in cs_items:
                        if isinstance(i, dict) and i.get("id"):
                            await _sync_consult_opinions(c, i)
                    out["consults"] = len(rows)
            else:
                existing = {r["id"] for r in await c.fetch("SELECT id FROM consults")}
                rows = [(i.get("id"), json.dumps(i, ensure_ascii=False),
                         i.get("initiator", ""), i.get("status", "open"))
                        for i in cs_items
                        if isinstance(i, dict) and i.get("id") and i["id"] not in existing]
                if rows:
                    await c.executemany(_CONSULT_UPSERT, rows)
                    # 评测 A4：只同步本次补齐的单（不再全表重建，避免误删 PG 既有派生行）
                    added_ids = {r[0] for r in rows}
                    for i in cs_items:
                        if isinstance(i, dict) and i.get("id") in added_ids:
                            await _sync_consult_opinions(c, i)
                    out["consults_reconciled"] = len(rows)
        # 轮 A3：runtime_flags——repo JSON 兜底文件 ⊇ PG（JSON 先写不回滚）：
        # 表空 → 全量导入；PG 非空 → 按 key 差集只增不覆盖补齐（幂等可重复跑，
        # 与 drug_dict 同纪律）。
        from backend.core.runtime_flags import FLAGS_FILE
        flags = _read_json(FLAGS_FILE, {})
        if isinstance(flags, dict) and flags:
            if not await c.fetchval("SELECT count(*) FROM runtime_flags"):
                rows = [(str(k), json.dumps(bool(v))) for k, v in flags.items()]
                if rows:
                    await c.executemany(_FLAGS_UPSERT, rows)
                    out["runtime_flags"] = len(rows)
            else:
                existing = {r["key"] for r in await c.fetch("SELECT key FROM runtime_flags")}
                rows = [(str(k), json.dumps(bool(v))) for k, v in flags.items()
                        if str(k) not in existing]
                if rows:
                    await c.executemany(_FLAGS_UPSERT, rows)
                    out["runtime_flags_reconciled"] = len(rows)
    return out


async def migrate_json_to_pg() -> dict:
    """B1 迁移：PG 表空而 JSON 有数据 → 幂等导入（ON CONFLICT DO NOTHING）。
    lifespan 启动（init 成功后）自动触发；scripts/migrate_json_to_pg.py 可手动触发。
    返回 {表: 导入条数}；PG 不可用/异常返回 {}（绝不影响启动）。"""
    if not _pool:
        return {}
    try:
        return await _arun(_migrate_impl())
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.migrate_failed", error=str(e)[:150])
        return {}
