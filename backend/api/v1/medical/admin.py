"""admin 管理与运维端点（W4.3 router 拆分）：config/概览/用户/科室/数据面板/日志/LLM 配置。"""
from __future__ import annotations

import asyncio
import os
import re
import time

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator

from backend.api.deps import rate_limit, require_role, require_user, revoke_user
from backend.api.v1.medical import shared as _shared
from backend.config import settings
from backend.core import llm_config, runtime_flags
from backend.core import pg_store
from backend.core import medical_review as review

router = APIRouter(dependencies=[Depends(rate_limit)])

_START = time.time()

# 第 98 批：基础设施探针短缓存（默认 5s）。
# 为什么：Milvus TCP 预检与 doc_count 各自 fail-fast ~500ms；本地实测 /admin/data
# 约 1038ms，其中 ~1000ms 是这两次探针（业务数据只占 ~5ms）。面板刷新/多人同时打开
# 会反复付这份代价。状态类信息对 5s 陈旧不敏感，缓存后热路径 ~5ms。
_INFRA_TTL_S = 5.0
_milvus_cache: dict = {"at": 0.0, "value": None}
_kb_docs_cache: dict = {"at": 0.0, "value": None}


def _reset_admin_infra_cache() -> None:
    """清空探针缓存（单测隔离用）。"""
    _milvus_cache.update(at=0.0, value=None)
    _kb_docs_cache.update(at=0.0, value=None)


async def _cached_milvus(milvus_status) -> dict:
    """Milvus 状态（5s TTL；探针异常降级为"不可用"）。"""
    now = time.monotonic()
    if _milvus_cache["value"] is not None and (now - _milvus_cache["at"]) < _INFRA_TTL_S:
        return _milvus_cache["value"]
    try:
        value = await asyncio.to_thread(milvus_status)
    except Exception:  # noqa: BLE001 —— 状态探针绝不影响面板主数据
        value = {"connected": False, "uri": ""}
    _milvus_cache.update(at=now, value=value)
    return value


async def _cached_kb_docs(doc_count) -> int:
    """知识库文档数（5s TTL；Milvus 冷启动/不可达时单次 ~500ms）。"""
    now = time.monotonic()
    if _kb_docs_cache["value"] is not None and (now - _kb_docs_cache["at"]) < _INFRA_TTL_S:
        return _kb_docs_cache["value"]
    try:
        value = await asyncio.to_thread(doc_count)
    except Exception:  # noqa: BLE001
        value = -1
    _kb_docs_cache.update(at=now, value=value)
    return value


async def _review_stats() -> dict:
    """复核状态统计：PG 聚合优先；无池/离线/空表/异常 → JSON **全表**计数。

    第 99 批：回落分支不再用 `list_all()`（默认只取最新 100 条，会严重少计）。"""
    counts = await asyncio.to_thread(review.status_counts)
    if counts is None:
        counts = await asyncio.to_thread(review.status_counts_memory)
    dist = counts.get("counts") or {}
    return {"pending": int(counts.get("pending", 0) or 0),
            "approved": int(dist.get("approved", 0) or 0),
            "rejected": int(dist.get("rejected", 0) or 0)}


@router.get("/config")
async def get_config(user: dict = Depends(require_user)):
    """前端据此动态显示真实模型名（不再硬编码）。FIND-15：管理端激活 provider 的 model_id
    优先（展示与实跑一致），无配置回落 .env 内置值。仅返回模型名，不回显密钥。"""
    chat = await asyncio.to_thread(llm_config.active_provider, "chat")
    vision = await asyncio.to_thread(llm_config.active_provider, "vision")
    from backend.core.medical_kb import milvus_status
    milvus = await asyncio.to_thread(milvus_status)
    return {"chat_model": (chat or {}).get("model_id") or settings.qwen_model_chat,
            "vl_model": (vision or {}).get("model_id") or settings.qwen_model_vl,
            "reasoning_model": settings.qwen_model_reasoning,
            "milvus": milvus,
            # 任务3：前端据此切换审核中心形态（True=留痕模式：qc/admin 全局只读档案）。
            # runtime_flags 优先（admin 运行时切换即时生效），无键回落 settings 启动值。
            "qc_auto_sign_full": runtime_flags.full_mode()}


class ConfigToggleReq(BaseModel):
    key: str = Field(max_length=64)
    value: bool | None = None  # 缺省=在当前值上取反（toggle）


@router.post("/admin/config-toggle")
async def admin_config_toggle(req: ConfigToggleReq, user: dict = Depends(require_role("admin"))):
    """任务3：admin 运行时切换行为开关（如「留痕模式」qc_auto_sign_full）。

    写 data/runtime_flags.json（backend/core/runtime_flags.py 白名单键）——决策点
    （决策点 _enqueue_if_risk（drug 类豁免，本批次任务2）/ GET /config）经
    runtime_flags.full_mode() 读值，
    flags 文件优先于启动时 settings，切换即时生效、无需重启。admin 权限；审计留痕。
    """
    if req.key not in runtime_flags.ALLOWED_FLAGS:
        raise HTTPException(400, f"不支持的运行时开关：{req.key}")
    cur = runtime_flags.full_mode() if req.key == "qc_auto_sign_full" else False
    new_val = (not cur) if req.value is None else bool(req.value)
    val = await asyncio.to_thread(runtime_flags.set_flag, req.key, new_val)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="config_toggle",
                            actor=user.get("username", "admin"),
                            payload={"key": req.key, "from": cur, "to": val})
    await pg_store.mirror_audit("admin.config_toggle", user.get("username", "admin"),
                                {"key": req.key, "value": val})
    return {"ok": True, "key": req.key, "value": val}


@router.get("/overview")
async def overview(user: dict = Depends(require_user)):
    """概览面板：真实运行与合规指标（不编造）。"""
    from backend.core.embedder import device
    from backend.core.medical_kb import doc_count
    stats = await _review_stats()
    pending, resolved = stats["pending"], stats["approved"] + stats["rejected"]
    al = _shared.get_audit_logger()
    # 任务1：recent 携带完整 payload，前端 humanizeAudit 生成人话描述（仅 event 名不可读）
    recent = [{"ts": e["ts"][11:19], "event": e["event_type"],
               "action": e["payload"].get("action", ""), "actor": e["actor"],
               "payload": e.get("payload", {})}
              for e in al.recent(8)]
    kb_docs = await _cached_kb_docs(doc_count)  # Milvus 冷启动可能慢，5s 短缓存 + 移出事件循环
    return {
        "kb_docs": kb_docs, "pending": pending, "resolved": resolved,
        "events_total": len(al.entries), "device": device(),
        "uptime_s": int(time.time() - _START), "version": settings.app_version,
        "env": settings.app_env,
        "compliance": {"auth": True, "phi_redact": True, "audit_append": True,
                       "dual_control": True, "data_local": True, "hybrid_rerank": True},
        "recent": recent,
    }


# ---------- admin 用户管理 ----------
class UserCreateReq(BaseModel):
    username: str
    password: str
    role: str
    dept: str = Field(default="", max_length=40)  # 所属科室（质控签名/科室绑定用）


@router.post("/admin/users")
async def admin_create_user(body: UserCreateReq, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import create_user, get_user
    from backend.core.departments import validate_role_dept
    uname = (body.username or "").strip()
    if not (3 <= len(uname) <= 32) or not re.fullmatch(r"[A-Za-z0-9_\-]+", uname):
        raise HTTPException(400, "用户名需 3-32 位，仅字母/数字/下划线/连字符")
    if len(body.password or "") < 8:
        raise HTTPException(400, "密码至少 8 位")
    # 整改轮 B 任务3（科室-角色归属模型）：建号时校验角色×科室匹配（存量账号不追溯），
    # 违反 → 422 中文文案（语义变更：此前 dept 不做角色约束，任意组合可入库）。
    try:
        validate_role_dept(body.role, (body.dept or "").strip())
    except ValueError as e:
        raise HTTPException(422, str(e))
    try:
        ok = await asyncio.to_thread(create_user, uname, body.password, body.role, False,
                                     (body.dept or "").strip())
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(400, "用户名已存在")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="user_created",
                            actor=user.get("username", "admin"),
                            payload={"user": uname, "role": body.role,
                                     "dept": (body.dept or "").strip()})
    await pg_store.mirror_audit("admin.user_created", user.get("username", "admin"), {"user": uname})
    rec = await asyncio.to_thread(get_user, uname)
    if rec:
        await pg_store.mirror_user(uname, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}


@router.delete("/admin/users/{username}")
async def admin_delete_user(username: str, user: dict = Depends(require_role("admin"))):
    from backend.core.auth import delete_user
    if username == user.get("username"):
        raise HTTPException(400, "不能删除当前登录的账号")
    ok = await asyncio.to_thread(delete_user, username)
    if not ok:
        raise HTTPException(404, "用户不存在")
    revoke_user(username)  # 使其已签发的令牌立即失效
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="user_deleted",
                            actor=user.get("username", "admin"), payload={"user": username})
    await pg_store.mirror_audit("admin.user_deleted", user.get("username", "admin"), {"user": username})
    return {"ok": True}


# ---------- admin 科室管理（质控签名/科室绑定的取值来源）----------
class DeptReq(BaseModel):
    name: str = Field(min_length=1, max_length=40)


@router.get("/admin/departments")
async def admin_list_departments(user: dict = Depends(require_role("admin"))):
    from backend.core import departments
    return {"list": await asyncio.to_thread(departments.list_departments)}


@router.post("/admin/departments")
async def admin_add_department(req: DeptReq, user: dict = Depends(require_role("admin"))):
    from backend.core import departments
    try:
        ok = await asyncio.to_thread(departments.add_department, req.name)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(400, "科室已存在")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="department_added",
                            actor=user.get("username", "admin"), payload={"name": req.name.strip()})
    return {"ok": True}


@router.delete("/admin/departments/{name}")
async def admin_delete_department(name: str, user: dict = Depends(require_role("admin"))):
    """name 走 path（前端 encodeURIComponent），FastAPI 自动百分号解码，中文名安全。"""
    from backend.core import departments
    try:
        ok = await asyncio.to_thread(departments.remove_department, name)
    except ValueError as e:  # 仍有账号绑定该科室 → 400，前端提示先调整账号
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(404, "科室不存在")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="department_removed",
                            actor=user.get("username", "admin"), payload={"name": name})
    return {"ok": True}


# ---------- admin 运维：数据面板 / 队列清理（只读优先，不做裸 SQL） ----------
_AUDIT_PAGE_SIZE = 50


def _audit_filters(actor: str, event: str, action: str) -> tuple[str, str, str]:
    """第 108 批：审计过滤参数归一（trim + 64 字上限，防超长参数打崩 SQL/缓存）。"""
    return ((actor or "").strip()[:64], (event or "").strip()[:64],
            (action or "").strip()[:64])


async def _audit_page(off: int, actor_f: str = "", event_f: str = "",
                      action_f: str = "") -> tuple[list, int | None]:
    """第 108 批：审计流一页（PG 真源优先 → JSONL 回落，同一过滤口径）。

    返回 (items, total)；total 为"过滤后"总条数，PG 不可用且能算出时为 JSONL 计数，
    两者都拿不到时 None（前端回退"本页是否满页"判断下一页）。
    """
    al = _shared.get_audit_logger()
    try:
        src = await pg_store.audit_recent_pg(_AUDIT_PAGE_SIZE, offset=off, actor=actor_f,
                                             event_type=event_f, action=action_f)
    except Exception:  # noqa: BLE001 —— 数据源切换绝不影响面板主数据
        src = None
    if src is None:
        def _match(e: dict) -> bool:
            p = e.get("payload") or {}
            return ((not actor_f or e.get("actor") == actor_f)
                    and (not event_f or e.get("event_type") == event_f)
                    and (not action_f or p.get("action") == action_f))
        # 过滤后分页（与 PG 侧同口径；entries 为时间正序，倒序后切片）
        entries = getattr(al, "entries", []) or []
        matched = [e for e in entries if _match(e)][::-1]
        src = matched[off:off + _AUDIT_PAGE_SIZE]
        total: int | None = len(matched)
    else:
        total = await pg_store.audit_count_pg(actor=actor_f, event_type=event_f, action=action_f)
    items = [{"ts": e["ts"], "event": e["event_type"],
              "action": (e.get("payload") or {}).get("action", ""), "actor": e["actor"],
              "payload": e.get("payload", {})}  # 完整 payload 下传，供前端人话化详情
             for e in src]
    return items, total


@router.get("/admin/audit")
async def admin_audit(user: dict = Depends(require_role("admin")), audit_offset: int = 0,
                      audit_actor: str = "", audit_event: str = "", audit_action: str = ""):
    """第 108 批：审计流专用分页端点（只读）。

    为什么独立：数据面板 /admin/data 还带用户表、复核统计、Milvus 探针等重活；审计过滤
    输入框每敲一次都拉全量面板是浪费。此端点只查审计一页 + 过滤后总数，前端过滤/翻页
    走它，首屏与刷新仍走 /admin/data。参数与回落语义与 /admin/data 完全一致。
    """
    off = max(0, audit_offset)
    actor_f, event_f, action_f = _audit_filters(audit_actor, audit_event, audit_action)
    items, total = await _audit_page(off, actor_f, event_f, action_f)
    return {"items": items, "total": total, "offset": off, "limit": _AUDIT_PAGE_SIZE,
            "filters": {"actor": actor_f, "event": event_f, "action": action_f}}


@router.get("/admin/data")
async def admin_data(user: dict = Depends(require_role("admin")), audit_offset: int = 0,
                     audit_actor: str = "", audit_event: str = "", audit_action: str = ""):
    """管理员只读数据面板：用户、审计流、队列统计、PG 镜像与知识库规模、基础设施状态。

    audit_offset（任务A）：审计流翻页偏移（每页 50，与前端「上一页/下一页」配合）；
    负数按 0 处理，绝不因越界参数 500。
    第 107 批：审计流可按 actor / event_type / payload.action 精确过滤（先过滤再分页）。
    第 108 批：过滤/分页逻辑抽到 _audit_page（与 /admin/audit 共享），并增返 audit_total。
    """
    from backend.core.auth import all_users
    from backend.core.medical_kb import doc_count, milvus_status
    from backend.core.embedder import device
    users = [{"username": u, "role": rec.get("role", ""), "created": rec.get("created", ""),
              "dept": rec.get("dept", "")}
             for u, rec in (await asyncio.to_thread(all_users)).items()]
    # 第 98/99 批：状态统计走 PG `GROUP BY status`（无池 → JSON 全表计数），不再读
    # 最新 100 条估算——1168 行队列下旧实现把 pending 显示成 3（真实 76）。
    stats = await _review_stats()
    al = _shared.get_audit_logger()
    # 任务5：审计流数据源切换为 PG audit_log 真源（audit_recent_pg 按 ts 倒序分页，返回
    # 与 JSONL recent 相同结构 [{ts,event_type,actor,payload}]）；PG 未建池/异常/超时
    # → 回落 JSONL 现状（AuditLog 有界缓存），前端审计流/翻页/导出对数据源透明。
    # F5：保留完整 ISO 串（含 +00:00 偏移），fmtTs 依赖时区信息做本地化。
    off = max(0, audit_offset)
    actor_f, event_f, action_f = _audit_filters(audit_actor, audit_event, audit_action)
    audit_recent, audit_total = await _audit_page(off, actor_f, event_f, action_f)
    try:
        pg_counts = await pg_store.counts()
    except Exception:  # noqa: BLE001
        pg_counts = {}
    # 基础设施只读状态（前端运维卡片）：milvus TCP 预检 + kb 文档数走 5s 短缓存
    # （两者各自 fail-fast ~500ms，是面板延迟的主要来源）；pg SELECT 1 探针开销 ~1ms 不缓存。
    # 安全红线：只报状态/命令文本供展示与复制，本端点绝不提供任何执行/重启能力。
    milvus = await _cached_milvus(milvus_status)
    kb_docs = await _cached_kb_docs(doc_count)
    try:
        pg_ok = await pg_store.ping()
    except Exception:  # noqa: BLE001
        pg_ok = False
    return {"users": users, "audit_recent": audit_recent, "audit_total": audit_total,
            "review": stats,
            "pg_counts": pg_counts, "kb_docs": kb_docs,
            "events_total": len(al.entries), "device": device(), "uptime_s": int(time.time() - _START),
            "infra": {"milvus": milvus, "pg": {"ok": pg_ok},
                      # 第 47 批：模式 + 按域 stale（写失败回落 JSON）——多副本漂移早期信号
                      "storage": pg_store.storage_status()},
            # 任务3：admin「留痕模式」开关卡数据源（runtime_flags 优先，运行时切换即时反映）
            "flags": {"qc_auto_sign_full": runtime_flags.full_mode()}}


class PurgeReq(BaseModel):
    confirm_text: str = ""  # 必须为「清空」，服务端强制校验，杜绝前端误触/绕过


@router.post("/admin/review/purge-pending")
async def admin_purge_pending(body: PurgeReq, user: dict = Depends(require_role("admin"))):
    """清空全部待核对项（保留已处理历史），用于清理测试数据。需确认词「清空」。"""
    if (body.confirm_text or "").strip() != "清空":
        raise HTTPException(400, "确认词不符：请输入「清空」以确认执行")
    n = await asyncio.to_thread(review.purge_pending)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="review", action="purge_pending",
                            actor=user.get("username", "admin"),
                            payload={"count": n, "role": user.get("role", "")})
    await pg_store.mirror_audit("review.purge_pending", user.get("username", "admin"), {"count": n})
    await pg_store.purge_pending_reviews()
    return {"purged": n}


# ---------- admin 运维：应用日志 tail（任务5，只读） + 日志健康卡元数据（本批次） ----------
def _read_log_tail(n: int) -> tuple[str, list[str], str, dict]:
    """同步读 logs/app.log 尾部 n 行（RotatingFileHandler 主文件，与 logger.py 同源路径）。
    只读末尾 512KB 窗口防大文件全量载入；窗口截断的首个残行丢弃；总行数按 1MB 分块
    全文件扫描计数（轮转上限 5MB，admin 低频调用可接受）。
    返回 (文件绝对路径, 行列表[旧→新], 提示语, 元数据)；元数据 {path, size_bytes,
    exists, total_lines} 供前端「日志健康卡」展示；文件不存在 → 提示
    「应用日志未启用（LOG_TO_FILE）」且元数据归零。"""
    # 第 72 批：多进程模式下主文件是 app-<pid>.log（取最新一份），单文件模式仍是 app.log
    from backend.core.logger import _FILE_NAME, resolve_log_path
    log_dir = (settings.log_dir or "logs").strip() or "logs"
    path = os.path.abspath(resolve_log_path(log_dir))  # 绝对路径（健康卡展示）
    multi = os.path.basename(path) != _FILE_NAME
    meta = {"path": path, "size_bytes": 0, "exists": False, "total_lines": 0}
    if not os.path.isfile(path):
        return path, [], "应用日志未启用（LOG_TO_FILE）", meta
    size = os.path.getsize(path)
    meta.update(exists=True, size_bytes=size)
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        window = min(size, 512 * 1024)
        f.seek(size - window)
        raw = f.read(window)
        f.seek(0)
        meta["total_lines"] = sum(chunk.count(b"\n")
                                  for chunk in iter(lambda: f.read(1024 * 1024), b""))
    all_lines = raw.decode("utf-8", errors="replace").splitlines()
    if window < size and all_lines:
        all_lines = all_lines[1:]  # 丢弃窗口起点处可能被截断的残行
    prefix = f"多进程日志 {os.path.basename(path)}；" if multi else ""
    return path, all_lines[-n:], f"{prefix}共 {min(len(all_lines), n)} 行（最新在底部）", meta


@router.get("/admin/logs")
async def admin_logs(lines: int = 200, user: dict = Depends(require_role("admin"))):
    """任务5：应用日志尾部只读视图（仅 admin）。lines=N 可调（1~500 上限截断，默认 200）。

    本批次新增日志健康卡元数据：path（绝对路径）/ size_bytes / exists / total_lines，
    与既有 file/lines/note 并存（向后兼容）。安全边界：只读主日志文件（app.log），
    不提供任何下载/删除/执行能力；日志内容可能含用户输入，前端必须逐行 esc() 后
    渲染（防注入）。文件不存在/未启用返回提示不报错。
    """
    n = max(1, min(int(lines or 200), 500))
    try:
        path, tail, note, meta = await asyncio.to_thread(_read_log_tail, n)
    except OSError as e:  # 读取失败（权限/占用等）显式 500 带原因，不静默吞掉
        raise HTTPException(500, f"日志文件读取失败：{e}")
    return {"file": path, "lines": tail, "note": note, **meta}


# ---------- admin 模型配置管理（F6：对话/视觉双通道，openai/anthropic 双格式）----------
class LlmProviderReq(BaseModel):
    kind: str                       # chat | vision
    api_format: str                 # openai | anthropic
    base_url: str = Field(max_length=2000)
    model_id: str = Field(max_length=200)
    display_name: str = Field(default="", max_length=200)
    api_key: str = Field(default="", max_length=2000)
    # 任务2 额外参数逃生舱：厂商特殊参数（JSON 字符串，如 {"thinking":{"type":"high"}}）。
    # 空串合法（=无额外参数）；非空必须是 JSON object，否则 422（pydantic 校验层拦截）。
    extra_params: str = Field(default="", max_length=4000)

    @field_validator("extra_params")
    @classmethod
    def _validate_extra_json(cls, v: str) -> str:
        from backend.core.llm_config import parse_extra_params
        if (v or "").strip() and not parse_extra_params(v):
            raise ValueError("extra_params 需为合法的 JSON 对象字符串，如 {\"thinking\":{\"type\":\"high\"}}")
        return (v or "").strip()


class LlmActivateReq(BaseModel):
    kind: str
    pid: str = Field(max_length=64)


def _reset_llm_cache() -> None:
    """F6：provider 增删/激活后必须重建模型缓存，否则 LLMFactory 仍复用旧实例。"""
    from backend.core.llm_factory import LLMFactory
    LLMFactory._instances.clear()


def _masked_providers() -> dict:
    """读配置并把 api_key 脱敏（前6后4中***，空则空串），供 GET 响应。"""
    cfg = llm_config.load_config()
    return {kind: {"active": (cfg.get(kind) or {}).get("active"),
                   "providers": [{**p, "api_key": llm_config.mask_key(p.get("api_key", ""))}
                                 for p in (cfg.get(kind) or {}).get("providers", [])]}
            for kind in ("chat", "vision")}


@router.get("/admin/llm/providers")
async def llm_providers(user: dict = Depends(require_role("admin"))):
    return _masked_providers()


@router.post("/admin/llm/providers")
async def llm_add_provider(req: LlmProviderReq, user: dict = Depends(require_role("admin"))):
    try:
        pid = await asyncio.to_thread(
            llm_config.add_provider, req.kind, req.api_format, req.base_url,
            req.model_id, req.display_name, req.api_key, req.extra_params)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="llm_add",
                            actor=user.get("username", "admin"), payload={"kind": req.kind, "pid": pid})
    return {"id": pid}


@router.delete("/admin/llm/providers/{kind}/{pid}")
async def llm_delete_provider(kind: str, pid: str, user: dict = Depends(require_role("admin"))):
    try:
        ok = await asyncio.to_thread(llm_config.remove_provider, kind, pid)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(404, "未找到该 provider")
    _reset_llm_cache()
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="llm_remove",
                            actor=user.get("username", "admin"), payload={"kind": kind, "pid": pid})
    return {"ok": True}


@router.post("/admin/llm/activate")
async def llm_activate(req: LlmActivateReq, user: dict = Depends(require_role("admin"))):
    try:
        await asyncio.to_thread(llm_config.activate, req.kind, req.pid)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()  # 切换后旧模型实例立即失效，下次 get_llm/get_vl_llm 按新配置重建
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="llm_activate",
                            actor=user.get("username", "admin"), payload={"kind": req.kind, "pid": req.pid})
    return {"ok": True, "active": req.pid}


class LlmDeactivateReq(BaseModel):
    kind: str


@router.post("/admin/llm/deactivate")
async def llm_deactivate(req: LlmDeactivateReq, user: dict = Depends(require_role("admin"))):
    """停用激活 provider（active 置 None），回落 .env 内置配置。"""
    try:
        await asyncio.to_thread(llm_config.deactivate, req.kind)
    except ValueError as e:
        raise HTTPException(400, str(e))
    _reset_llm_cache()  # 停用后旧模型实例立即失效
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="llm_deactivate",
                            actor=user.get("username", "admin"), payload={"kind": req.kind})
    return {"ok": True}


@router.get("/admin/llm/suggest-extra")
async def llm_suggest_extra(base_url: str = "", model_id: str = "",
                            user: dict = Depends(require_role("admin"))):
    """任务2 厂商自动预设（仅建议不落库）：按 base_url+model_id 返回建议的
    extra_params JSON 字符串（无匹配返回空串），前端据此预填「额外参数」输入框。"""
    suggested = await asyncio.to_thread(llm_config.suggest_extra_params, base_url, model_id)
    return {"suggested": suggested}


@router.post("/admin/llm/test")
async def llm_test(req: LlmProviderReq, user: dict = Depends(require_role("admin"))):
    """连通性测试（不落盘）。返回 {"ok","latency_ms","detail"}，detail 不含 api_key。
    任务2：透传 extra_params 同合并逻辑（真实验证用户填的参数可用性）。"""
    r = await asyncio.to_thread(llm_config.test_provider, req.api_format, req.base_url,
                                req.model_id, req.api_key, req.extra_params)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin", action="llm_test",
                            actor=user.get("username", "admin"),
                            payload={"kind": req.kind if req.kind in ("chat", "vision") else None,
                                     "api_format": req.api_format, "model_id": req.model_id[:80],
                                     "ok": r["ok"]})
    return r
