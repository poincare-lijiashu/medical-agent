"""智能开药：处方域模块（阶段2.1）。

状态机（非法流转一律 ValueError）：
    draft → pending_pharm → approved / rejected；rejected →（rewrite 改写）→ draft。
- create：医生创建处方即直送药剂科复核（→ pending_pharm）；
- submit：draft 重新送审（→ pending_pharm，rejected 改写后的再提交路径）；
- approve/reject：仅 pending_pharm 可签发/驳回（药剂科），留痕 reviewer/opinion；
- rewrite：仅 rejected 且处方医生本人可改写回 draft（同步可更新病例/药品）；
- list_mine/list_pending/stats：医生本人清单 / 药剂科待核清单 / 状态分布统计。

存储走 pg_store repo 层（与 consults 同模式）：PG 池可用 → PG 真源（prescriptions 表：
id text pk + data jsonb 全量留痕 + doctor/status 冗余列）；PG 不可用/异常/空表 →
data/prescriptions.json（JSON 原子写兜底，PermissionError 重试与 _lock 锁语义保留）。
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
PRESCRIPTIONS_FILE = os.path.join(BASE_DIR, "data", "prescriptions.json")
_lock = threading.Lock()

# 合法状态与可达后继（approved 为终态；rejected 经 rewrite 回 draft 形成闭环）
_TRANSITIONS: dict[str, set[str]] = {
    "draft": {"pending_pharm"},
    "pending_pharm": {"approved", "rejected"},
    "approved": set(),
    "rejected": {"draft"},
}
_MAX_CASE_LEN = 5000  # 病例文本上限（模型约束；端点层另限 20-5000）


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _check_transition(cur: str, target: str) -> None:
    """状态机守卫：cur → target 不在合法流转表即 ValueError（非法流转拒绝）。"""
    if target not in _TRANSITIONS.get(cur, set()):
        raise ValueError(f"非法状态流转：{cur or '未知'} → {target}")


def _load_json() -> list[dict]:
    """prescriptions.json 现状读逻辑（JSON 兜底真源）。Windows 下与他人并发写
    （os.replace 前）可能瞬时 PermissionError，有限重试后仍失败才抛原异常。"""
    if not os.path.isfile(PRESCRIPTIONS_FILE):
        return []
    for attempt in range(4):  # 首次 + 3 次重试 × 50ms
        try:
            with open(PRESCRIPTIONS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except PermissionError:
            if attempt == 3:
                raise
            time.sleep(0.05)
    return []  # pragma: no cover —— 不可达（重试耗尽时已 raise）


def _load() -> list[dict]:
    # PG 池可用 → PG 真源；不可用/异常/空表 → JSON（重试语义保留在 _load_json）
    return pg_store.load_prescriptions(PRESCRIPTIONS_FILE, _load_json)


def _save(items: list[dict]) -> None:
    # JSON 原子写兜底 + PG 全量同步（best-effort）；运行在调用方 _lock 临界区内
    pg_store.save_prescriptions(items, PRESCRIPTIONS_FILE)


def delete_prescriptions(ids: list[str]) -> list[str]:
    """按 id 硬删除处方（F2 e2e 测试数据清理专用；审计 jsonl 不动）。

    JSON 兜底真源过滤重写 + PG 全量同步（save_prescriptions 全量覆盖语义天然完成
    PG 侧删除）。返回实际删除的 id 清单（不存在的 id 忽略，幂等）。
    进程锁复用 _lock，与服务端写路径互斥（外部进程调用时依赖调用方保证时序）。"""
    want = {str(x) for x in (ids or []) if x}
    if not want:
        return []
    with _lock:
        items = _load()
        removed = [x.get("id") for x in items if x.get("id") in want]
        if removed:
            _save([x for x in items if x.get("id") not in want])
        return removed


def _clean_drugs(drugs) -> list[dict]:
    """药品条目归一：[{name,dose,freq,note}]，name 为空/非 dict 条目丢弃。
    整改轮 B 任务2（字典外药方案 A）：out_of_dict 为真时随条目保留（字典外药
    标记必须跨提交/改写/持久化存活），字典内药不写该键（存量数据/断言零扰动）。"""
    out = []
    for d in (drugs or []):
        if not isinstance(d, dict):
            continue
        name = str(d.get("name") or "").strip()
        if not name:
            continue
        entry = {"name": name,
                 "dose": str(d.get("dose") or "").strip(),
                 "freq": str(d.get("freq") or "").strip(),
                 "note": str(d.get("note") or "").strip()}
        if d.get("out_of_dict"):  # 语义变更（方案A）：字典外药条目级标记透传
            entry["out_of_dict"] = True
        out.append(entry)
    return out


def _validate_case_text(case_text) -> str:
    """病例文本校验（1-5000 字，去首尾空白后计长）；返回清洗后文本。"""
    text = str(case_text or "").strip()
    if not text or len(text) > _MAX_CASE_LEN:
        raise ValueError(f"case_text 需为 1-{_MAX_CASE_LEN} 字")
    return text


def create(*, doctor: str, dept: str = "", case_text: str, drugs: list[dict],
           contraindication_reason: str = "", forced_high_risk: bool = False) -> dict:
    """创建处方（→ pending_pharm 直送药剂科复核），返回处方记录。

    forced_high_risk：医生在系统提示高危后仍强制开立时的显式标记（审计/药师重点核对用），
    不改变状态机流转，仅作留痕。
    整改轮 B 任务2（字典外药方案 A）：out_of_dict=任一药在字典外即 true（路由层
    _hard_validate_drugs 已对字典外药强制 note≥5 字理由），供药剂科重点审核与展示。"""
    if not (doctor or "").strip():
        raise ValueError("医生不能为空")
    text = _validate_case_text(case_text)
    cleaned = _clean_drugs(drugs)
    if not cleaned:
        raise ValueError("至少需一种药品（name 必填）")
    now = _now()
    rx = {
        "id": "rx-" + uuid.uuid4().hex[:8],
        "doctor": doctor.strip(),
        "dept": str(dept or "").strip(),
        "case_text": text,
        "drugs": cleaned,
        "out_of_dict": any(d.get("out_of_dict") for d in cleaned),  # 方案A：处方级外典标记
        "contraindication_reason": str(contraindication_reason or "").strip(),
        "status": "pending_pharm",
        "pharm_reviewer": None,
        "pharm_opinion": None,
        "pharm_reviewed_at": None,  # 药剂科审核操作时间（approve/reject 落值；重提清零，留痕用）
        "created_at": now,
        "updated_at": now,
        "forced_high_risk": bool(forced_high_risk),
    }
    with _lock:
        items = _load()
        items.append(rx)
        _save(items)
    return rx


def _mutate(rx_id: str, fn) -> dict:
    """锁内「定位 → 校验流转 → 变更 → 落盘」公共骨架；fn(rx) 原地修改并返回 rx。
    未找到 → ValueError（业务错，路由层转 404/400 由后续阶段决定）。"""
    with _lock:
        items = _load()
        for i in items:
            if i["id"] == rx_id:
                fn(i)
                _save(items)
                return i
    raise ValueError(f"处方不存在：{rx_id}")


def approve(rx_id: str, pharmacist: str, opinion: str = "") -> dict:
    """药剂科签发：pending_pharm → approved（留痕 reviewer/opinion）。"""
    if not (pharmacist or "").strip():
        raise ValueError("药师不能为空")
    now = _now()

    def _do(rx: dict) -> None:
        _check_transition(rx.get("status"), "approved")
        rx["status"] = "approved"
        rx["pharm_reviewer"] = pharmacist.strip()
        rx["pharm_opinion"] = str(opinion or "").strip()
        rx["pharm_reviewed_at"] = now
        rx["updated_at"] = now

    return _mutate(rx_id, _do)


def reject(rx_id: str, pharmacist: str, opinion: str = "") -> dict:
    """药剂科驳回：pending_pharm → rejected（opinion=驳回理由，供医生改写参考）。"""
    if not (pharmacist or "").strip():
        raise ValueError("药师不能为空")
    now = _now()

    def _do(rx: dict) -> None:
        _check_transition(rx.get("status"), "rejected")
        rx["status"] = "rejected"
        rx["pharm_reviewer"] = pharmacist.strip()
        rx["pharm_opinion"] = str(opinion or "").strip()
        rx["pharm_reviewed_at"] = now
        rx["updated_at"] = now

    return _mutate(rx_id, _do)


def rewrite(rx_id: str, doctor: str, case_text: str | None = None,
            drugs: list[dict] | None = None,
            contraindication_reason: str | None = None) -> dict:
    """医生改写被驳回处方：rejected → draft（仅本人可改写；可同步更新病例/药品/禁忌理由）。"""
    if not (doctor or "").strip():
        raise ValueError("医生不能为空")
    new_text = _validate_case_text(case_text) if case_text is not None else None
    cleaned = _clean_drugs(drugs) if drugs is not None else None
    now = _now()

    def _do(rx: dict) -> None:
        if rx.get("doctor") != doctor.strip():
            raise ValueError("仅处方医生本人可改写")
        _check_transition(rx.get("status"), "draft")
        if new_text is not None:
            rx["case_text"] = new_text
        if cleaned:
            rx["drugs"] = cleaned
        if contraindication_reason is not None:
            rx["contraindication_reason"] = str(contraindication_reason).strip()
        # 方案A（整改轮 B 任务2）：改写后按当前药单重算处方级外典标记（换药可进出字典）
        rx["out_of_dict"] = any(d.get("out_of_dict") for d in (rx.get("drugs") or []))
        rx["status"] = "draft"
        rx["updated_at"] = now

    return _mutate(rx_id, _do)


def submit(rx_id: str, doctor: str) -> dict:
    """医生提交 draft 送审：draft → pending_pharm（rejected 改写后的再提交路径）。"""
    now = _now()

    def _do(rx: dict) -> None:
        if rx.get("doctor") != (doctor or "").strip():
            raise ValueError("仅处方医生本人可提交")
        _check_transition(rx.get("status"), "pending_pharm")
        rx["status"] = "pending_pharm"
        rx["pharm_reviewer"] = None
        rx["pharm_opinion"] = None
        rx["pharm_reviewed_at"] = None  # 旧记录可能无此键（dict 赋值即补齐），重提清空上一轮审核留痕
        rx["updated_at"] = now

    return _mutate(rx_id, _do)


def get(rx_id: str) -> dict | None:
    for i in _load():
        if i["id"] == rx_id:
            return i
    return None


def list_mine(doctor: str) -> list[dict]:
    """医生本人全部处方（新→旧）。"""
    return [i for i in _load()[::-1] if i.get("doctor") == doctor]


def list_pending() -> list[dict]:
    """药剂科待核清单（pending_pharm，新→旧）。"""
    return [i for i in _load()[::-1] if i.get("status") == "pending_pharm"]


def list_reviewed_by(pharmacist: str) -> list[dict]:
    """问题3②：药剂科本人审核过的处方（pharm_reviewer=me，新→旧，含 approved/rejected）。

    背景：处方审核与 review 队列是双轨体系——pharmacist 驳回/签发的处方此前完全不在
    /review/history 体系内，审核中心「我的历史」看不到。本函数为
    GET /prescriptions/reviewed-by-me 的数据源（供前端「我的历史」tab 合并渲染：
    rid/状态/意见/时间）。"""
    return [i for i in _load()[::-1]
            if i.get("pharm_reviewer") == pharmacist
            and i.get("status") in ("approved", "rejected")]


def stats() -> dict:
    """状态分布统计：{total, draft, pending_pharm, approved, rejected}（管理/运维用）。"""
    items = _load()
    out = {"total": len(items), "draft": 0, "pending_pharm": 0, "approved": 0, "rejected": 0}
    for i in items:
        s = i.get("status")
        if s in out:
            out[s] += 1
    return out
