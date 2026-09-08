"""高危双人核对审核队列（临床医生版核心）。

常规答案医生在线直接采纳（不入队）；仅当回答被判定为高风险/低置信时入队，
由「第二名」医生/药师签发。强制双控：reviewer 不能是提交者本人。
存储：data/review/queue.json（原子写）。审计另记。
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


def _load() -> list[dict]:
    """读队列文件。Windows 下与他人并发写（_save 的 os.replace 前）可能瞬时 PermissionError，
    有限重试后仍失败才抛原异常（FIND-04；读路径不加锁，仅重试）。"""
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


def _save(items: list[dict]) -> None:
    os.makedirs(os.path.dirname(QUEUE_FILE), exist_ok=True)
    tmp = QUEUE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)
    os.replace(tmp, QUEUE_FILE)


def submit(*, agent: str, question: str, answer: str, confidence: float,
           risk_reason: str, submitted_by: str,
           images: list[str] | None = None) -> str:
    """入队一条待核对项，返回 review_id。images：待医生核对的影像（data URL）。
    压缩/截断为纯函数，在锁外完成（FIND-01）；锁内仅 _load/append/_save。"""
    rid = "rev-" + uuid.uuid4().hex[:10]
    compressed = _compress_images(images)  # FIND-01：纯函数不持锁，缩短临界区
    stored_images = None if compressed is None else _cap_total_chars(compressed)
    with _lock:
        items = _load()
        items.append({
            "id": rid, "ts": _now(), "agent": agent, "question": question,
            "answer": answer, "confidence": confidence, "risk_reason": risk_reason,
            "status": "pending", "submitted_by": submitted_by,
            "reviewed_by": None, "review_note": None, "resolved_at": None,
            "images": stored_images,
        })
        _save(items)
    return rid


def pending() -> list[dict]:
    return [i for i in _load() if i["status"] == "pending"]


def get(rid: str) -> dict | None:
    for i in _load():
        if i["id"] == rid:
            return i
    return None


def list_all(limit: int = 100) -> list[dict]:
    """历史列表（admin_data / review_history 数据源）：返回前剥离 images（FIND-03 体积控制，
    历史不再全量带图；详情仍可用 get()）。"""
    return [{k: v for k, v in i.items() if k != "images"}
            for i in _load()[::-1][:limit]]


def purge_pending() -> int:
    """清空全部 pending 项（保留已处理历史），返回清除数量。用于清理测试数据。"""
    with _lock:
        items = _load()
        keep = [i for i in items if i.get("status") != "pending"]
        n = len(items) - len(keep)
        if n:
            _save(keep)
        return n


class ReviewError(Exception):
    pass


def resolve(rid: str, decision: str, reviewer: str, note: str = "") -> dict:
    """decision: approved | rejected。双控：reviewer != submitted_by。"""
    if decision not in {"approved", "rejected"}:
        raise ReviewError("decision 需为 approved 或 rejected")
    if not reviewer:
        raise ReviewError("审核人不能为空")
    with _lock:
        items = _load()
        for i in items:
            if i["id"] == rid:
                if i["status"] != "pending":
                    raise ReviewError(f"该条目已处理：{i['status']}")
                if reviewer == i["submitted_by"]:
                    raise ReviewError("双控失败：审核人不能是提交人本人")
                i["status"] = decision
                i["reviewed_by"] = reviewer
                i["review_note"] = note
                i["resolved_at"] = _now()
                i["images"] = None  # FIND-03a：已处理历史瘦身，影像不再留存
                _save(items)
                return i
        raise ReviewError("未找到该审核条目")


def reopen(rid: str, operator: str) -> dict:
    """翻案（转人工复核）：已处理（approved/rejected，含「AI·阈值自动」签发）条目回到
    pending 并清除签署信息，由另一名医师/药师重新核对；翻案动作本身由路由层记审计留痕。
    双控对称：operator 不能是提交人本人（防止单人闭环操纵条目生命周期）。"""
    if not operator:
        raise ReviewError("翻案操作人不能为空")
    with _lock:
        items = _load()
        for i in items:
            if i["id"] == rid:
                if i["status"] == "pending":
                    raise ReviewError("该条目仍在待核对，无需重开")
                if operator == i["submitted_by"]:
                    raise ReviewError("双控失败：翻案人不能是提交人本人")
                i["status"] = "pending"
                i["reviewed_by"] = None
                i["review_note"] = None
                i["resolved_at"] = None
                _save(items)
                return i
        raise ReviewError("未找到该审核条目")
