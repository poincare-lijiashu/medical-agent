"""高危双人核对审核队列（临床医生版核心）。

常规答案医生在线直接采纳（不入队）；仅当回答被判定为高风险/低置信时入队，
由「第二名」医生/药师签发。强制双控：reviewer 不能是提交者本人。
存储：data/review/queue.json（原子写）。审计另记。
"""
from __future__ import annotations

import json
import os
import threading
import uuid
from datetime import datetime, timezone

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUEUE_FILE = os.path.join(BASE_DIR, "data", "review", "queue.json")
_lock = threading.Lock()


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
           risk_reason: str, submitted_by: str) -> str:
    """入队一条待核对项，返回 review_id。"""
    rid = "rev-" + uuid.uuid4().hex[:10]
    with _lock:
        items = _load()
        items.append({
            "id": rid, "ts": _now(), "agent": agent, "question": question,
            "answer": answer, "confidence": confidence, "risk_reason": risk_reason,
            "status": "pending", "submitted_by": submitted_by,
            "reviewed_by": None, "review_note": None, "resolved_at": None,
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
