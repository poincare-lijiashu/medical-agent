"""科室字典管理（B1 数据真源化：PG 真源 + JSON 兜底）。

质控的「科室」字段由服务端绑定账号 dept，本科室字典是 dept 的权威取值来源：
- 文件不存在时首次访问自动写入默认科室；
- add 重名返回 False（路由转 400）；remove 校验存在（否则 False → 路由 404），
  且仍有账号绑定该科室时抛 ValueError（路由转 400）——防止在用科室被删成悬空值。
- 存取走 pg_store repo 层：PG 池可用 → PG 真源（departments 表 id=顺序号保序）；
  PG 不可用/异常/空表 → data/departments.json（现状行为完全等价）。
"""
from __future__ import annotations

import json
import os
import threading

from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEPARTMENTS_FILE = os.path.join(BASE_DIR, "data", "departments.json")
_lock = threading.Lock()

DEFAULT_DEPARTMENTS = ["内科", "外科", "口腔科", "妇产科", "儿科", "急诊科"]

# 整改轮 B 任务3（科室-角色归属模型）：职能部门清单（医院真实编制模型）。
# 药师编制在药剂科、质控在质控科/医务处、临床科室只有医生——职责天然全院。
# 核对结论：药剂科此前**不在**科室字典（仅作为种子账号 dept 值存在），一并补齐。
FUNCTIONAL_DEPARTMENTS = ["药剂科", "医务处", "质控科", "病案室"]


def ensure_functional_departments() -> list[str]:
    """职能部门兜底入库（服务启动时调用，幂等）：缺哪个补哪个，返回本次新增名单。
    PG 池可用时经 add_department 走「JSON 兜底 + PG 全量同步」，两侧真源同步补齐。"""
    return [name for name in FUNCTIONAL_DEPARTMENTS if add_department(name)]


def validate_role_dept(role: str, dept: str) -> None:
    """科室-角色归属校验（整改轮 B 任务3，仅新建/更新账号时执行，存量账号不追溯）：
    - pharmacist → dept 必须为「药剂科」（药师编制在药剂科）；
    - qc → dept 必须 ∈ {质控科, 医务处}（质控编制在质控科/医务处）；
    - doctor → dept 不得为职能部门（临床科室只有医生）；
    - admin → dept 必须为「医务处」（F4 收口：管理岗归属医务处，不再跨科室放任）。
    违反抛 ValueError（admin/users 端点转 422 中文文案）。"""
    d = (dept or "").strip()
    if role == "pharmacist" and d != "药剂科":
        raise ValueError("药师（pharmacist）所属科室必须为「药剂科」")
    if role == "qc" and d not in ("质控科", "医务处"):
        raise ValueError("质控员（qc）所属科室必须为「质控科」或「医务处」")
    if role == "doctor" and d in FUNCTIONAL_DEPARTMENTS:
        raise ValueError("医生（doctor）所属科室须为临床科室，不能是职能部门（药剂科/质控科/医务处/病案室）")
    if role == "admin" and d != "医务处":
        raise ValueError("admin 账号应归属医务处")


def _load_json() -> dict:
    """departments.json 现状读逻辑（损坏回落默认；JSON 兜底真源）。"""
    if not os.path.isfile(DEPARTMENTS_FILE):
        return {"list": list(DEFAULT_DEPARTMENTS)}
    try:
        with open(DEPARTMENTS_FILE, encoding="utf-8") as f:
            d = json.load(f)
    except Exception:  # noqa: BLE001 —— 文件损坏时回落默认，不让坏文件拖垮服务
        return {"list": list(DEFAULT_DEPARTMENTS)}
    if not isinstance(d.get("list"), list):
        d["list"] = list(DEFAULT_DEPARTMENTS)
    return d


def _load() -> dict:
    # B1 数据真源化：PG 池可用 → PG 真源；不可用/异常/空表 → JSON（现状行为完全等价）
    return pg_store.load_departments(DEPARTMENTS_FILE, _load_json)


def _save(d: dict) -> None:
    # B1：JSON 原子写兜底 + PG 全量同步（best-effort）；路径取本模块常量（测试 monkeypatch 生效）
    pg_store.save_departments(d, DEPARTMENTS_FILE)


def list_departments() -> list[str]:
    """科室列表；文件不存在则首次自动创建默认。"""
    with _lock:
        d = _load()
        if not os.path.isfile(DEPARTMENTS_FILE):
            _save(d)
        return list(d["list"])


def add_department(name: str) -> bool:
    """新增科室；重名返回 False（路由转 400），空名抛 ValueError。"""
    name = (name or "").strip()
    if not name:
        raise ValueError("科室名不能为空")
    with _lock:
        d = _load()
        if name in d["list"]:
            return False
        d["list"].append(name)
        _save(d)
    return True


def remove_department(name: str) -> bool:
    """删除科室；不存在返回 False（路由转 404）。
    仍有账号绑定该科室时抛 ValueError（路由转 400）：科室字典是账号 dept / 质控科室的
    权威取值来源，直接删除会让在用账号的科室变成悬空值。"""
    name = (name or "").strip()
    with _lock:
        d = _load()
        if name not in d["list"]:
            return False
    from backend.core.auth import all_users  # 延迟导入，避免模块加载环
    if any((rec or {}).get("dept", "") == name for rec in all_users().values()):
        raise ValueError(f"科室「{name}」仍有账号使用，请先调整相关账号的科室后再删除")
    with _lock:
        d = _load()  # 双检：在用校验期间列表可能被并发增删
        if name not in d["list"]:
            return False
        d["list"].remove(name)
        _save(d)
    return True
