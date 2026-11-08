"""admin 审计流端点（第 109 批从 admin.py 拆出，保持单文件 <600 行护栏）。

只读两端点：
- ``GET /admin/audit``：按 actor / event_type / payload.action / 日期区间过滤后分页，
  返回一页 + **过滤后**总数（前端"共 N 条"与下一页禁用都以此为准）；
- ``GET /admin/audit/export``：同一套过滤条件的全量 CSV（≤10,000 行，超限显式告知），
  导出本身是合规敏感操作 → 服务端留痕。

数据源：PG ``audit_log`` 优先；未建池/异常/超时回落 JSONL（AuditLog 有界缓存），
两条数据源使用同一过滤口径（精确匹配 + 半开时间窗），前端对数据源透明。
路由挂载：``admin.router.include_router(audit.router)``——位置在 ``/admin/data`` 之前，
保持既有路由注册顺序快照（tests/test_router_split_guard.py）。
"""
from __future__ import annotations

import asyncio
import json
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Response

from backend.api.deps import require_role
from backend.api.v1.medical import shared as _shared
from backend.core import pg_store

# rate_limit 由父路由 admin.router 统一声明（include 时叠加），此处不重复挂载，
# 避免一次请求被计两次限流。
router = APIRouter()

_AUDIT_PAGE_SIZE = 50
# 第 109 批：全量导出上限。合规抽查常见"某人某段时间全部操作"，但一次性把百万行塞进
# 一个响应会打爆内存与浏览器；上限内全量、超限截断并用响应头显式告知（不静默截断）。
_AUDIT_EXPORT_MAX = 10000


def _audit_filters(actor: str, event: str, action: str) -> tuple[str, str, str]:
    """第 108 批：审计过滤参数归一（trim + 64 字上限，防超长参数打崩 SQL/缓存）。"""
    return ((actor or "").strip()[:64], (event or "").strip()[:64],
            (action or "").strip()[:64])


def _audit_window(from_date: str, to_date: str) -> tuple[datetime | None, datetime | None]:
    """第 109 批：日期区间（含首尾日）→ 半开时间窗 [start, end)。

    输入只接受 ``YYYY-MM-DD``（前端原生 date input 即此格式）；按**服务端本地时区**
    解释为当天 00:00，截止日 end 取次日 00:00——这样"11-01 ~ 11-07"含 7 日全天，
    且相邻区间不重不漏。非法格式/起止倒置 → 400（不静默忽略，避免导出范围不符预期）。
    """
    def _parse(v: str) -> date | None:
        s = (v or "").strip()
        if not s:
            return None
        try:
            return datetime.strptime(s, "%Y-%m-%d").date()
        except ValueError:
            raise HTTPException(400, "日期格式应为 YYYY-MM-DD（如 2026-11-01）") from None
    d1, d2 = _parse(from_date), _parse(to_date)
    if d1 and d2 and d1 > d2:
        raise HTTPException(400, "起始日期不能晚于截止日期")
    tz = datetime.now().astimezone().tzinfo
    start = datetime.combine(d1, datetime.min.time(), tzinfo=tz) if d1 else None
    end = (datetime.combine(d2 + timedelta(days=1), datetime.min.time(), tzinfo=tz)
           if d2 else None)
    return start, end


def _audit_match(e: dict, actor_f: str, event_f: str, action_f: str,
                 start: datetime | None, end: datetime | None) -> bool:
    """JSONL 回落侧的同口径匹配（精确 actor/event/action + 半开时间窗）。"""
    p = e.get("payload") or {}
    if actor_f and e.get("actor") != actor_f:
        return False
    if event_f and e.get("event_type") != event_f:
        return False
    if action_f and p.get("action") != action_f:
        return False
    if start is None and end is None:
        return True
    try:
        t = datetime.fromisoformat(str(e.get("ts", "")))
    except ValueError:
        return False  # 时间戳损坏的行无法判断区间：排除而非猜测
    if t.tzinfo is None:
        t = t.replace(tzinfo=timezone.utc)
    return (start is None or t >= start) and (end is None or t < end)


def _audit_jsonl_rows(actor_f: str, event_f: str, action_f: str,
                      start: datetime | None, end: datetime | None) -> list:
    """JSONL 回落：先过滤、再倒序（entries 为时间正序），与 PG 侧同口径。"""
    entries = getattr(_shared.get_audit_logger(), "entries", []) or []
    return [e for e in entries
            if _audit_match(e, actor_f, event_f, action_f, start, end)][::-1]


def _audit_item(e: dict) -> dict:
    return {"ts": e["ts"], "event": e["event_type"],
            "action": (e.get("payload") or {}).get("action", ""), "actor": e["actor"],
            "payload": e.get("payload", {})}  # 完整 payload 下传，供前端人话化详情


async def _audit_page(off: int, actor_f: str = "", event_f: str = "", action_f: str = "",
                      start: datetime | None = None,
                      end: datetime | None = None) -> tuple[list, int | None]:
    """第 108/109 批：审计流一页（PG 真源优先 → JSONL 回落，同一过滤口径）。

    返回 (items, total)；total 为"过滤后"总条数，PG 不可用且能算出时为 JSONL 计数，
    两者都拿不到时 None（前端回退"本页是否满页"判断下一页）。
    """
    try:
        src = await pg_store.audit_recent_pg(_AUDIT_PAGE_SIZE, offset=off, actor=actor_f,
                                             event_type=event_f, action=action_f,
                                             start=start, end=end)
    except Exception:  # noqa: BLE001 —— 数据源切换绝不影响面板主数据
        src = None
    if src is None:
        matched = _audit_jsonl_rows(actor_f, event_f, action_f, start, end)
        src = matched[off:off + _AUDIT_PAGE_SIZE]
        total: int | None = len(matched)
    else:
        total = await pg_store.audit_count_pg(actor=actor_f, event_type=event_f,
                                               action=action_f, start=start, end=end)
    return [_audit_item(e) for e in src], total


def _csv_cell(v) -> str:
    """CSV 单元格：引号转义 + 防 Excel 公式注入（= + - @ 开头前置单引号）。

    审计字段含用户输入（用户名、payload），不处理的话 `=cmd|...` 会在 Excel 打开时
    被当成公式执行——这是导出功能常见的安全缺口，服务端导出必须自己兜住。"""
    s = "" if v is None else str(v)
    if s[:1] in ("=", "+", "-", "@"):
        s = "'" + s
    return '"' + s.replace('"', '""') + '"'


@router.get("/admin/audit")
async def admin_audit(user: dict = Depends(require_role("admin")), audit_offset: int = 0,
                      audit_actor: str = "", audit_event: str = "", audit_action: str = "",
                      audit_from: str = "", audit_to: str = ""):
    """第 108/109 批：审计流专用分页端点（只读）。

    为什么独立：数据面板 /admin/data 还带用户表、复核统计、Milvus 探针等重活；审计过滤
    输入框每敲一次都拉全量面板是浪费。此端点只查审计一页 + 过滤后总数，前端过滤/翻页
    走它，首屏与刷新仍走 /admin/data。参数与回落语义与 /admin/data 完全一致。
    audit_from/audit_to（YYYY-MM-DD，含首尾日）为第 109 批新增时间区间。
    """
    off = max(0, audit_offset)
    actor_f, event_f, action_f = _audit_filters(audit_actor, audit_event, audit_action)
    start, end = _audit_window(audit_from, audit_to)
    items, total = await _audit_page(off, actor_f, event_f, action_f, start, end)
    return {"items": items, "total": total, "offset": off, "limit": _AUDIT_PAGE_SIZE,
            "filters": {"actor": actor_f, "event": event_f, "action": action_f,
                        "from": (audit_from or "").strip(), "to": (audit_to or "").strip()}}


@router.get("/admin/audit/export")
async def admin_audit_export(user: dict = Depends(require_role("admin")),
                             audit_actor: str = "", audit_event: str = "",
                             audit_action: str = "", audit_from: str = "",
                             audit_to: str = ""):
    """第 109 批：按当前过滤条件导出审计 CSV（最多 10,000 行，超限显式告知）。

    与分页端点同一套过滤（精确 actor/event/action + 半开时间窗）；PG 不可用回落 JSONL。
    导出本身是合规敏感操作 → 服务端留痕（event=admin, action=audit_export），记录
    过滤条件与行数；留痕写在读取之后，因此本次导出事件不会出现在导出文件里。
    """
    actor_f, event_f, action_f = _audit_filters(audit_actor, audit_event, audit_action)
    start, end = _audit_window(audit_from, audit_to)
    try:
        rows = await pg_store.audit_recent_pg(_AUDIT_EXPORT_MAX + 1, offset=0, actor=actor_f,
                                              event_type=event_f, action=action_f,
                                              start=start, end=end)
    except Exception:  # noqa: BLE001
        rows = None
    if rows is None:
        rows = _audit_jsonl_rows(actor_f, event_f, action_f, start, end)[:_AUDIT_EXPORT_MAX + 1]
    truncated = len(rows) > _AUDIT_EXPORT_MAX
    rows = rows[:_AUDIT_EXPORT_MAX]
    head = ["时间", "事件", "动作", "操作者", "payload"]
    lines = [",".join(_csv_cell(h) for h in head)]
    lines += [",".join(_csv_cell(x) for x in (
        e.get("ts", ""), e.get("event_type", ""), (e.get("payload") or {}).get("action", ""),
        e.get("actor", ""), json.dumps(e.get("payload") or {}, ensure_ascii=False),
    )) for e in rows]
    body = "\ufeff" + "\r\n".join(lines) + "\r\n"  # BOM：Excel 中文不乱码
    actor_name = user.get("username", "admin")
    filters = {"actor": actor_f, "event": event_f, "action": action_f,
               "from": (audit_from or "").strip(), "to": (audit_to or "").strip()}
    await asyncio.to_thread(_write_export_audit, actor_name, filters, len(rows), truncated)
    await pg_store.mirror_audit("admin.audit_export", actor_name,
                                {"filters": filters, "rows": len(rows), "truncated": truncated})
    filename = "audit_" + datetime.now().strftime("%Y%m%d_%H%M%S") + ".csv"
    return Response(content=body, media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="{filename}"',
                             "X-Export-Rows": str(len(rows)),
                             "X-Export-Truncated": "1" if truncated else "0"})


def _write_export_audit(actor_name: str, filters: dict, rows: int, truncated: bool) -> None:
    """导出留痕（同步函数交给 asyncio.to_thread；请求时解析 shared 模块属性，便于测试打桩）。"""
    _shared.get_audit_logger().write(event_type="admin", action="audit_export",
                                     actor=actor_name,
                                     payload={"filters": filters, "rows": rows,
                                              "truncated": truncated})
