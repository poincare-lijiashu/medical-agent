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


# ---------- 任务2：显式离线模式（PG_OFFLINE=on） ----------
# 场景：eval/脚本/单测等非服务进程没有 PG 池（从不调 init），此前每次读都会打
# read_stale_fallback_json 告警（logs/app.log 7269 行几乎全是它刷屏）。显式离线模式下
# pg_store 纯静默 JSON：init 直接跳过、JSON 是唯一真源、不置 stale、不告警。
_TRUTHY = ("on", "1", "true", "yes")


def offline_mode() -> bool:
    """PG_OFFLINE 环境变量解析（on/1/true/yes 均可，大小写不敏感）。"""
    return (os.environ.get("PG_OFFLINE") or "").strip().lower() in _TRUTHY


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
            # 任务1 根修：任务3 全交互留痕的 sources 列旧库平滑升级——旧库缺列会让
            # review UPSERT（$17::jsonb sources）每次全量同步确定性失败
            # （UndefinedColumnError: column "sources" does not exist），stale 窗口内
            # 读回落 JSON，一旦被其它域成功写"洗白"stale，JSON 独有记录即从 PG 真源读
            # 中"消失"（运行环境实测 imaging 留痕丢失根因，重启后本行自动补列）。
            await c.execute("ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS sources jsonb")
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
        return await _arun(_purge_drug_impl())
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
    空表 → JSON（现状行为；stale 时 JSON 恒为最新已落盘数据，读它保证不回滚）。
    任务2：PG_OFFLINE=on → 直接 JSON 纯静默（不告警）；无池/stale 读回落首次 WARN
    后同 file 去重静默（DEBUG）。"""
    domain = os.path.basename(path)
    if offline_mode():
        # 显式离线模式：JSON 是唯一真源，读它不是异常窗口，绝不告警
        if json_loader is not None:
            return json_loader()
        return _read_json(path, default)
    if _pool and domain not in _repo_pg_stale:
        try:
            data = _run_pg(load_fn())
            if data is not None:
                return data
        except Exception as e:  # noqa: BLE001 —— PG 任何异常都降级 JSON，绝不影响调用方
            logger.warning("pg.repo.load_fallback_json", error=str(e)[:120],
                           file=os.path.basename(path))
    else:
        # 任务1：stale 读回落从 debug 升级为 warning——这是"数据暂存 JSON 未同步 PG"
        # 的异常窗口，运维必须可在日志中看到（写失败时已有 save_pg_failed 告警配对）。
        # 任务2：同 file 首次 WARN 后去重静默（DEBUG），stale 清除后重置。
        _warn_stale_fallback_once(domain)
    if json_loader is not None:
        return json_loader()
    return _read_json(path, default)


def _json_then_pg(save_fn, path: str, data) -> None:
    """repo 写编排：JSON 原子写先行（兜底安全，现状行为），PG 可用再全量同步（best-effort：
    异常仅告警不冒泡，但置**本域** stale 标志——此后该域读回落 JSON 最新数据，直到该域
    下次成功写恢复 PG 真源；其它域的 stale 状态不受影响（任务1 域级隔离））。
    任务2：PG_OFFLINE=on → 仅 JSON 落盘（不置 stale 不告警）；成功写清除本域 stale 的
    同时重置该 file 的告警去重位（再次 stale 时重新 WARN 一次）。"""
    _write_json_atomic(path, data)
    domain = os.path.basename(path)
    if offline_mode():
        return
    if _pool:
        try:
            _run_pg(save_fn(data))
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


# ---- 阶段2.1：智能开药处方（data jsonb 全量留痕，created_at 内嵌 data 中，读时按其排序还原追加顺序） ----

_RX_UPSERT = """INSERT INTO prescriptions(id,data,doctor,status) VALUES($1,$2::jsonb,$3,$4)
   ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data, doctor=EXCLUDED.doctor,
   status=EXCLUDED.status, updated_at=now()"""


async def _pg_load_prescriptions() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM prescriptions ORDER BY data->>'created_at', id")
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
    """运行时开关写：JSON 原子写兜底 + PG 全量同步（不在键集的一并清除）。"""
    _json_then_pg(_pg_save_runtime_flags, path, d)


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
