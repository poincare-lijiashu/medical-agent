"""科室字典管理：data/departments.json（_lock + 原子写）。

质控的「科室」字段由服务端绑定账号 dept，本科室字典是 dept 的权威取值来源：
- 文件不存在时首次访问自动写入默认科室；
- add 重名返回 False（路由转 400）；remove 校验存在（否则 False → 路由 404）。
"""
from __future__ import annotations

import json
import os
import threading

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEPARTMENTS_FILE = os.path.join(BASE_DIR, "data", "departments.json")
_lock = threading.Lock()

DEFAULT_DEPARTMENTS = ["内科", "外科", "口腔科", "妇产科", "儿科", "急诊科"]


def _load() -> dict:
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


def _save(d: dict) -> None:
    os.makedirs(os.path.dirname(DEPARTMENTS_FILE), exist_ok=True)
    tmp = DEPARTMENTS_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    os.replace(tmp, DEPARTMENTS_FILE)


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
    """删除科室；不存在返回 False（路由转 404）。"""
    name = (name or "").strip()
    with _lock:
        d = _load()
        if name not in d["list"]:
            return False
        d["list"].remove(name)
        _save(d)
    return True
