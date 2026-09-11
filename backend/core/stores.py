"""轮 B2 可选双后端 KV 存储：三处进程内存态组件（限流计数/会话记忆/令牌撤销时间戳）的共享存储抽象。

设计要点：
- KVStore 协议只暴露最小面（get/set/ttl/incr/expire/delete）——调用方零学习成本，测试面小。
- MemoryStore：线程安全内存实现（默认路径，单机零配置零依赖）。
- RedisStore：多实例部署用（REDIS_URL 非空启用）。懒 import redis（未装包/连接失败
  一律不报错、不中断服务），每次操作 try/except，失败回落进程内内存镜像（可用性
  优先）；首次失败打一条 redis.degraded 告警（仅一次，防日志风暴），恢复后自动重连。
- 工厂 get_store()：settings.redis_url 非空 → RedisStore，否则 MemoryStore（模块级
  单例）；reset_store() 供测试重建单例。
- 值约定：set/get 走 JSON 序列化（跨进程可共享，仅支持 JSON 可序列化类型）；
  incr 仅用于整数计数；ttl(key) 返回剩余秒（0 = 无过期或不存在，两种后端语义统一）。
"""
from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Protocol, runtime_checkable

from backend.config import settings

logger = logging.getLogger(__name__)


@runtime_checkable
class KVStore(Protocol):
    """最小 KV 面：三组件（限流/会话记忆/令牌撤销）共同需要的全部原语。"""

    def get(self, key: str, default: Any = None) -> Any: ...

    def set(self, key: str, value: Any, ttl: float | None = None) -> None: ...

    def ttl(self, key: str) -> int: ...

    def incr(self, key: str, amount: int = 1) -> int: ...

    def expire(self, key: str, seconds: float) -> bool: ...

    def delete(self, key: str) -> bool: ...


class MemoryStore:
    """线程安全内存 KV（默认单机后端）。

    语义：值原样存取（进程内对象，不序列化）；TTL 以单调时钟计（不受系统改时影响）；
    ttl() 返回剩余秒（向上取整），不存在/无过期统一返回 0；incr 对过期键从 0 重新
    起算且不清除既有 TTL（与 Redis INCRBY 对齐）。
    """

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}
        self._expires: dict[str, float] = {}  # key → 绝对到期时刻（time.monotonic()）
        self._lock = threading.Lock()

    def _expired_locked(self, key: str) -> bool:
        exp = self._expires.get(key)
        return exp is not None and time.monotonic() >= exp

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            if self._expired_locked(key):
                self._data.pop(key, None)
                self._expires.pop(key, None)
                return default
            return self._data.get(key, default)

    def set(self, key: str, value: Any, ttl: float | None = None) -> None:
        with self._lock:
            self._data[key] = value
            if ttl is not None:
                self._expires[key] = time.monotonic() + max(0.0, float(ttl))
            else:
                self._expires.pop(key, None)  # 覆盖写不带 TTL → 清除旧 TTL（SET 语义）

    def ttl(self, key: str) -> int:
        with self._lock:
            if self._expired_locked(key):
                return 0
            exp = self._expires.get(key)
            if exp is None or key not in self._data:
                return 0
            return int(-(-(exp - time.monotonic()) // 1))  # 剩余秒向上取整

    def incr(self, key: str, amount: int = 1) -> int:
        with self._lock:
            if self._expired_locked(key):
                self._data.pop(key, None)
                self._expires.pop(key, None)
            try:
                base = int(self._data.get(key, 0))
            except (TypeError, ValueError):
                base = 0
            value = base + int(amount)
            self._data[key] = value  # incr 不动 TTL（与 Redis INCRBY 一致）
            return value

    def expire(self, key: str, seconds: float) -> bool:
        with self._lock:
            if self._expired_locked(key) or key not in self._data:
                return False
            self._expires[key] = time.monotonic() + max(0.0, float(seconds))
            return True

    def delete(self, key: str) -> bool:
        with self._lock:
            if self._expired_locked(key):
                self._data.pop(key, None)
                self._expires.pop(key, None)
                return False
            return self._data.pop(key, _sentinel) is not _sentinel


_sentinel = object()


class RedisStore:
    """Redis 后端（多实例共享）：所有操作失败回落进程内内存镜像（可用性优先）。

    - 懒 import redis：仅在第一次构造时尝试；未装包 → _import_failed，操作全部走
      内存镜像，结果正确、不报错（单实例语义降级，服务不中断）。
    - 每操作 try/except：网络抖动/命令失败 → 本实例内存镜像兜底；连接对象置空，
      下次操作自动重连。首次失败 warn「redis.degraded」一条，之后静默回落。
    - 值统一 JSON 序列化（跨进程可读）；incr 走 INCRBY 纯整数；ttl 归一化到
      「0 = 无过期或不存在」（与 MemoryStore 语义一致）。
    """

    def __init__(self, url: str) -> None:
        self._url = url
        self._client = None           # 懒建（redis-py 懒连接，构造不触网）
        self._import_failed = False   # 未安装 redis 包 → 永久走内存镜像
        self._degraded_logged = False
        self._fallback = MemoryStore()  # 内存镜像（降级期间的本地兜底）
        try:
            import redis  # noqa: F401  懒 import：仅 RedisStore 构造时触发
        except ImportError:
            self._import_failed = True

    def _get_client(self):
        if self._client is None:
            import redis
            self._client = redis.Redis.from_url(
                self._url, decode_responses=True,
                socket_connect_timeout=2, socket_timeout=2)
        return self._client

    def _exec(self, redis_fn, fallback_fn):
        """统一执行：Redis 失败（未装包/网络/命令异常）→ 内存镜像兜底，结果对调用方一致。"""
        if not self._import_failed:
            try:
                return redis_fn(self._get_client())
            except Exception:
                self._client = None  # 连接池可能已坏，下次操作重建
                if not self._degraded_logged:
                    self._degraded_logged = True
                    logger.warning(
                        "redis.degraded: Redis 不可用，本实例回落进程内内存镜像"
                        "（多实例共享语义降级为单实例）；恢复后自动重连")
        return fallback_fn()

    def get(self, key: str, default: Any = None) -> Any:
        def via_redis(client):
            v = client.get(key)
            if v is None:
                return default
            try:
                return json.loads(v)
            except (TypeError, ValueError):
                return v  # 非 JSON（如 INCRBY 写入的裸字符串）原样返回

        return self._exec(via_redis, lambda: self._fallback.get(key, default))

    def set(self, key: str, value: Any, ttl: float | None = None) -> None:
        payload = json.dumps(value, ensure_ascii=False)

        def via_redis(client):
            if ttl is not None:
                client.setex(key, max(1, int(round(ttl))), payload)
            else:
                client.set(key, payload)

        self._exec(via_redis, lambda: self._fallback.set(key, value, ttl=ttl))

    def ttl(self, key: str) -> int:
        def via_redis(client):
            return max(0, int(client.ttl(key) or 0))  # -1/-2 归一化为 0

        return self._exec(via_redis, lambda: self._fallback.ttl(key))

    def incr(self, key: str, amount: int = 1) -> int:
        def via_redis(client):
            return int(client.incrby(key, amount))

        return self._exec(via_redis, lambda: self._fallback.incr(key, amount))

    def expire(self, key: str, seconds: float) -> bool:
        def via_redis(client):
            return bool(client.expire(key, max(1, int(round(seconds)))))

        return self._exec(via_redis, lambda: self._fallback.expire(key, seconds))

    def delete(self, key: str) -> bool:
        def via_redis(client):
            return bool(client.delete(key))

        return self._exec(via_redis, lambda: self._fallback.delete(key))


# 模块级单例：进程内所有组件共用一个 store 实例
_store_instance: KVStore | None = None


def get_store() -> KVStore:
    """按配置返回共享 KV 后端：REDIS_URL 非空 → RedisStore，否则 MemoryStore。"""
    global _store_instance
    if _store_instance is None:
        url = (settings.redis_url or "").strip()
        _store_instance = RedisStore(url) if url else MemoryStore()
    return _store_instance


def reset_store() -> None:
    """测试钩子：清空单例（下次 get_store 按当前配置重建），保证测试间状态隔离。"""
    global _store_instance
    _store_instance = None
