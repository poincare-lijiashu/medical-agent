"""高危双人核对审核队列（临床医生版核心）。

常规答案医生在线直接采纳（不入队）；仅当回答被判定为高风险/低置信时入队，
由「第二名」医生/药师签发。强制双控：reviewer 不能是提交者本人。
存储走 pg_store repo 层（B1 数据真源化）：PG 池可用 → PG 真源（review_queue 含 images）；
PG 不可用/异常/空表 → data/review/queue.json（现状行为完全等价，PermissionError 重试
与 _lock 锁语义保留在 JSON 路径）。审计另记。
"""
from __future__ import annotations

import base64
import io
import json
import os
import threading
import time
import uuid
from datetime import datetime, timezone

from PIL import Image

from backend.config import settings
from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUEUE_FILE = os.path.join(BASE_DIR, "data", "review", "queue.json")
_lock = threading.Lock()

_IMG_MAX_SIDE = 1280  # F3：入队影像压缩上限（长边），控制队列文件与详情页体积
_IMG_MAX_PIXELS = 40_000_000  # FIND-02：解码前像素上限（防 decompression bomb 与超大图卡顿）
_IMG_TOTAL_CHARS = 4_000_000  # FIND-03：单条入队 images 总 base64 字符上限（控制队列文件体积）


def _compress_one(src: str) -> str:
    """单张 data URL → 长边 ≤1280 的 JPEG(q80) data URL；非法/像素超限抛异常由调用方丢弃（FIND-02）。"""
    _, _, b64 = src.partition(",")
    img = Image.open(io.BytesIO(base64.b64decode(b64)))  # 仅读头部，未解码像素
    if img.width * img.height > _IMG_MAX_PIXELS:
        raise ValueError(f"图像像素超限（{img.width * img.height} > {_IMG_MAX_PIXELS}）")
    if max(img.size) > _IMG_MAX_SIDE:
        ratio = _IMG_MAX_SIDE / max(img.size)
        img = img.resize((max(1, round(img.width * ratio)), max(1, round(img.height * ratio))))
    if img.mode not in ("RGB", "L"):  # JPEG 不支持 RGBA/P 等带透明/调色板模式
        img = img.convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def _compress_images(images: list[str] | None) -> list[str] | None:
    """F3：核对可见影像压缩（PIL）。None/空 → None；压缩失败的该图丢弃，不入库（FIND-02）。"""
    if not images:
        return None
    out: list[str] = []
    for src in images:
        try:
            out.append(_compress_one(src))
        except Exception:  # noqa: BLE001 —— 非法/超限图不入库，不阻断其余图
            continue
    return out


def _cap_total_chars(images: list[str]) -> list[str]:
    """FIND-03：单条 images 总 base64 字符数上限 4M，超限从尾部丢弃超额图；
    首图本身超限则丢弃该图（合规首图必须保留）。"""
    out: list[str] = []
    total = 0
    for s in images:
        n = len(s or "")
        if out:
            if total + n > _IMG_TOTAL_CHARS:
                break  # 从尾部截断：后续图全部丢弃
        elif n > _IMG_TOTAL_CHARS:
            continue  # 首图本身超限 → 丢弃，尝试下一张
        out.append(s)
        total += n
    return out


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_json() -> list[dict]:
    """queue.json 现状读逻辑（JSON 兜底真源）。Windows 下与他人并发写（_save 的 os.replace
    前）可能瞬时 PermissionError，有限重试后仍失败才抛原异常（FIND-04；读路径不加锁，仅重试）。"""
    if not os.path.isfile(QUEUE_FILE):
        return []
    for attempt in range(4):  # 首次 + 3 次重试 × 50ms
        try:
            with open(QUEUE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except PermissionError:
            if attempt == 3:
                raise
            time.sleep(0.05)
    return []  # pragma: no cover —— 不可达（重试耗尽时已 raise）


def _load() -> list[dict]:
    # B1 数据真源化：PG 池可用 → PG 真源；不可用/异常/空表 → JSON（重试语义保留在 _load_json）
    return pg_store.load_queue(QUEUE_FILE, _load_json)


def _save(items: list[dict]) -> None:
    # B1：JSON 原子写兜底 + PG 全量同步（best-effort）；仍运行在调用方 _lock 临界区内
    pg_store.save_queue(items, QUEUE_FILE)


def _save_row(item: dict, items: list[dict]) -> None:
    """W4.1 行级落盘：PG 主模式（pg_primary=true 默认）且池可用 → 单行 UPSERT（严格，
    PG 写失败抛出防分裂）+ JSON 镜像（灾难恢复源）；灰度回滚（pg_primary=false，旧编排）
    或无池/离线 → JSON 真源全量（save_queue：JSON 先行 + PG best-effort，行为与拆分前
    完全一致）。
    行级写根除多实例并发互覆：各实例改不同条目时 save_queue 全量同步的
    last-writer-wins 不再丢更新。运行在调用方 _lock 临界区内（读-改-写原子性不变）。"""
    if settings.pg_primary and pg_store.has_pool():
        pg_store.upsert_review_row(item)
        pg_store.mirror_json_file(QUEUE_FILE, items)
    else:
        _save(items)


def _persist_new(item: dict) -> None:
    """第 87 批：新增一条时不再读全表——PG 主模式单行 UPSERT + JSON 镜像就地追加；
    灰度回滚/无池/离线保持原「读全量 → append → 全量写」语义（行为不变）。
    运行在调用方 _lock 临界区内。"""
    if settings.pg_primary and pg_store.has_pool():
        pg_store.upsert_review_row(item)
        pg_store.mirror_json_upsert(QUEUE_FILE, item)
        return
    items = _load()
    items.append(item)
    _save(items)


def _mutate_row(rid: str, fn) -> dict:
    """第 87 批：单条「定位 → 校验/变更 → 落盘」骨架（替代读全表线性查找）。

    PG 主模式：主键直查（get_review_row）+ 单行 UPSERT + 镜像就地更新；
    `(False, None)`（无池/离线/空表/异常 = PG 答不了）或灰度回滚形态 → 回落原
    「读全量 → 改一条 → 全量写」，行为与拆分前完全一致。
    运行在调用方 _lock 临界区内。"""
    if settings.pg_primary and pg_store.has_pool():
        found, item = pg_store.get_review_row(rid)
        if found:
            if item is None:
                raise ReviewError("未找到该审核条目")
            fn(item)
            pg_store.upsert_review_row(item)  # 严格：PG 写失败抛出，防 PG/JSON 分裂
            pg_store.mirror_json_upsert(QUEUE_FILE, item)
            return item
    items = _load()
    for i in items:
        if i["id"] == rid:
            fn(i)
            _save(items)
            return i
    raise ReviewError("未找到该审核条目")


def submit(*, agent: str, question: str, answer: str, confidence: float,
           risk_reason: str, submitted_by: str,
           images: list[str] | None = None,
           self_confirm_required: bool = False,
           resubmit_of: str | None = None,
           attempt: int = 1,
           meta: dict | None = None,
           sources: list | None = None) -> str:
    """入队一条待核对项，返回 review_id。images：待医生核对的影像（data URL）。
    压缩/截断为纯函数，在锁外完成（FIND-01）；锁内仅 _load/append/_save。
    任务3：self_confirm_required=True（留痕模式自动签发的高危项）→ 提交医生需知情确认。
    任务6：resubmit_of=原驳回记录 id、attempt=第 N 次提交（链上递增，路由层计算）、
    meta=随件留档（质控为已脱敏原病历，供「重新提交」预填），普通提交不传。
    任务3 全交互留痕：sources=响应溯源来源（如 KB:PMID/drug_rules/VL:模型名）随条目
    存档（详情可回溯证据链），不传为 None。"""
    rid = "rev-" + uuid.uuid4().hex[:10]
    compressed = _compress_images(images)  # FIND-01：纯函数不持锁，缩短临界区
    stored_images = None if compressed is None else _cap_total_chars(compressed)
    try:
        attempt_n = int(attempt) if attempt is not None else 1
    except (TypeError, ValueError):
        attempt_n = 1
    with _lock:
        entry = {
            "id": rid, "ts": _now(), "agent": agent, "question": question,
            "answer": answer, "confidence": confidence, "risk_reason": risk_reason,
            "status": "pending", "submitted_by": submitted_by,
            "reviewed_by": None, "review_note": None, "resolved_at": None,
            "images": stored_images,
            # 任务3：知情确认三字段（留痕模式使用；常规路径恒为 False/None）
            "self_confirm_required": bool(self_confirm_required),
            "confirmed_by_self": None, "self_confirmed_at": None,
            # 任务6：重提链三字段（普通提交恒为 None/1/None）
            "resubmit_of": resubmit_of or None,
            "attempt": max(1, attempt_n),
            "meta": meta,
            # 任务3 全交互留痕：溯源来源存档（JSON 兜底真源；PG 镜像同列）
            "sources": list(sources) if sources else None,
        }
        _persist_new(entry)  # 第 87 批：PG 主模式不再为新条目读全表
    return rid


def my_pending_confirm(username: str) -> list[dict]:
    """任务3：本人待知情确认项（submitted_by=本人 且 self_confirm_required 且未确认），
    新→旧，剥离 images（体积控制与 list_all 同纪律）。
    第 86 批：优先 PG 条件查询（确认列已行级化），空表/无池/异常回落既有内存扫描。"""
    rows = pg_store.review_my_rows(
        "submitted_by = $1 AND self_confirm_required AND confirmed_by_self IS NOT TRUE",
        (username,))
    if rows is not None:
        return rows
    return [{k: v for k, v in i.items() if k != "images"}
            for i in _load()[::-1]
            if i.get("submitted_by") == username and i.get("self_confirm_required")
            and not i.get("confirmed_by_self")]


def self_confirm(rid: str, username: str) -> dict:
    """任务3：提交医生本人对「AI·留痕模式(自动)」签发的高危项做知情确认（仅本人）。

    校验：仅提交人本人；条目须标记 self_confirm_required；不可重复确认。
    """
    if not username:
        raise ReviewError("确认人不能为空")

    def _do(i: dict) -> None:
        if i.get("submitted_by") != username:
            raise ReviewError("仅提交人本人可确认")
        if not i.get("self_confirm_required"):
            raise ReviewError("该条目无需知情确认")
        if i.get("confirmed_by_self"):
            raise ReviewError("该条目已确认过")
        i["confirmed_by_self"] = True
        i["self_confirmed_at"] = _now()

    with _lock:
        return _mutate_row(rid, _do)


def my_rejections(username: str) -> list[dict]:
    """任务2：本人被驳回（status=rejected）的记录，新→旧，剥离 images。
    任务6：同时返回本人的重提链记录（resubmit_of 非空，任意状态）——重提提交后新条目
    立即置顶显示在「我的质控驳回」卡片第一条（含「第 N 次提交」进度）。
    阶段0.2：只返回 agent=="qc" 的条目——drug 类驳回由药剂科复核产生、且不带质控表单
    可预填的 meta，混入本卡片会导致「重新提交」假预填（横幅声称已预填但各栏无值）；
    drug 驳回改由 my_drug_rejections 提供药物助手上下文提示。
    携带 review_note（驳回原因）/reviewed_by（审核人）/resolved_at（时间）/
    attempt/resubmit_of/meta（原病历结构化留档 record+labs，供前端「重新提交」逐栏直填），
    供医生端只读卡片。第 86 批：优先 PG 条件查询（resubmit_of 列已行级化）。"""
    rows = pg_store.review_my_rows(
        "submitted_by = $1 AND agent = 'qc' AND "
        "(status = 'rejected' OR COALESCE(resubmit_of, '') <> '')", (username,))
    if rows is not None:
        return rows
    return [{k: v for k, v in i.items() if k != "images"}
            for i in _load()[::-1]
            if i.get("submitted_by") == username and i.get("agent") == "qc"
            and (i.get("status") == "rejected" or i.get("resubmit_of"))]


def my_drug_rejections(username: str) -> list[dict]:
    """阶段0.2：本人被药剂科驳回（agent=drug 且 status=rejected）的记录，新→旧，
    剥离 images。供药物助手视图显示「曾被药剂科驳回：{问题}」提示（过渡实现）：
    与 qc 驳回分流入各自的上下文，避免质控卡片出现不可重提的药物条目。
    第 86 批：优先 PG 条件查询，空表/无池/异常回落既有内存扫描。"""
    rows = pg_store.review_my_rows(
        "submitted_by = $1 AND agent = 'drug' AND status = 'rejected'", (username,))
    if rows is not None:
        return rows
    return [{k: v for k, v in i.items() if k != "images"}
            for i in _load()[::-1]
            if i.get("submitted_by") == username and i.get("agent") == "drug"
            and i.get("status") == "rejected"]


def pending() -> list[dict]:
    return [i for i in _load() if i["status"] == "pending"]


def page_pending(exclude_drug: bool = False, keyword: str = "",
                 limit: int | None = None, offset: int = 0) -> dict | None:
    """第 82 批：待核对列表分页/筛选/计数下推 PG。

    返回 {items,total}（items 已按 ts,id 升序切片，与内存 pending() 的队列顺序一致）；
    None → 路由层回落既有内存实现（无池/离线/异常/空表，行为完全不变）。"""
    return pg_store.review_pending_page(exclude_drug, keyword, limit, offset)


def page_history(involves: str | None = None, base_limit: int = 50,
                 hide_pending: bool = False, hide_drug: bool = False, keyword: str = "",
                 limit: int | None = None, offset: int = 0) -> dict | None:
    """第 83 批：历史列表分页/筛选/计数下推 PG。

    involves=me → 基础集合为「本人参与」(submitted_by/reviewed_by=me) 最新 base_limit 条；
    None → 全表最新 base_limit 条。**先截断再过滤**，与内存 list_involved/list_all 一致。
    None → 路由层回落既有内存实现。"""
    return pg_store.review_history_page(involves, base_limit, hide_pending, hide_drug,
                                        keyword, limit, offset)


def get(rid: str) -> dict | None:
    """单条查询：第 84 批优先按主键直查 PG（避免为一条详情读全表）；
    PG 不可用/空表 → 回落既有 _load() 线性查找（行为不变）。"""
    found, item = pg_store.get_review_row(rid)
    if found:
        return item
    for i in _load():
        if i["id"] == rid:
            return i
    return None


def _is_auto_signed(item: dict) -> bool:
    """任务5：是否 AI 自动签发（reviewed_by=「AI·留痕模式(自动)」/「AI·阈值自动(规则库v2)」）。
    人工签发的 reviewed_by 为真实 qc/admin 用户名。"""
    return str(item.get("reviewed_by") or "").startswith("AI·")


def is_auto_signed(item: dict) -> bool:
    """公开只读判定（第 73 批）：历史详情接口复用人机签发判定，避免两处漂移。

    历史视图的合规规则不同（人工签发后不留原图），路由层需要这个判定来选择
    是否保留 images；保持单一实现（本函数只是 _is_auto_signed 的公开别名）。"""
    return _is_auto_signed(item)


def list_involved(username: str, limit: int = 200) -> list[dict]:
    """问题3修复：本人参与的已处理记录（submitted_by=me OR reviewed_by=me），新→旧。

    背景（实测结论）：data/review/queue.json 中 pharmacist 处理过的 drug 项
    reviewed_by 为真实用户名（如 'pharm01'，非「AI·」显示名）——存储正确；
    但 /review/history 数据源 list_all(limit=50) 按插入序全局截断，真实队列中
    pharmacist 处理的 drug 项会被其后大量「AI·阈值自动」签发条目挤出最新 50 条
    窗口（实测 636 条中最新 50 条含 pharm01 参与记录 0 条），前端按
    reviewed_by=me 过滤恒空。故「我的历史」数据源改为服务端按人检索（不受全局
    截断影响）。images 可见性与 /review/history 既有边界一致：仅 AI 自动签发且
    查看者=提交人时保留（他人提交的记录一律剥离，可见性边界不放宽）。"""
    def _view(i: dict) -> dict:
        if _is_auto_signed(i) and i.get("submitted_by") == username:
            return dict(i)
        return {k: v for k, v in i.items() if k != "images"}
    return [_view(i) for i in _load()[::-1]
            if (i.get("submitted_by") == username or i.get("reviewed_by") == username)][:limit]


def list_all(limit: int = 100, with_images: bool = True) -> list[dict]:
    """历史列表（admin_data 统计 / review_history 数据源）。

    任务5 剥离规则调整（更新 FIND-03b 语义，合规初衷不变）：**仅人工签发的记录剥离
    images**（人工复核后不留原图）；AI 自动签发（留痕模式/阈值自动，签发时
    keep_images=True 已存档）的记录保留 images——用户自己的交互留痕在历史区可回溯。
    with_images=False：全量剥离（/overview、/admin/data 等只做计数的调用方用，
    控响应体积，不携带 base64）。可见性边界（images 仅发起人本人/admin 可见）由
    /review/history 路由按查看者收窄，本函数不做人级过滤。"""
    return [(dict(i) if (with_images and _is_auto_signed(i))
             else {k: v for k, v in i.items() if k != "images"})
            for i in _load()[::-1][:limit]]


def purge_pending() -> int:
    """清空全部 pending 项（保留已处理历史），返回清除数量。用于清理测试数据。
    W4.1：PG 可用走行级批量删除（严格，失败抛出防 PG/JSON 分裂）+ JSON 镜像；
    无池/离线 → JSON 全量。"""
    with _lock:
        items = _load()
        keep = [i for i in items if i.get("status") != "pending"]
        n = len(items) - len(keep)
        if n:
            if settings.pg_primary and pg_store.has_pool():
                pg_store.purge_pending_reviews_strict()
                pg_store.mirror_json_file(QUEUE_FILE, keep)
            else:
                _save(keep)
        return n


class ReviewError(Exception):
    pass


def resolve(rid: str, decision: str, reviewer: str, note: str = "",
            keep_images: bool = False) -> dict:
    """decision: approved | rejected。双控：reviewer != submitted_by。
    keep_images（任务3 留痕模式自动签发=True）：签发后保留 images 存档（留痕的可回溯
    价值所在）；默认 False 维持 FIND-03a 现状（人工签发路径清空影像控制体积）。"""
    if decision not in {"approved", "rejected"}:
        raise ReviewError("decision 需为 approved 或 rejected")
    if not reviewer:
        raise ReviewError("审核人不能为空")

    def _do(i: dict) -> None:
        if i["status"] != "pending":
            raise ReviewError(f"该条目已处理：{i['status']}")
        if reviewer == i["submitted_by"]:
            raise ReviewError("双控失败：审核人不能是提交人本人")
        i["status"] = decision
        i["reviewed_by"] = reviewer
        i["review_note"] = note
        i["resolved_at"] = _now()
        if not keep_images:
            i["images"] = None  # FIND-03a：人工签发历史瘦身，影像不再留存

    with _lock:
        return _mutate_row(rid, _do)


def reopen(rid: str, operator: str) -> dict:
    """翻案（转人工复核）：已处理（approved/rejected，含「AI·阈值自动」签发）条目回到
    pending 并清除签署信息，由另一名医师/药师重新核对；翻案动作本身由路由层记审计留痕。
    双控对称：operator 不能是提交人本人（防止单人闭环操纵条目生命周期）。"""
    if not operator:
        raise ReviewError("翻案操作人不能为空")

    def _do(i: dict) -> None:
        if i["status"] == "pending":
            raise ReviewError("该条目仍在待核对，无需重开")
        if operator == i["submitted_by"]:
            raise ReviewError("双控失败：翻案人不能是提交人本人")
        i["status"] = "pending"
        i["reviewed_by"] = None
        i["review_note"] = None
        i["resolved_at"] = None

    with _lock:
        return _mutate_row(rid, _do)
