"""统一身份同步：从 Java 认证服务拉取"令牌撤销"与"账号停用"清单（第 129 批）。

为什么需要本模块
----------------
Java 医院平台（platform/auth）是唯一身份签发方，AI 中台用同一 HS256 密钥验签，
因此登录态天然互通。但 JWT 是无状态的，**验签通过 ≠ 账号仍然有效**：

- 用户在 Java 侧登出 / 被管理员踢下线 → 该 jti 进入撤销名单；
- 管理员停用账号 → 只撤销刷新令牌，已签发的访问令牌在 TTL（默认 30 分钟）内仍可验签。

不补这一层，就会出现"Java 已停用、AI 中台还能用"的不同步窗口。本模块按固定周期
（``IDENTITY_SYNC_SECONDS``，默认 15 秒）拉取两个内部端点并缓存在进程内：

- ``GET /internal/revocations?since=<epochMillis>`` → 已撤销的 jti（增量）；
- ``GET /internal/users/disabled``               → 非 ACTIVE 账号用户名（全量，238 条量级）。

纪律
----
- **直连 auth 服务**（127.0.0.1:8081），不经网关：网关对 ``/internal/**`` 直接 404，
  且这两条接口必须带 ``X-Internal-Token``（fail-closed）。
- **未配置即关闭**：``PLATFORM_AUTH_BASE_URL`` 或 ``INTERNAL_API_TOKEN`` 为空时不启动同步，
  行为与历史完全一致（单机/离线形态）。
- **同步失败不阻断业务**：Java 侧短暂不可用时保留上一份缓存继续判定；从未同步成功时
  按"放行"处理并只告警一次——AI 中台不能因为认证服务抖动而全员 401。
- 只在**校验 Java 签发令牌**（载荷带 jti + roles）时使用；本系统自签令牌走原有撤销机制。
"""
from __future__ import annotations

import threading
import time
from datetime import datetime
from typing import Optional

import httpx

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)

_REVOKE_PATH = "/internal/revocations"
_DISABLED_PATH = "/internal/users/disabled"

_lock = threading.Lock()
_revoked: dict[str, float] = {}      # jti → 到期时间戳（秒）；到期即自然失效，无需撤销
_disabled: set[str] = set()          # 非 ACTIVE 账号用户名（小写）
_synced_at: float = 0.0              # 最近一次**成功**同步完成的时间戳
_since_ms: int = 0                   # 增量游标（服务端时间，回拨 2 秒防漏）
_ever_synced: bool = False
_warned_unreachable: bool = False
_started: bool = False
_stop = threading.Event()
_thread: Optional[threading.Thread] = None


def is_configured() -> bool:
    """是否具备同步条件（地址与内部令牌都配了）。"""
    return bool((settings.platform_auth_base_url or "").strip()
                and (settings.internal_api_token or "").strip())


def is_enabled() -> bool:
    """同步线程是否已启动（供健康检查/测试断言）。"""
    return _started


def start() -> bool:
    """启动后台同步线程（幂等）。未配置返回 False，不产生任何副作用。"""
    global _started, _thread
    if not is_configured():
        return False
    with _lock:
        if _started:
            return True
        _started = True
        _stop.clear()
    _thread = threading.Thread(target=_loop, name="platform-identity-sync", daemon=True)
    _thread.start()
    logger.info("platform_identity.sync_started",
                base_url=settings.platform_auth_base_url,
                interval_s=float(settings.identity_sync_seconds or 15))
    return True


def stop() -> None:
    """停止同步线程（下线/测试清理用）。"""
    _stop.set()


def sync_once(timeout_s: float = 5.0) -> bool:
    """执行一轮同步；返回是否全部成功。异常向上抛由调用方决定是否告警。"""
    global _since_ms, _synced_at, _ever_synced, _warned_unreachable
    base = (settings.platform_auth_base_url or "").strip().rstrip("/")
    token = (settings.internal_api_token or "").strip()
    if not base or not token:
        return False
    headers = {"X-Internal-Token": token}
    with _new_client(timeout_s) as client:
        resp = client.get(f"{base}{_REVOKE_PATH}", params={"since": int(_since_ms)}, headers=headers)
        resp.raise_for_status()
        data = _unwrap(resp.json())
        revocations = data.get("revocations") or []
        server_ms = int(data.get("serverTime") or 0)

        resp2 = client.get(f"{base}{_DISABLED_PATH}", headers=headers)
        resp2.raise_for_status()
        disabled = _unwrap(resp2.json())

    now = time.time()
    with _lock:
        for item in revocations:
            if not isinstance(item, dict):
                continue
            jti = str(item.get("jti") or "").strip()
            if not jti:
                continue
            _revoked[jti] = _parse_expiry(item.get("expiresAt")) or (now + 86400)
        # 全量覆盖：账号重新启用后会从清单消失，必须能"恢复"，不能只增不减
        if isinstance(disabled, list):
            _disabled.clear()
            _disabled.update(str(n).strip().lower() for n in disabled if str(n).strip())
        # 回拨 2 秒：与网关同一口径，避免时钟偏差导致同一毫秒的撤销项漏拉
        if server_ms > 0:
            _since_ms = max(_since_ms, server_ms - 2000)
        _synced_at = now
        _ever_synced = True
        _warned_unreachable = False
        _prune_locked(now)
    return True


def check(username: str, jti: Optional[str]) -> bool:
    """Java 令牌是否仍可用：jti 未被撤销且账号未被停用。

    - 未配置同步 / 从未同步成功 → True（不因认证服务不可用而阻断 AI 中台）；
    - 已同步过 → 按缓存判定，命中即拒绝。
    """
    name = (username or "").strip().lower()
    token_id = (jti or "").strip()
    with _lock:
        if not _ever_synced:   # 从未同步成功（未配置/Java 不可达）不阻断业务
            return True
        if token_id and token_id in _revoked:
            return False
        if name and name in _disabled:
            return False
    return True


def snapshot() -> dict:
    """同步状态快照（日志/排障用，不对外暴露）。"""
    with _lock:
        return {"enabled": _started, "ever_synced": _ever_synced,
                "revoked": len(_revoked), "disabled": len(_disabled),
                "synced_at": _synced_at, "since_ms": _since_ms}


def _loop() -> None:
    global _warned_unreachable
    interval = max(1.0, float(settings.identity_sync_seconds or 15))
    while not _stop.is_set():
        try:
            sync_once()
        except Exception as exc:  # noqa: BLE001 —— 同步失败只告警，保留上一份缓存继续判定
            if not _warned_unreachable:
                _warned_unreachable = True
                logger.warning("platform_identity.sync_failed",
                               error=str(exc)[:160],
                               detail="保留上一份缓存；从此只在恢复/再次失败时记录，避免刷日志")
        _stop.wait(interval)


def _new_client(timeout_s: float) -> httpx.Client:
    """HTTP 客户端工厂（测试通过替换本函数注入 MockTransport）。"""
    return httpx.Client(timeout=timeout_s)


def _unwrap(body) -> dict | list:
    """兼容 Java 统一响应包装 {code,message,data}；已解包的直接返回。"""
    if isinstance(body, dict) and "data" in body and isinstance(body.get("data"), (dict, list)):
        return body["data"]
    return body


def _parse_expiry(value) -> float:
    """ISO-8601（如 2026-11-09T11:40:05Z）→ 时间戳；解析不了返回 0（调用方按兜底处理）。"""
    text = str(value or "").strip()
    if not text:
        return 0.0
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return 0.0


def _prune_locked(now: float) -> None:
    """清掉已过期的撤销项（令牌本身已过期，不必再记）。调用方需持锁。"""
    for jti, exp in list(_revoked.items()):
        if exp and exp <= now:
            _revoked.pop(jti, None)


def _reset_for_tests() -> None:
    """测试专用：清空缓存与游标（不停止已启动线程，测试用 fake client 直接调 sync_once）。"""
    global _synced_at, _since_ms, _ever_synced, _warned_unreachable
    with _lock:
        _revoked.clear()
        _disabled.clear()
        _synced_at = 0.0
        _since_ms = 0
        _ever_synced = False
        _warned_unreachable = False
