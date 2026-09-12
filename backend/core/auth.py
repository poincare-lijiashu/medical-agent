"""鉴权核心：pbkdf2 密码哈希 + PyJWT 令牌 + 基于 JSON 的用户表 + 角色。

设计取舍：
- 密码用 stdlib hashlib.pbkdf2_hmac（避开 passlib/bcrypt 后端读取坑）。
- 令牌用 PyJWT HS256，含 sub(username)/role/exp。
- 用户存取走 pg_store repo 层（B1 数据真源化）：PG 池可用 → PG 真源（users 表含 dept 列）；
  PG 不可用/异常/空表 → data/users.json（现状行为完全等价）。写入始终 JSON 兜底 + PG 同步。
- 角色：doctor / pharmacist / admin / qc（质控员）。medical 端点要求有效令牌；写审计含操作者。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import secrets
import sys
import threading
from datetime import datetime, timedelta, timezone
from typing import Optional

import jwt

from backend.config import settings
from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
USERS_FILE = os.path.join(_BASE_DIR, "data", "auth", "users.json")
_users_lock = threading.Lock()  # users.json 读-改-写临界区保护（防并发丢更新）

_ITERATIONS = 600_000  # OWASP 建议 PBKDF2-HMAC-SHA256 ≥600k；旧哈希按其存储的迭代数兼容校验，登录成功后透明升级
_ALGO = "sha256"

# 允许访问的角色（全部为医疗专业角色；无匿名访问）。qc=质控员（E 角色细分：质控工作台入口，
# 医生端行为完全不变；后端 review 端点白名单见 medical_router require_role）
ROLES = {"doctor", "pharmacist", "admin", "qc"}


# ---------- 密码 ----------
def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac(_ALGO, password.encode("utf-8"), bytes.fromhex(salt), _ITERATIONS)
    return f"pbkdf2${_ALGO}${_ITERATIONS}${salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, algo, iters, salt, hashhex = stored.split("$")
        if scheme != "pbkdf2":
            return False
        dk = hashlib.pbkdf2_hmac(algo, password.encode("utf-8"), bytes.fromhex(salt), int(iters))
        return hmac.compare_digest(dk.hex(), hashhex)
    except Exception:  # noqa: BLE001
        return False


# ---------- 令牌 ----------
def create_access_token(username: str, role: str) -> str:
    now = datetime.now(timezone.utc)
    payload = {
        "sub": username,
        "role": role,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=settings.auth_token_expire_minutes)).timestamp()),
    }
    return jwt.encode(payload, settings.auth_jwt_secret, algorithm="HS256")


def decode_token(token: str) -> Optional[dict]:
    try:
        payload = jwt.decode(token, settings.auth_jwt_secret, algorithms=["HS256"])
        if payload.get("typ") == "refresh":
            return None  # 刷新令牌不能当访问令牌用
        return payload
    except jwt.PyJWTError:
        return None


def create_refresh_token(username: str) -> str:
    now = datetime.now(timezone.utc)
    payload = {"sub": username, "typ": "refresh",
               "iat": int(now.timestamp()),
               "exp": int((now + timedelta(minutes=settings.auth_refresh_expire_minutes)).timestamp())}
    return jwt.encode(payload, settings.auth_jwt_secret, algorithm="HS256")


def decode_refresh_token(token: str) -> Optional[str]:
    try:
        payload = jwt.decode(token, settings.auth_jwt_secret, algorithms=["HS256"])
        return payload["sub"] if payload.get("typ") == "refresh" else None
    except jwt.PyJWTError:
        return None


def decode_refresh_payload(token: str) -> Optional[dict]:
    """刷新令牌解码为完整载荷（refresh 端点需 iat 做撤销比对）。"""
    try:
        payload = jwt.decode(token, settings.auth_jwt_secret, algorithms=["HS256"])
        return payload if payload.get("typ") == "refresh" else None
    except jwt.PyJWTError:
        return None


# ---------- 用户表 ----------
def _load_users_json() -> dict:
    """users.json 现状读逻辑（JSON 兜底真源；PG 不可用/异常/空表时的唯一数据源）。"""
    if not os.path.isfile(USERS_FILE):
        return {}
    with open(USERS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def _load_users() -> dict:
    # B1 数据真源化：PG 池可用 → PG 真源；不可用/异常/空表 → JSON（现状行为完全等价）
    return pg_store.load_users(USERS_FILE, _load_users_json)


def _save_users(users: dict) -> None:
    # B1：JSON 原子写兜底（降级安全）+ PG 全量同步（best-effort）；
    # 路径取本模块 USERS_FILE（测试 monkeypatch 该常量仍然生效）
    pg_store.save_users(users, USERS_FILE)


def get_user(username: str) -> Optional[dict]:
    return _load_users().get(username)


def all_users() -> dict:
    return _load_users()


def _needs_rehash(stored: str) -> bool:
    """存储哈希的迭代数低于当前标准时返回 True（登录成功后透明升级）。"""
    try:
        return int(stored.split("$")[2]) < _ITERATIONS
    except (IndexError, ValueError):
        return False


def authenticate(username: str, password: str) -> Optional[dict]:
    u = get_user(username)
    if not u or not verify_password(password, u["password_hash"]):
        return None
    if _needs_rehash(u["password_hash"]):
        set_password(username, password)  # 平滑升级到当前迭代数（不撤销令牌）
    return {"username": username, "role": u["role"]}


def create_user(username: str, password: str, role: str, overwrite: bool = False,
                dept: str = "") -> bool:
    """创建用户；dept 为所属科室（质控签名/科室绑定用，缺省空 = 未设置）。"""
    if role not in ROLES:
        raise ValueError(f"非法角色: {role}")
    with _users_lock:
        users = _load_users()
        if username in users and not overwrite:
            return False
        users[username] = {"role": role, "dept": (dept or "").strip(),
                           "password_hash": hash_password(password)}
        _save_users(users)
    return True


def delete_user(username: str) -> bool:
    with _users_lock:
        users = _load_users()
        if username not in users:
            return False
        del users[username]
        _save_users(users)
    return True


def set_password(username: str, new_password: str) -> bool:
    with _users_lock:
        users = _load_users()
        if username not in users:
            return False
        users[username]["password_hash"] = hash_password(new_password)
        _save_users(users)
    return True


def _console_only_password(pw: str) -> None:
    """把随机种子口令打印到控制台（外部审查 M1：仅开发环境调用）。

    用独立 logger + propagate=False 阻断向 root/共享文件 handler 传播，保证明文
    只出现在本次进程的控制台输出、绝不进入文件日志；StreamHandler 每次现挂当前
    sys.stdout（不缓存 logger/handler，兼容测试的输出捕获）。"""
    lg = logging.getLogger("backend.core.auth.demo_password_console")
    lg.setLevel(logging.WARNING)
    lg.propagate = False  # 关键：明文不得被任何父级（含共享文件 handler）捕获
    for h in lg.handlers:
        try:
            h.close()
        except Exception:  # noqa: BLE001 —— 关闭失败不影响本次输出
            pass
    h = logging.StreamHandler(sys.stdout)
    h.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
    lg.handlers = [h]
    lg.warning("auth.seed.demo_password_console | password=%s（仅控制台输出，不落文件）", pw)


def _demo_password() -> str:
    """种子账号口令选择（纯函数便于测试；历史命名兼容保留）：显式配置 AUTH_DEMO_PASSWORD 优先；
    未配置则随机生成（种子账号仅本地试用注入，生产不 seed）。

    安全纪律（外部审查 M1）：随机口令明文绝不写入文件日志——文件日志长期留存且
    可经 admin 日志视图读取，属敏感泄露面。改为：
    - 常规 logger 只记「已生成随机口令」事件（不带 password 值，控制台+文件均可见）；
    - 开发环境（app_env=development）额外经仅控制台 logger 打印一次口令明文——
      dev 流程仍靠看控制台拿口令；非开发环境不打印（生产不 seed，双保险）。"""
    if settings.auth_demo_password:
        return settings.auth_demo_password
    pw = secrets.token_urlsafe(12)
    from backend.core.logger import get_logger
    get_logger(__name__).warning("auth.seed.demo_password",
                                 env=settings.app_env,
                                 hint="随机口令仅在开发环境控制台打印一次（不落文件日志）")
    if settings.app_env == "development":
        _console_only_password(pw)
    return pw


def seed_default_users() -> None:
    """首次运行注入种子账号（登录后务必改密）。qc01 为质控员种子账号（E 角色细分）。"""
    users = _load_users()
    if users:
        return
    pw = _demo_password()
    defaults = [
        ("doctor01", pw, "doctor", "口腔科"),
        ("pharm01", pw, "pharmacist", "药剂科"),
        ("admin01", pw, "admin", "医务处"),
        ("qc01", pw, "qc", "医务处"),  # E：质控员（质控工作台/审核中心入口；口令同种子约定）
    ]
    for u, p, r, d in defaults:
        users[u] = {"role": r, "dept": d, "password_hash": hash_password(p)}
    _save_users(users)
