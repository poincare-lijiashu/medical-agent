"""跨科室会诊协助（真实会诊流转核心）。

流程：医生发起（问题+可选图片）→ Agent 动态组队（LLM 读病例+实时科室清单自主选专科，
见 agents/medical/mdt.pick_departments）→ 按科室生成定向初步意见 → 分发给目标科室
全部在职医生（users.dept 匹配）→ 各科医生在审核中心「其它科室会诊协助」填写本科室
意见（仅建议权无驳回权，每人一票）→ 发起者手动「结束会诊并汇总」→ 汇总（AI 意见+
各科医生意见并排署名）落回发起者的会诊记录。

存储走 pg_store repo 层（与 medical_review 同模式，B1 数据真源化）：
PG 池可用 → PG 真源（consults 表：id text pk, data jsonb, initiator text, status text）；
PG 不可用/异常/空表 → data/consults.json（现状行为完全等价，PermissionError 重试
与 _lock 锁语义保留在 JSON 路径）。审计由路由层另记（consult_created/consult_dispatch/
consult_opinion/consult_closed）。

科室同步纪律：本模块不做任何科室缓存；分发可达性完全由 target_depts 与 users.dept
的实时匹配决定（inbox_for/add_opinion），组队科室清单由 mdt.pick_departments 每次
实时调 departments.list_departments()（绝不缓存）。
"""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from datetime import datetime, timezone

from backend.config import settings
from backend.core import pg_store
from backend.core.medical_review import _cap_total_chars, _compress_images

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONSULTS_FILE = os.path.join(BASE_DIR, "data", "consults.json")
_lock = threading.Lock()


class ConsultError(Exception):
    """会诊业务错误（路由转 400）。"""


class ConsultPermissionError(ConsultError):
    """会诊权限错误（路由转 403）：非发起者结束 / 非受邀科室填意见等。"""


class ConsultNotFoundError(ConsultError):
    """会诊单不存在（路由转 404）。"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_json() -> list[dict]:
    """consults.json 现状读逻辑（JSON 兜底真源）。Windows 下与他人并发写（os.replace
    前）可能瞬时 PermissionError，有限重试后仍失败才抛原异常（与 medical_review 同语义；
    读路径不加锁，仅重试）。"""
    if not os.path.isfile(CONSULTS_FILE):
        return []
    for attempt in range(4):  # 首次 + 3 次重试 × 50ms
        try:
            with open(CONSULTS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except PermissionError:
            if attempt == 3:
                raise
            time.sleep(0.05)
    return []  # pragma: no cover —— 不可达（重试耗尽时已 raise）


def _load() -> list[dict]:
    # B1 数据真源化：PG 池可用 → PG 真源；不可用/异常/空表 → JSON（重试语义保留在 _load_json）
    return pg_store.load_consults(CONSULTS_FILE, _load_json)


def _save(items: list[dict]) -> None:
    # B1：JSON 原子写兜底 + PG 全量同步（best-effort）；仍运行在调用方 _lock 临界区内
    pg_store.save_consults(items, CONSULTS_FILE)


def _save_row(item: dict, items: list[dict]) -> None:
    """W4.1 行级落盘（语义同 medical_review._save_row）：PG 主模式且池可用 → 单行
    UPSERT（严格，失败抛出防分裂）+ JSON 镜像；灰度回滚/无池/离线 → 全量（行为不变）。
    会诊单是单据级变更（create/opinion/close 各改一张单），行级写根除多实例
    全量同步的 last-writer-wins 互覆。运行在调用方 _lock 临界区内。"""
    if settings.pg_primary and pg_store.has_pool():
        pg_store.upsert_consult_row(item)
        pg_store.mirror_json_file(CONSULTS_FILE, items)
    else:
        _save(items)


def _persist_new(item: dict) -> None:
    """第 88 批：新建会诊不再读全表——PG 主模式单行 UPSERT + 镜像就地追加；
    灰度回滚/无池/离线保持原「读全量 → append → 全量写」。运行在 _lock 临界区内。"""
    if settings.pg_primary and pg_store.has_pool():
        pg_store.upsert_consult_row(item)
        pg_store.mirror_json_upsert(CONSULTS_FILE, item)
        return
    items = _load()
    items.append(item)
    _save(items)


def _mutate_row(cid: str, fn, not_found) -> dict:
    """第 88 批：单条「定位 → 校验 → 变更 → 落盘」（替代读全表线性查找）。

    PG 主模式：主键直查 + 单行 UPSERT + 镜像就地更新；`(False, None)`（PG 答不了）
    或灰度回滚/无池形态 → 回落原「读全量 → 改一条 → 全量写」。not_found 由调用方
    传入（add_opinion/close 都用 ConsultNotFoundError，保持既有错误类型与文案）。
    运行在调用方 _lock 临界区内。"""
    if settings.pg_primary and pg_store.has_pool():
        found, item = pg_store.get_consult_row(cid)
        if found:
            if item is None:
                raise not_found
            fn(item)
            pg_store.upsert_consult_row(item)  # 严格：失败抛出防 PG/JSON 分裂
            pg_store.mirror_json_upsert(CONSULTS_FILE, item)
            return item
    items = _load()
    for i in items:
        if i["id"] == cid:
            fn(i)
            _save_row(i, items)
            return i
    raise not_found


def create(*, initiator: str, question: str, ai_analysis: str, target_depts: list[str],
           team: dict | None = None, images: list[str] | None = None,
           initiator_dept: str = "") -> str:
    """发起一条跨科室会诊（open），返回会诊 id。

    images 与 review 队列同纪律：压缩/截断为纯函数在锁外完成，非法图丢弃不入库；
    team 为 Agent 动态组队结果 {departments, reasoning, reason}（审计与前端展示用；
    reason=分科理由，随会诊单入库供前端「AI 分科依据」展示；旧调用方无 reason 键
    → 存空串，向后兼容）。
    """
    if not initiator:
        raise ConsultError("发起人不能为空")
    if not (question or "").strip():
        raise ConsultError("会诊问题不能为空")
    cid = "con-" + uuid.uuid4().hex[:10]
    compressed = _compress_images(images)  # 纯函数不持锁，缩短临界区
    # SSRF 收口（终评 F1）存储层兜底：HTTP 层已 422 拒绝非 data:image/，这里再过滤
    # 一道（MCP/内部调用绕过 AskReq 时仍不入库），与「非法图丢弃」既有纪律一致。
    if compressed:
        compressed = [u for u in compressed if isinstance(u, str) and u.startswith("data:image/")] or None
    stored_images = None if compressed is None else _cap_total_chars(compressed)
    with _lock:
        entry = {
            "id": cid, "initiator": initiator, "initiator_dept": initiator_dept or "",
            "question": question, "images": stored_images,
            "ai_analysis": ai_analysis or "",
            # Agent 动态组队留痕：{departments: 选中科室, reasoning: 选择理由,
            # reason: 分科理由（T1，随单入库供前端展示；旧形态无该键 → 空串向后兼容）}
            "team": ({"departments": list((team or {}).get("departments") or []),
                      "reasoning": (team or {}).get("reasoning", ""),
                      "reason": (team or {}).get("reason", "")} if team else None),
            "target_depts": [d for d in (target_depts or []) if d],
            "opinions": [],  # [{doctor, dept, content, ts}]，每人一票
            "status": "open",
            "created_at": _now(), "closed_at": None, "summary": None,
        }
        _persist_new(entry)  # 第 88 批：PG 主模式不再为新会诊读全表
    return cid


def get(cid: str) -> dict | None:
    """第 85 批：优先按主键直查 PG；PG 不可用/空表/异常 → 回落 _load() 线性查找。"""
    found, item = pg_store.get_consult_row(cid)
    if found:
        return item
    for i in _load():
        if i["id"] == cid:
            return i
    return None


def inbox_for(dept: str) -> list[dict]:
    """某科室的会诊收件箱：target_depts 含该科室且仍 open 的单，新→旧。

    实时按 dept 匹配（users.dept 即此值）：分发即达目标科室全部在职医生，无名单快照。
    第 89 批：优先 PG 条件查询（jsonb 数组包含），空表/无池/异常回落既有内存过滤。"""
    if not dept:
        return []
    rows = pg_store.consult_list(
        "status = 'open' AND data->'target_depts' @> $1::jsonb",
        (json.dumps([dept], ensure_ascii=False),))
    if rows is not None:
        return rows
    return [i for i in _load()[::-1]
            if i.get("status") == "open" and dept in (i.get("target_depts") or [])]


def by_initiator(user: str) -> list[dict]:
    """发起者视角：本人发起的全部会诊（含已结束），新→旧。
    第 89 批：优先 PG 条件查询，空表/无池/异常回落内存过滤。"""
    rows = pg_store.consult_list("initiator = $1", (user,))
    if rows is not None:
        return rows
    return [i for i in _load()[::-1] if i.get("initiator") == user]


def participated(user: str) -> list[dict]:
    """参与视角：本人作为受邀科室医生给过意见（每人一票留痕）的会诊，新→旧。
    第 89 批：优先 PG 条件查询（opinions jsonb 包含 {doctor: user}），异常回落内存。"""
    rows = pg_store.consult_list(
        "data->'opinions' @> $1::jsonb",
        (json.dumps([{"doctor": user}], ensure_ascii=False),))
    if rows is not None:
        return rows
    return [i for i in _load()[::-1]
            if any(o.get("doctor") == user for o in (i.get("opinions") or []))]


def matches_query(item: dict, q: str) -> bool:
    """第 111 批：会诊单关键字匹配（大小写不敏感子串）；空关键字恒 True。

    检索面：病例/会诊问题、AI 各科意见、发起人、受邀科室、已填意见正文——
    覆盖"按病例找""按科室找""按谁提过意见找"三类常见诉求。
    与 prescriptions.matches_query 同语义（同一交互预期，不引入第二套匹配规则）。
    """
    kw = str(q or "").strip().lower()
    if not kw:
        return True
    opinions = " ".join(str((o or {}).get("content") or "")
                        for o in (item.get("opinions") or []) if isinstance(o, dict))
    blob = " ".join((str(item.get("question") or ""), str(item.get("ai_analysis") or ""),
                     str(item.get("initiator") or ""),
                     " ".join(str(d) for d in (item.get("target_depts") or [])),
                     opinions)).lower()
    return kw in blob


def add_opinion(cid: str, doctor: str, dept: str, content: str) -> dict:
    """受邀科室医生填写本科室意见（仅建议权无驳回权，任务4：意见按科室一票）。

    校验：会诊须 open；填报人 dept 必须在 target_depts（非受邀 → 权限错 403）；
    同一医生不可重复填写（→ 业务错 400）；该科室已有医生提交过意见 → 400
    「该科室已完成意见」（科室作为整体一票，同科室第二名医生不再重复填写）；
    dept 为空说明账号未设置科室（→ 403）。
    """
    if not doctor:
        raise ConsultError("意见人不能为空")
    content = (content or "").strip()
    if not content:
        raise ConsultError("意见内容不能为空")
    if not dept:
        raise ConsultPermissionError("请先联系管理员设置科室，再填写会诊意见")

    def _do(i: dict) -> None:
        if i.get("status") != "open":
            raise ConsultError(f"该会诊已结束（{i.get('status')}），不再受理意见")
        if dept not in (i.get("target_depts") or []):
            raise ConsultPermissionError("您所在科室未被邀请参与该会诊")
        if any(o.get("doctor") == doctor for o in (i.get("opinions") or [])):
            raise ConsultError("您已提交过本科室意见（每人仅一票）")
        # 任务4：科室一票——该科室已有人提交（含同科室其他医生）→ 拒绝
        if any(o.get("dept") == dept for o in (i.get("opinions") or [])):
            raise ConsultError("该科室已完成意见")
        i.setdefault("opinions", []).append(
            {"doctor": doctor, "dept": dept, "content": content, "ts": _now()})

    with _lock:
        return _mutate_row(cid, _do, ConsultNotFoundError("未找到该会诊"))


def close(cid: str, initiator: str) -> dict:
    """结束会诊并汇总（仅发起者）：status→closed，汇总（AI 意见 + 各科医生意见并排
    署名）落回会诊记录 summary 字段。非发起者 → 权限错 403；已结束 → 业务错 400。"""
    if not initiator:
        raise ConsultPermissionError("仅发起者本人可结束会诊")

    def _do(i: dict) -> None:
        if i.get("initiator") != initiator:
            raise ConsultPermissionError("仅发起者本人可结束会诊并汇总")
        if i.get("status") != "open":
            raise ConsultError("该会诊已结束，请勿重复操作")
        ops = i.get("opinions") or []
        lines = ["【AI 初步意见（按科室定向）】", i.get("ai_analysis") or "（无）", "",
                 "—— 各科室医生意见（并排署名 · 仅建议权）——"]
        if ops:
            for o in ops:
                lines.append(f"【{o.get('dept', '')} · {o.get('doctor', '')}】"
                             f"{o.get('content', '')}（{o.get('ts', '')}）")
        else:
            lines.append("（会诊结束时未收到科室医生意见）")
        i["status"] = "closed"
        i["closed_at"] = _now()
        i["summary"] = "\n".join(lines)

    with _lock:
        return _mutate_row(cid, _do, ConsultNotFoundError("未找到该会诊"))
