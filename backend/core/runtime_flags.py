"""运行时开关层（任务3）：admin 面板运行时切换「留痕模式」等行为开关。

背景：settings（backend/config.py）只在进程启动时读一次 .env.local，运行中改 .env
不会生效。本层用 data/runtime_flags.json 存储运行时覆盖值，作为独立 flags 文件层：
- 读：flags 文件存在且含该键 → 以 flags 为准；否则回落 settings 启动值（向后兼容，
  已有用 monkeypatch settings 的既有测试语义不变）。
- 写：仅 POST /medical/admin/config-toggle（admin 权限）调用；原子写（tmp+replace）；
  开关键走白名单，防止任意键写入。
- 测试隔离：FLAGS_FILE 为模块级常量，conftest autouse fixture 将其指向 tmp_path。
"""
from __future__ import annotations

import json
import os
import threading

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FLAGS_FILE = os.path.join(BASE_DIR, "data", "runtime_flags.json")

# 可运行时切换的开关白名单（任务3：目前仅留痕模式；后续新开关在此登记）
ALLOWED_FLAGS = ("qc_auto_sign_full",)

_lock = threading.Lock()


def _load() -> dict:
    """读 flags 文件；文件缺失/损坏 → 空 dict（全部回落 settings 启动值）。"""
    try:
        if not os.path.isfile(FLAGS_FILE):
            return {}
        with open(FLAGS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:  # noqa: BLE001 —— flags 文件损坏绝不拖垮主链路，回落启动值
        return {}


def get_flag(key: str, default: bool) -> bool:
    """读单个开关：flags 文件优先；无键/非白名单/文件缺失 → default（settings 启动值）。"""
    if key not in ALLOWED_FLAGS:
        return default
    return bool(_load().get(key, default))


def set_flag(key: str, value: bool) -> bool:
    """写单个开关（原子写 + 白名单校验）；返回落盘后的值。非法键抛 ValueError。"""
    if key not in ALLOWED_FLAGS:
        raise ValueError(f"不支持的运行时开关：{key}")
    with _lock:
        d = _load()
        d[key] = bool(value)
        os.makedirs(os.path.dirname(FLAGS_FILE), exist_ok=True)
        tmp = FLAGS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
        os.replace(tmp, FLAGS_FILE)
    return bool(value)


def full_mode() -> bool:
    """「留痕模式」权威读取口（全部决策点统一走这里，绝不直接读 settings）：
    runtime_flags.json 优先（admin 运行时切换即时生效），无键回落 settings.qc_auto_sign_full
    （启动值，既有 monkeypatch 测试语义不变）。"""
    from backend.config import settings
    return get_flag("qc_auto_sign_full", settings.qc_auto_sign_full)
