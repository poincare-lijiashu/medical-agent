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
  lifespan 启动时自动触发，scripts/migrate_json_to_pg.py 可手动触发。
"""
from __future__ import annotations

import asyncio
import json
import os
import threading
from concurrent.futures import TimeoutError as _FutTimeout
from datetime import datetime, timezone

import asyncpg

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)
_pool = None

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
    耗尽连接，后续请求全部超时降级（取消经 future 链传播为协程 CancelledError）。"""
    fut = asyncio.run_coroutine_threadsafe(coro, _get_db_loop())
    try:
        return fut.result(timeout=_BRIDGE_TIMEOUT)
    except _FutTimeout:
        fut.cancel()
        raise


async def _arun(coro):
    """异步桥接：主循环协程内把池操作转投专用 DB 循环（wrap_future 支持跨循环等待）。"""
    return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(coro, _get_db_loop()))


def _dsn() -> str:
    return settings.async_database_url.replace("postgresql+asyncpg://", "postgresql://")


async def _init_impl() -> bool:
    """建池 + 建表（含旧表平滑升级：users.dept / review_queue.images）。"""
    global _pool
    pool = await asyncpg.create_pool(_dsn(), min_size=1, max_size=5, timeout=5)
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
            await c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_llm_providers_kind_pid "
                            "ON llm_providers(kind,pid)")
    except Exception:
        try:
            await pool.close()
        except Exception:  # noqa: BLE001
            pass
        raise
    _pool = pool
    logger.info("pg_store.ready")
    return True


async def init() -> bool:
    """建池+建表（池归属专用 DB 循环）。成功 True；任何异常降级 JSON 模式（_pool=None）。"""
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

async def _mirror_audit_impl(event_type: str, actor: str, payload: dict) -> None:
    async with _pool.acquire() as c:
        await c.execute("INSERT INTO audit_log(event_type,actor,payload) VALUES($1,$2,$3::jsonb)",
                        event_type, actor, json.dumps(payload, ensure_ascii=False))


async def mirror_audit(event_type: str, actor: str, payload: dict) -> None:
    if not _pool:
        return
    try:
        await _arun(_mirror_audit_impl(event_type, actor, payload))
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
        await _arun(_mirror_user_impl(username, role, pw_hash))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_user_failed", error=str(e)[:120])


_REVIEW_COLS = ("id", "ts", "agent", "question", "answer", "confidence", "risk_reason",
                "status", "submitted_by", "reviewed_by", "review_note", "resolved_at",
                "resubmit_of", "attempt", "meta", "sources")

_REVIEW_UPSERT = """INSERT INTO review_queue(id,ts,agent,question,answer,confidence,risk_reason,
   status,submitted_by,reviewed_by,review_note,resolved_at,images,resubmit_of,attempt,meta,sources)
   VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13::jsonb,$14,$15,$16::jsonb,$17::jsonb)
   ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status, reviewed_by=EXCLUDED.reviewed_by,
   review_note=EXCLUDED.review_note, resolved_at=EXCLUDED.resolved_at,
   images=EXCLUDED.images, sources=EXCLUDED.sources, updated_at=now()"""


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
            json.dumps(sources, ensure_ascii=False) if sources else None)


async def _mirror_review_impl(item: dict) -> None:
    async with _pool.acquire() as c:
        await c.execute(_REVIEW_UPSERT, *_review_row(item))


async def mirror_review(item: dict | None) -> None:
    if not _pool or not item:
        return
    try:
        await _arun(_mirror_review_impl(item))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.mirror_review_failed", error=str(e)[:120])


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

async def _audit_recent_pg_impl(limit: int, offset: int) -> list:
    async with _pool.acquire() as c:
        rows = await c.fetch(
            "SELECT ts, event_type, actor, payload FROM audit_log "
            "ORDER BY ts DESC, id DESC LIMIT $1 OFFSET $2", limit, offset)
    out = []
    for r in rows:
        p = r["payload"]
        if isinstance(p, (str, bytes)):  # asyncpg 默认 jsonb 返回 str；兼容驱动直返 dict
            try:
                p = json.loads(p)
            except Exception:  # noqa: BLE001 —— 单行 payload 损坏降级空对象，不拖垮整页
                p = {}
        ts = r["ts"]
        out.append({"ts": ts.isoformat() if hasattr(ts, "isoformat") else str(ts),
                    "event_type": r["event_type"], "actor": r["actor"], "payload": p})
    return out


async def audit_recent_pg(limit: int = 50, offset: int = 0) -> list | None:
    """任务5：admin 审计流数据源——PG audit_log 表按 ts 倒序分页。

    返回与 AuditLog.recent(JSONL) 相同结构 [{ts,event_type,actor,payload}]（payload
    内含 action/role 等字段）；未建池/异常/超时一律返回 None（调用方回落 JSONL 现状），
    绝不冒泡影响 admin 面板。"""
    if not _pool:
        return None
    try:
        return await _arun(_audit_recent_pg_impl(max(1, int(limit)), max(0, int(offset))))
    except Exception as e:  # noqa: BLE001
        logger.warning("pg.audit_recent_failed", error=str(e)[:120])
        return None


async def _purge_pending_impl() -> str:
    async with _pool.acquire() as c:
        return await c.execute("DELETE FROM review_queue WHERE status='pending'")


async def purge_pending_reviews() -> str:
    """镜像一致性：清空 review_queue 中 pending 行（与 review.purge_pending 配套）。best-effort。"""
    if not _pool:
        return ""
    try:
        return await _arun(_purge_pending_impl())
    except Exception:  # noqa: BLE001
        return ""


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
    """通用原子写（tmp + os.replace），与既有各模块写盘形态一致（ensure_ascii=False/indent=2）。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


_repo_pg_stale = False  # repo 层最近一次 PG 全量同步失败标志：置位后读回落 JSON（最新数据），
                        # 直到下一次成功写清除——防「JSON 已写新、PG 仍旧」窗口内读回滚旧真源


def _pg_or_json(load_fn, json_loader, path: str, default):
    """repo 读编排：PG 池可用且非 stale → 读 PG（非空即真源）；None/stale/异常/超时/空表
    → JSON（现状行为；stale 时 JSON 恒为最新已落盘数据，读它保证不回滚）。"""
    if _pool and not _repo_pg_stale:
        try:
            data = _run_pg(load_fn())
            if data is not None:
                return data
        except Exception as e:  # noqa: BLE001 —— PG 任何异常都降级 JSON，绝不影响调用方
            logger.warning("pg.repo.load_fallback_json", error=str(e)[:120],
                           file=os.path.basename(path))
    else:
        logger.debug("pg.repo.read_stale_fallback_json", file=os.path.basename(path))
    if json_loader is not None:
        return json_loader()
    return _read_json(path, default)


def _json_then_pg(save_fn, path: str, data) -> None:
    """repo 写编排：JSON 原子写先行（兜底安全，现状行为），PG 可用再全量同步（best-effort：
    异常仅告警不冒泡，但置 stale 标志——此后读回落 JSON 最新数据，直到下次成功写恢复
    PG 真源；避免读改写循环用 PG 旧数据覆盖 JSON 中未同步的改动）。"""
    _write_json_atomic(path, data)
    global _repo_pg_stale
    if _pool:
        try:
            _run_pg(save_fn(data))
            _repo_pg_stale = False
        except Exception as e:  # noqa: BLE001
            _repo_pg_stale = True
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
            await c.execute("DELETE FROM users WHERE NOT (username = ANY($1))",
                            [r[0] for r in rows])
            await c.executemany(_USER_UPSERT, rows)


def load_users(path: str, json_loader=None) -> dict:
    """用户表读：PG 可用→PG 真源；不可用/异常/空表→JSON。json_loader=调用方自有 JSON 读语义。"""
    return _pg_or_json(_pg_load_users, json_loader, path, {})


def save_users(users: dict, path: str) -> None:
    """用户表写：JSON 原子写兜底 + PG 全量同步（含 dept；不在表中的 username 一并清除）。"""
    _json_then_pg(_pg_save_users, path, users)


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

async def _pg_load_queue() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch(f"""SELECT {','.join(_REVIEW_COLS)}, images
            FROM review_queue ORDER BY ts, id""")  # noqa: S608 (固定列名)
    if not rows:
        return None
    out = []
    for r in rows:
        d = {k: r[k] for k in _REVIEW_COLS}
        img = r["images"]
        d["images"] = json.loads(img) if img else None
        meta = r["meta"]
        d["meta"] = json.loads(meta) if isinstance(meta, str) and meta else meta
        src = d.get("sources")  # 任务3：sources jsonb → list（兼容驱动直返 dict）
        d["sources"] = json.loads(src) if isinstance(src, str) and src else src
        out.append(d)
    return out


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


async def _pg_load_consults() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM consults ORDER BY data->>'created_at', id")
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


def load_consults(path: str, json_loader=None) -> list:
    """会诊单读：PG 可用→PG 真源；不可用/异常/空表→JSON（调用方 json_loader 自有重试语义）。"""
    return _pg_or_json(_pg_load_consults, json_loader, path, [])


def save_consults(items: list, path: str) -> None:
    _json_then_pg(_pg_save_consults, path, items)


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
        if isinstance(items, list) and items and not await c.fetchval("SELECT count(*) FROM review_queue"):
            rows = [_review_row(i) for i in items if isinstance(i, dict)]
            if rows:
                await c.executemany(_REVIEW_UPSERT, rows)
                out["review_queue"] = len(rows)
        # llm_providers
        from backend.core.llm_config import LLM_CONFIG_FILE
        cfg = _read_json(LLM_CONFIG_FILE, {})
        rows = _llm_rows_from_cfg(cfg if isinstance(cfg, dict) else {})
        if rows and not await c.fetchval("SELECT count(*) FROM llm_providers"):
            await c.executemany(_LLM_IMPORT, rows)
            out["llm_providers"] = len(rows)
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
