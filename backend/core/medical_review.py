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
import uuid
from datetime import datetime, timezone

from PIL import Image

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUEUE_FILE = os.path.join(BASE_DIR, "data", "review", "queue.json")
_lock = threading.Lock()

_IMG_MAX_SIDE = 1280  # F3：入队影像压缩上限（长边），控制队列文件与详情页体积


def _compress_one(src: str) -> str:
    """单张 data URL → 长边 ≤1280 的 JPEG(q80) data URL；解码失败抛异常由调用方兜底。"""
    _, _, b64 = src.partition(",")
    img = Image.open(io.BytesIO(base64.b64decode(b64)))
    if max(img.size) > _IMG_MAX_SIDE:
        ratio = _IMG_MAX_SIDE / max(img.size)
        img = img.resize((max(1, round(img.width * ratio)), max(1, round(img.height * ratio))))
    if img.mode not in ("RGB", "L"):  # JPEG 不支持 RGBA/P 等带透明/调色板模式
        img = img.convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def _compress_images(images: list[str] | None) -> list[str] | None:
    """F3：核对可见影像压缩（PIL）。None/空 → None；单张压缩失败该图原样保留。"""
    if not images:
        return None
    out: list[str] = []
    for src in images:
        try:
            out.append(_compress_one(src))
        except Exception:  # noqa: BLE001 —— 非法图不阻断入队，原样保留
            out.append(src)
    return out


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load() -> list[dict]:
    if not os.path.isfile(QUEUE_FILE):
        return []
    with open(QUEUE_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def _save(items: list[dict]) -> None:
    os.makedirs(os.path.dirname(QUEUE_FILE), exist_ok=True)
    tmp = QUEUE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)
    os.replace(tmp, QUEUE_FILE)


def submit(*, agent: str, question: str, answer: str, confidence: float,
           risk_reason: str, submitted_by: str,
           images: list[str] | None = None) -> str:
    """入队一条待核对项，返回 review_id。images：待医生核对的影像（data URL，入队前压缩）。"""
    rid = "rev-" + uuid.uuid4().hex[:10]
    with _lock:
        items = _load()
        items.append({
            "id": rid, "ts": _now(), "agent": agent, "question": question,
            "answer": answer, "confidence": confidence, "risk_reason": risk_reason,
            "status": "pending", "submitted_by": submitted_by,
            "reviewed_by": None, "review_note": None, "resolved_at": None,
            "images": _compress_images(images),
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
    return _load()[::-1][:limit]


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
                _save(items)
                return i
        raise ReviewError("未找到该审核条目")
