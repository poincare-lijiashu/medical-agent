"""轮 B2 Store 抽象单元：MemoryStore 全语义、工厂选择（REDIS_URL）、RedisStore 降级回落、三组件接入回归。

对应 backend/core/stores.py 设计：
- KVStore 协议最小面 get/set/ttl/incr/expire/delete；
- MemoryStore 线程安全内存后端（默认路径）；
- RedisStore 懒 import redis（未装包不报错）+ 每操作 try/except 失败回落内存镜像
  （可用性优先，首次失败 warn redis.degraded 一次）；
- 工厂 get_store()：settings.redis_url 非空 → RedisStore，否则 MemoryStore（模块级单例）。

既有默认内存路径（限流/会话/撤销）行为零改变由 test_security / test_chat_memory /
test_auth* 全绿锁定，此处不重复断言。
"""
from __future__ import annotations

import sys
import threading
import time
import types

import pytest
from fastapi import HTTPException

from backend.api import deps
from backend.config import settings
from backend.core import chat_memory, security_rate, stores


class _FakeRedisClient:
    """不触网的假 redis 客户端：dict 存储 + 调用记录（验证 RedisStore 确实走 client）。"""

    def __init__(self) -> None:
        self.data: dict[str, str] = {}
        self.expires: dict[str, float] = {}  # key → 绝对到期时刻（monotonic）

    def set(self, key, value):
        self.data[key] = value
        self.expires.pop(key, None)

    def setex(self, key, seconds, value):
        self.data[key] = value
        self.expires[key] = time.monotonic() + max(1, int(seconds))

    def get(self, key):
        exp = self.expires.get(key)
        if exp is not None and time.monotonic() >= exp:
            self.data.pop(key, None)
            self.expires.pop(key, None)
        return self.data.get(key)

    def expire(self, key, seconds):
        if key not in self.data:
            return False
        self.expires[key] = time.monotonic() + max(1, int(seconds))
        return True

    def ttl(self, key):
        if key not in self.data or key not in self.expires:
            return 0
        return max(0, int(self.expires[key] - time.monotonic()))

    def incrby(self, key, amount=1):
        v = int(self.data.get(key) or 0) + int(amount)
        self.data[key] = str(v)  # 与 Redis 一致：INCRBY 后以字符串形式存储
        return v

    def delete(self, key):
        return 1 if self.data.pop(key, None) is not None else 0


@pytest.fixture
def fake_redis(monkeypatch):
    """注入假 redis 模块（不触网），返回假客户端；配合 reset_store 供 RedisStore 使用。"""
    client = _FakeRedisClient()
    mod = types.ModuleType("redis")
    mod.Redis = types.SimpleNamespace(from_url=lambda *a, **kw: client)
    monkeypatch.setitem(sys.modules, "redis", mod)
    yield client


@pytest.fixture(autouse=True)
def _fresh_store():
    """每个用例独立的 store 单例：不串测试状态（conftest 已全局清理，此处双保险）。"""
    stores.reset_store()
    yield
    stores.reset_store()


# ---------- 1. MemoryStore 全语义 ----------


class TestMemoryStore:
    def test_get_set_roundtrip_with_default(self):
        s = stores.MemoryStore()
        assert s.get("k") is None
        assert s.get("k", "dft") == "dft"
        s.set("k", {"a": 1})
        assert s.get("k") == {"a": 1}  # 进程内对象原样存取（不序列化）

    def test_set_with_ttl_then_get_returns_default_after_expiry(self):
        s = stores.MemoryStore()
        s.set("k", "v", ttl=0.02)
        assert s.get("k") == "v"
        time.sleep(0.05)
        assert s.get("k") is None  # 到期自动回收
        assert s.get("k", "dft") == "dft"

    def test_ttl_semantics(self):
        s = stores.MemoryStore()
        assert s.ttl("nope") == 0          # 不存在 → 0
        s.set("k1", "v")                    # 无 TTL → 0
        assert s.ttl("k1") == 0
        s.set("k2", "v", ttl=100)
        assert 0 < s.ttl("k2") <= 100      # 有 TTL → 剩余秒

    def test_incr_start_at_one_and_amount(self):
        s = stores.MemoryStore()
        assert s.incr("n") == 1            # 不存在从 0 起算
        assert s.incr("n") == 2
        assert s.incr("n", amount=10) == 12

    def test_incr_on_expired_key_starts_fresh(self):
        s = stores.MemoryStore()
        s.set("n", 5, ttl=0.02)
        time.sleep(0.05)
        assert s.incr("n") == 1            # 过期键重新计数（INCRBY 语义）

    def test_incr_keeps_existing_ttl(self):
        s = stores.MemoryStore()
        s.set("n", 1, ttl=100)
        s.incr("n")
        assert s.ttl("n") > 0              # INCRBY 不清除 TTL（与 Redis 一致）

    def test_expire_extends_and_missing_returns_false(self):
        s = stores.MemoryStore()
        s.set("k", "v", ttl=50)
        assert s.expire("k", 100) is True
        assert s.ttl("k") > 50             # 续期生效
        assert s.expire("nope", 100) is False

    def test_delete_returns_presence(self):
        s = stores.MemoryStore()
        s.set("k", "v")
        assert s.delete("k") is True
        assert s.get("k") is None
        assert s.delete("k") is False      # 再删不存在 → False

    def test_set_overwrite_without_ttl_clears_old_ttl(self):
        s = stores.MemoryStore()
        s.set("k", "v", ttl=100)
        s.set("k", "v2")                   # 覆盖写不带 TTL → 旧 TTL 清除（SET 语义）
        assert s.ttl("k") == 0
        assert s.get("k") == "v2"

    def test_thread_safety_incr(self):
        s = stores.MemoryStore()

        def worker():
            for _ in range(200):
                s.incr("counter")

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        assert s.get("counter") == 1600    # 无锁竞争丢计数


# ---------- 2. 工厂 get_store：REDIS_URL 选择后端 ----------


class TestGetStoreFactory:
    def test_default_empty_url_selects_memory(self, monkeypatch):
        monkeypatch.setattr(settings, "redis_url", "")
        assert isinstance(stores.get_store(), stores.MemoryStore)

    def test_singleton_until_reset(self, monkeypatch):
        monkeypatch.setattr(settings, "redis_url", "")
        assert stores.get_store() is stores.get_store()  # 模块级单例
        first = stores.get_store()
        stores.reset_store()
        assert stores.get_store() is not first           # reset 后按配置重建

    def test_nonempty_url_selects_redis_store(self, monkeypatch, fake_redis):
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        st = stores.get_store()
        assert isinstance(st, stores.RedisStore)
        st.set("k", "v")                       # 经假 client（不触网）
        st.set("kt", "v", ttl=60)
        assert fake_redis.data                 # 确认操作落在了 redis client 上
        assert st.get("k") == "v"
        assert st.get("kt") == "v"
        assert 0 < st.ttl("kt") <= 60
        assert st.incr("n") == 1
        assert st.incr("n") == 2
        assert st.expire("k", 120) is True
        assert st.delete("k") is True
        assert st.delete("k") is False
        assert st.get("k") is None

    def test_redis_package_missing_never_raises(self, monkeypatch):
        """未安装 redis 包：RedisStore 不可用但不报错，操作回落内存镜像。"""
        monkeypatch.setitem(sys.modules, "redis", None)  # import redis → ImportError
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        st = stores.get_store()
        assert isinstance(st, stores.RedisStore)
        st.set("k", "v")                       # 全程不抛
        st.set("kt", "v", ttl=60)
        assert st.get("k") == "v"
        assert st.get("kt") == "v"
        assert st.incr("n") == 1
        assert st.delete("k") is True


# ---------- 3. RedisStore 异常回落内存镜像 + degraded 告警一次 ----------


class TestRedisDegradedFallback:
    def test_ops_fall_back_and_warn_once(self, monkeypatch, caplog):
        class _Broken:
            def __getattr__(self, name):
                raise ConnectionError("mock redis down")

        mod = types.ModuleType("redis")
        mod.Redis = types.SimpleNamespace(from_url=lambda *a, **kw: _Broken())
        monkeypatch.setitem(sys.modules, "redis", mod)
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")

        st = stores.get_store()
        with caplog.at_level("WARNING"):
            # 每次操作 Redis 失败 → 内存镜像兜底，结果正确、绝不抛
            st.set("k", "v", ttl=100)
            assert st.get("k") == "v"
            assert st.get("k", "dft") == "v"
            assert st.incr("n") == 1
            assert st.incr("n") == 2
            assert st.expire("n", 60) is True
            assert st.ttl("n") > 0
            assert st.delete("k") is True
            assert st.get("k") is None
            assert st.expire("nope", 10) is False
        degraded = [r for r in caplog.records if "redis.degraded" in r.getMessage()]
        assert len(degraded) == 1, "首次失败 warn 一次，后续静默回落（防日志风暴）"


# ---------- 3b. 第 66 批：降级冷却（Redis 挂掉后不得每请求吃满连接超时） ----------


class TestRedisDegradedCooldown:
    def test_no_retry_within_cooldown(self, monkeypatch):
        """失败后冷却期内**完全不触网**（否则每个请求都要等 socket_connect_timeout）。"""
        attempts = {"n": 0}

        class _Broken:
            def __getattr__(self, name):
                attempts["n"] += 1
                raise ConnectionError("mock redis down")

        mod = types.ModuleType("redis")
        mod.Redis = types.SimpleNamespace(from_url=lambda *a, **kw: _Broken())
        monkeypatch.setitem(sys.modules, "redis", mod)
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        st = stores.get_store()

        st.set("k", "v", ttl=60)          # 第 1 次：触网失败 → 进冷却
        first = attempts["n"]
        assert first >= 1
        for _ in range(9):
            assert st.get("k") == "v"     # 冷却期内：走内存镜像，结果仍正确
            st.incr("n")
        assert attempts["n"] == first, "冷却期内不得再尝试连接 Redis"
        assert st._degraded_until > time.monotonic() - stores._DEGRADED_COOLDOWN_S

    def test_retry_after_cooldown_and_log_recovered(self, monkeypatch, caplog):
        class _Broken:
            def __getattr__(self, name):
                raise ConnectionError("mock redis down")

        mod = types.ModuleType("redis")
        mod.Redis = types.SimpleNamespace(from_url=lambda *a, **kw: _Broken())
        monkeypatch.setitem(sys.modules, "redis", mod)
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        st = stores.get_store()

        with caplog.at_level("WARNING"):
            st.set("k", "v")
        assert [r for r in caplog.records if "redis.degraded" in r.getMessage()]

        # 模拟"冷却结束 + Redis 已恢复"：换回健康客户端
        good = _FakeRedisClient()
        st._client = good
        st._degraded_until = 0.0
        with caplog.at_level("INFO"):
            st.set("k2", "v2")
        assert good.data.get("k2") is not None, "恢复后应重新走共享存储"
        assert [r for r in caplog.records if "redis.recovered" in r.getMessage()], \
            "恢复时记一条 redis.recovered"
        assert st._degraded_logged is False


# ---------- 3c. 第 67 批：共享存储可观测指标 ----------


class TestRedisMetrics:
    def test_memory_backend_exports_disabled(self, monkeypatch):
        monkeypatch.setattr(settings, "redis_url", "")
        stores.reset_store()
        text = stores.prometheus_text()
        assert "medassist_agent_redis_enabled 0" in text
        assert "medassist_agent_redis_degraded 0" in text
        assert "medassist_agent_redis_import_missing 0" in text
        assert stores.redis_status() == {"enabled": False, "degraded": False,
                                         "importMissing": False}

    def test_redis_healthy_exports_enabled_not_degraded(self, monkeypatch, fake_redis):
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        stores.reset_store()
        stores.get_store().set("k", "v")     # 走一次成功路径
        text = stores.prometheus_text()
        assert "medassist_agent_redis_enabled 1" in text
        assert "medassist_agent_redis_degraded 0" in text

    def test_degraded_is_exported_after_failure(self, monkeypatch):
        class _Broken:
            def __getattr__(self, name):
                raise ConnectionError("mock redis down")

        mod = types.ModuleType("redis")
        mod.Redis = types.SimpleNamespace(from_url=lambda *a, **kw: _Broken())
        monkeypatch.setitem(sys.modules, "redis", mod)
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        stores.reset_store()
        stores.get_store().set("k", "v")     # 失败 → 进入降级冷却
        assert stores.redis_status()["degraded"] is True
        assert "medassist_agent_redis_degraded 1" in stores.prometheus_text()

    def test_import_missing_is_visible(self, monkeypatch):
        """文档点名的坑：配了 REDIS_URL 但没装 redis 包 → 必须可观测，而不是静默。"""
        monkeypatch.setitem(sys.modules, "redis", None)
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        stores.reset_store()
        assert stores.redis_status() == {"enabled": True, "degraded": True,
                                         "importMissing": True}
        text = stores.prometheus_text()
        assert "medassist_agent_redis_import_missing 1" in text
        assert "medassist_agent_redis_degraded 1" in text


# ---------- 4. 三组件接入回归（共享语义；默认内存路径由既有测试锁定） ----------


class TestComponentIntegration:
    def test_revoke_user_writes_shared_store_and_blocks_old_tokens(self):
        stores.reset_store()
        deps.revoke_user("alice")
        ts = stores.get_store().get("revoke:alice")
        assert ts is not None and ts > 0
        deps.ensure_not_revoked("alice", ts + 1)   # 新签发令牌有效（不抛）
        with pytest.raises(HTTPException):
            deps.ensure_not_revoked("alice", ts - 1)  # 旧令牌拒绝
        # 模拟另一实例：本地撤销表为空，仅凭共享存储也应拒绝（多实例互通核心语义）
        deps._revoked_before.clear()
        with pytest.raises(HTTPException):
            deps.ensure_not_revoked("alice", ts - 1)

    def test_chat_memory_roundtrip_via_shared_store(self):
        stores.reset_store()
        chat_memory.reset()
        chat_memory.remember("s1", "q1", "a1")
        # MemoryStore 原样存对象（不序列化）；JSON 形态仅 Redis 路径（见工厂用例）
        assert stores.get_store().get("chat:s1") == [("q1", "a1")]
        # 模拟另一实例写入 → 本实例本地未命中时从共享存储读回
        stores.get_store().set("chat:s2", [["qx", "ax"], ["qy", "ay"]])
        assert chat_memory.recall("s2") == [("qx", "ax"), ("qy", "ay")]

    def test_chat_memory_local_hit_takes_precedence(self):
        stores.reset_store()
        chat_memory.reset()
        chat_memory.remember("s1", "q1", "a1")
        stores.get_store().set("chat:s1", [["stale", "stale"]])  # 共享端旧数据
        assert chat_memory.recall("s1") == [("q1", "a1")]        # 本地命中优先

    def test_chat_memory_lru_eviction_removes_shared_entry(self, monkeypatch):
        """被 LRU 淘汰的会话 recall 仍返回 []（与既有语义一致）：淘汰时同步删共享键。"""
        stores.reset_store()
        chat_memory.reset()
        monkeypatch.setattr(chat_memory, "MAX_SESSIONS", 1)
        chat_memory.remember("s1", "q1", "a1")
        chat_memory.remember("s2", "q2", "a2")  # 超 MAX_SESSIONS → s1 淘汰
        assert stores.get_store().get("chat:s1") is None
        assert chat_memory.recall("s1") == []
        assert chat_memory.recall("s2") == [("q2", "a2")]

    def test_rate_limit_shared_counting_via_redis(self, monkeypatch, fake_redis):
        """REDIS_URL 非空：限流计数走共享存储（多实例聚合计数），超限 429 语义不变。"""
        monkeypatch.setattr(settings, "redis_url", "redis://mock:6379/0")
        stores.reset_store()
        assert security_rate.allow("u1", limit=2, window=60) is True
        assert security_rate.allow("u1", limit=2, window=60) is True
        assert security_rate.allow("u1", limit=2, window=60) is False  # 第 3 次超限
        assert any(k.startswith("rl:") for k in fake_redis.data)       # 计数确实落共享存储
        assert security_rate.allow("u2", limit=2, window=60) is True   # 其它 key 不受影响


def test_memory_rate_sweeper_survives_many_keys(monkeypatch):
    """回归（评测 C5 lint 发现）：键数 >256 时清扫路径曾因缺 global 抛 UnboundLocalError。

    限流是登录/问答的第一道门——这条路径一抛错就是全院 500，且只在"IP 桶超过 256 个"
    的压测规模下才触发，常规单测永远走不到。F823 由 ruff 静态规则捕获，这里补行为锁。
    """
    monkeypatch.setattr(settings, "redis_url", "")
    stores.reset_store()
    security_rate.reset()
    for i in range(300):
        security_rate._hits[f"ip:10.0.{i // 200}.{i % 200}"].append(0.0)  # 全部是过期桶
    assert security_rate.allow("ip:203.0.113.9", limit=60, window=60) is True
    assert security_rate._last_sweep > 0            # 清扫时间已写回模块变量
    assert len(security_rate._hits) < 300           # 过期桶被回收，内存不会无界增长
