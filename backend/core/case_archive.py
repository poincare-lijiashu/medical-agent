"""阶段4：病例库合规归档（质控通过自动归档 + 软删除可追溯）。

数据流：qc/admin 在审核中心对质控（agent='qc'）条目签发 approve 后，resolve 端点挂点
调用 archive_from_review 构造归档条目（try/except 全兜底：失败仅审计 case_archive.failed，
绝不影响质控主流程）。归档条目含已脱敏病历结构 record/labs、质控结论摘要与置信、质控
签字人；patient_ref 为脱敏标识（主诉前 N 字，绝不取医师签名/姓名等身份字段）。
移除为软删除（status=removed + removed_by/removed_reason 留痕，可审计不可恢复原状态）。

存储走 pg_store repo 层（照 prescriptions 同模式）：PG 池可用 → PG 真源（case_archive 表：
id text pk + data jsonb 全量留痕 + dept/status 冗余列）；PG 不可用/异常/空表 →
data/case_archive.json（JSON 原子写兜底，PermissionError 重试与 _lock 锁语义保留）。
权限矩阵在路由层：list=qc/admin 全量（科室/时间筛选），doctor 仅本人 status=active；
remove=qc/admin（doctor 403，由 require_role 拒绝）。
"""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from datetime import datetime, timezone

from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASE_ARCHIVE_FILE = os.path.join(BASE_DIR, "data", "case_archive.json")
_lock = threading.Lock()

_PATIENT_REF_LEN = 12   # patient_ref 脱敏标识长度（主诉前 N 字）
_MAX_CONCLUSION = 300   # 质控结论摘要截断长度（完整报告在 review 队列留痕，归档存摘要）


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_json() -> list[dict]:
    """case_archive.json 现状读逻辑（JSON 兜底真源）。Windows 下与他人并发写
    （os.replace 前）可能瞬时 PermissionError，有限重试后仍失败才抛原异常。"""
    if not os.path.isfile(CASE_ARCHIVE_FILE):
        return []
    for attempt in range(4):  # 首次 + 3 次重试 × 50ms
        try:
            with open(CASE_ARCHIVE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except PermissionError:
            if attempt == 3:
                raise
            time.sleep(0.05)
    return []  # pragma: no cover —— 不可达（重试耗尽时已 raise）


def _load() -> list[dict]:
    # PG 池可用 → PG 真源；不可用/异常/空表 → JSON（重试语义保留在 _load_json）
    return pg_store.load_case_archive(CASE_ARCHIVE_FILE, _load_json)


def _save(items: list[dict]) -> None:
    # JSON 原子写兜底 + PG 全量同步（best-effort）；运行在调用方 _lock 临界区内
    pg_store.save_case_archive(items, CASE_ARCHIVE_FILE)


def _patient_ref(record: dict) -> str:
    """脱敏患者标识：取质控病历「主诉」前 N 字（主诉缺失回落「现病史」前 N 字）。

    合规红线：绝不取「医师签名」「患者姓名」等身份字段——record 中的医师签名是
    服务端绑定的医生账号（PHI），只能留在 record 结构内（qc/admin 全量视图），
    不得进入列表可见的 patient_ref。
    """
    r = record if isinstance(record, dict) else {}
    ref = str(r.get("主诉") or "").strip() or str(r.get("现病史") or "").strip()
    return ref[:_PATIENT_REF_LEN]


def archive_from_review(item: dict) -> dict:
    """质控 approve 条目 → 归档条目（resolve 端点挂点调用，qc approve 成功后执行）。

    item：review 队列条目（resolve 后形态：status=approved、meta={record,labs} 为
    /qc/record 提交时留档的已脱敏原病历）。非 qc / 非 approved → ValueError（挂点
    由调用方 try/except 全兜底，任何异常只审计不冒泡）。
    """
    if not isinstance(item, dict) or item.get("agent") != "qc":
        raise ValueError("仅质控（qc）审核条目可归档")
    if item.get("status") != "approved":
        raise ValueError(f"仅质控通过（approved）条目可归档，当前：{item.get('status')}")
    meta = item.get("meta") or {}
    record = meta.get("record") if isinstance(meta.get("record"), dict) else {}
    labs = meta.get("labs") if isinstance(meta.get("labs"), dict) else {}
    entry = {
        "id": "arch-" + uuid.uuid4().hex[:8],
        "patient_ref": _patient_ref(record),
        "dept": str(record.get("科室") or "").strip(),
        "record": record,                       # 已脱敏病历结构（整段留档）
        "labs": labs,
        "qc_conclusion": str(item.get("answer") or "").strip()[:_MAX_CONCLUSION],
        "qc_confidence": item.get("confidence"),
        "reviewed_by": item.get("reviewed_by"),  # 质控签字人（含 AI·留痕模式(自动) 等形态）
        "submitted_by": item.get("submitted_by"),  # 提交医生（doctor「我的归档」归属依据）
        "archived_at": _now(),
        "status": "active",
        "removed_by": None,
        "removed_reason": None,
    }
    with _lock:
        items = _load()
        items.append(entry)
        _save(items)
    return entry


def get(arch_id: str) -> dict | None:
    for i in _load():
        if i.get("id") == arch_id:
            return i
    return None


def list_cases(*, dept: str | None = None, status: str | None = None,
               date_from: str | None = None, date_to: str | None = None) -> list[dict]:
    """全量清单（qc/admin）：按科室/状态/归档日期区间筛选，新→旧。

    日期参数取 YYYY-MM-DD，与 archived_at（ISO 串）前 10 位做字典序比较。
    """
    df = str(date_from or "").strip()
    dt = str(date_to or "").strip()
    out = []
    for i in _load()[::-1]:
        if dept and i.get("dept") != dept:
            continue
        if status and i.get("status") != status:
            continue
        ts = str(i.get("archived_at") or "")
        if df and ts[:10] < df:
            continue
        if dt and ts[:10] > dt:
            continue
        out.append(i)
    return out


def list_mine(doctor: str) -> list[dict]:
    """doctor 仅本人 status=active（新→旧，只读）。doctor 无移除权，removed 条目不可见。"""
    return [i for i in _load()[::-1]
            if i.get("submitted_by") == doctor and i.get("status") == "active"]


def remove(arch_id: str, operator: str, reason: str) -> dict:
    """软删除：status=removed + removed_by/removed_reason 留痕（qc/admin，路由层限权）。

    原因必填（缺失 ValueError → 端点 422）；已移除条目不得重复移除（保留首次原因留痕）。
    """
    if not (operator or "").strip():
        raise ValueError("操作人不能为空")
    reason = str(reason or "").strip()
    if not reason:
        raise ValueError("移除原因必填")
    with _lock:
        items = _load()
        for i in items:
            if i.get("id") == arch_id:
                if i.get("status") == "removed":
                    raise ValueError(f"该条目已移除：{arch_id}")
                i["status"] = "removed"
                i["removed_by"] = operator.strip()
                i["removed_reason"] = reason
                _save(items)
                return i
    raise ValueError(f"归档条目不存在：{arch_id}")


def stats() -> dict:
    """库内统计：{total, active, removed}（病例库统计头，N 例在库 / M 移除）。"""
    items = _load()
    active = sum(1 for i in items if i.get("status") == "active")
    return {"total": len(items), "active": active, "removed": len(items) - active}
