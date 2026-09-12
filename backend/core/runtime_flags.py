"""运行时开关层（任务3；轮 A3 双写 PG）：admin 面板运行时切换「留痕模式」等行为开关。

存储双写（轮 A3）：**PG 真源优先，JSON 兜底保留**——
- 读：pg_store.load_runtime_flags（PG 池可用且域非 stale → PG 真源；不可用/空表/异常
  → 回落 JSON 兜底文件 data/runtime_flags.json）；flags 含该键 → 以 flags 为准；
  否则回落 settings 启动值（向后兼容，已有用 monkeypatch settings 的既有测试语义不变）。
- 写：仅 POST /medical/admin/config-toggle（admin 权限）调用；pg_store.save_runtime_flags
  = JSON 原子写先行（tmp+replace）+ PG 全量同步（best-effort，与 drug_dict 同纪律）；
  开关键走白名单，防止任意键写入。
- 接口不变：get_flag / set_flag / full_mode 签名与语义与轮 A 前完全一致。
- 测试隔离：FLAGS_FILE 为模块级常量，conftest autouse fixture 将其指向 tmp_path；
  测试进程无 PG 池 → 自动走 JSON 兜底，既有测试零回归。
"""
from __future__ import annotations

import json
import os
import threading

from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FLAGS_FILE = os.path.join(BASE_DIR, "data", "runtime_flags.json")

# 可运行时切换的开关白名单（任务3：目前仅留痕模式；后续新开关在此登记）
ALLOWED_FLAGS = ("qc_auto_sign_full",)

_lock = threading.Lock()


def _load() -> dict:
    """JSON 兜底读（pg_store json_loader 回调用）：文件缺失/损坏 → 空 dict（全部回落 settings）。"""
    try:
        if not os.path.isfile(FLAGS_FILE):
            return {}
        with open(FLAGS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:  # noqa: BLE001 —— flags 文件损坏绝不拖垮主链路，回落启动值
        return {}


def _load_all() -> dict:
    """统一读口（轮 A3）：PG 真源优先（池可用且非 stale），回落 JSON 兜底文件（现状行为）。"""
    return pg_store.load_runtime_flags(json_loader=_load, path=FLAGS_FILE)


def get_flag(key: str, default: bool) -> bool:
    """读单个开关：flags（PG→JSON）优先；无键/非白名单/缺失 → default（settings 启动值）。"""
    if key not in ALLOWED_FLAGS:
        return default
    return bool(_load_all().get(key, default))


def set_flag(key: str, value: bool) -> bool:
    """写单个开关（白名单校验 + JSON 原子写与 PG 全量同步双落）；返回落盘后的值。非法键抛 ValueError。"""
    if key not in ALLOWED_FLAGS:
        raise ValueError(f"不支持的运行时开关：{key}")
    with _lock:
        d = _load_all()
        d[key] = bool(value)
        pg_store.save_runtime_flags(d, FLAGS_FILE)  # 轮 A3：JSON 先落 + PG best-effort 同步
    return bool(value)


def full_mode() -> bool:
    """「留痕模式」权威读取口（全部决策点统一走这里，绝不直接读 settings）：
    runtime_flags（PG 真源→JSON 兜底）优先（admin 运行时切换即时生效），无键回落
    settings.qc_auto_sign_full（启动值，既有 monkeypatch 测试语义不变）。"""
    from backend.config import settings
    return get_flag("qc_auto_sign_full", settings.qc_auto_sign_full)
