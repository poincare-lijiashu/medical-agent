"""登录爆破防护（评测 04 P1-6）：按「账号 + 客户端 IP」计连续失败并临时锁定。

为什么需要（与通用限流的分工）：
- `deps.rate_limit` 是 60 次/分钟的**请求**限流，防的是刷接口；登录前没有身份，
  只能按 IP 计。攻击者换 IP 或慢慢试（1 次/秒）时，一天可试 8 万次口令，足够撞开弱口令。
- 本模块按 (username, ip) 计**连续失败**：连续 `LOGIN_FAIL_LIMIT` 次（默认 5）后锁定
  `LOGIN_LOCK_S` 秒（默认 15 分钟），成功登录立即清零。

设计要点：
- **不区分"账号不存在"与"密码错误"**：两者都走同一计数/同一提示，避免账号枚举。
- 计数与锁定标记放 `stores.get_store()`（配置 `REDIS_URL` 后跨副本共享；
  未配置时进程内 MemoryStore，单副本可用）。每次调用都重新取 store，
  测试/运维 `reset_store()` 后立即生效，不会持有旧单例。
- 键形如 `secure:login:fail:<user>:<ip>` / `secure:login:lock:<user>:<ip>`；
  账号与 IP 都做长度截断与分隔符替换，避免键注入。
- `LOGIN_FAIL_LIMIT=0` 关闭整套防护（默认开启，不建议生产关闭）。
"""
from __future__ import annotations

from backend.config import settings
from backend.core import stores

_PREFIX = "secure:login:"
_MAX_PART = 96


def _part(value: str) -> str:
    """键片段：截断 + 去分隔符（避免 `a:b` 与 `a`+`b` 拼出同一个键）。"""
    return str(value or "").strip().lower()[:_MAX_PART].replace(":", "_")


def _fail_key(username: str, ip: str) -> str:
    return f"{_PREFIX}fail:{_part(username)}:{_part(ip)}"


def _lock_key(username: str, ip: str) -> str:
    return f"{_PREFIX}lock:{_part(username)}:{_part(ip)}"


def _limit() -> int:
    try:
        return max(0, int(getattr(settings, "login_fail_limit", 0) or 0))
    except (TypeError, ValueError):
        return 0


def _lock_s() -> int:
    try:
        return max(1, int(getattr(settings, "login_lock_s", 900) or 900))
    except (TypeError, ValueError):
        return 900


def _window_s() -> int:
    try:
        return max(60, int(getattr(settings, "login_fail_window_s", 900) or 900))
    except (TypeError, ValueError):
        return 900


def locked_seconds(username: str, ip: str) -> int:
    """当前是否处于锁定期：返回剩余秒数（0 = 未锁定/防护已关闭）。"""
    if _limit() <= 0:
        return 0
    try:
        return max(0, int(stores.get_store().ttl(_lock_key(username, ip)) or 0))
    except Exception:  # noqa: BLE001 —— 防护层故障绝不阻断登录（可用性优先）
        return 0


def record_failure(username: str, ip: str) -> int:
    """记一次失败；达到阈值时开锁并返回锁定时长（秒），否则返回 0。"""
    if _limit() <= 0:
        return 0
    store = stores.get_store()
    try:
        key = _fail_key(username, ip)
        n = int(store.incr(key) or 0)
        if n == 1:
            # 只给首次计数设 TTL：窗口内累计，窗口外自然归零（与 Redis INCR 语义一致）
            store.expire(key, _window_s())
        if n < _limit():
            return 0
        lock_s = _lock_s()
        store.set(_lock_key(username, ip), 1, ttl=lock_s)
        store.delete(key)  # 锁定后清零，解锁后重新从 0 计
        return lock_s
    except Exception:  # noqa: BLE001 —— 防护层故障不阻断登录
        return 0


def clear(username: str, ip: str) -> None:
    """登录成功：清空失败计数与锁定标记。"""
    if _limit() <= 0:
        return
    try:
        store = stores.get_store()
        store.delete(_fail_key(username, ip))
        store.delete(_lock_key(username, ip))
    except Exception:  # noqa: BLE001
        return


def status(username: str, ip: str) -> dict:
    """可观测/排障用：剩余锁定秒数与窗口内失败次数。"""
    try:
        store = stores.get_store()
        return {"lockedSeconds": locked_seconds(username, ip),
                "failures": int(store.get(_fail_key(username, ip), 0) or 0),
                "limit": _limit()}
    except Exception:  # noqa: BLE001
        return {"lockedSeconds": 0, "failures": 0, "limit": _limit()}
