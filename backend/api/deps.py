"""FastAPI 鉴权依赖：校验 Bearer 令牌，返回当前用户。"""
from __future__ import annotations

import time
from typing import Optional

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from backend.config import settings
from backend.core.auth import decode_token, get_user
from backend.core.security_rate import allow
from backend.core.stores import get_store

_bearer = HTTPBearer(auto_error=False)

# 令牌撤销：按用户记录"撤销时间戳"，签发早于该时间的令牌一律 401（删除用户/重置密码时登记）。
# 相比用户名黑名单，新登录签发的令牌立即可用。轮 B2 双后端：本地表保留（默认路径零改变），
# REDIS_URL 配置后同步写共享存储（TTL=令牌最大寿命，到期自动清理，无需遍历）；
# 校验时本地未命中再查共享存储——多实例下任一实例登记的撤销全局生效。
# Redis 不可用由 RedisStore 自动回落内存镜像（fail-closed 兜底仍由下方用户表回查保证）。
_revoked_before: dict[str, float] = {}
_TOKEN_MAX_AGE = 8 * 86400  # refresh 令牌最长 7 天，超过此前的撤销记录可安全清除
_REVOKE_PREFIX = "revoke:"  # 共享存储键前缀


def revoke_user(username: str) -> None:
    """删除用户/重置密码后调用：使该用户此前签发的所有访问令牌立即失效。"""
    now = time.time()
    _revoked_before[username] = now
    get_store().set(_REVOKE_PREFIX + username, now, ttl=_TOKEN_MAX_AGE)
    for k in [k for k, v in _revoked_before.items() if v < now - _TOKEN_MAX_AGE]:
        _revoked_before.pop(k, None)  # 撤销记录本身超过令牌最大寿命后可清除（旧令牌已自然过期）


def ensure_not_revoked(username: Optional[str], iat: float) -> None:
    """令牌签发时间早于该用户撤销时间戳则拒绝（访问/刷新令牌统一适用）。

    fail-closed：iat 缺失或非法时按 0 处理，一旦该用户存在撤销记录即拒绝。
    """
    if not username:
        return
    if username in _revoked_before and iat < _revoked_before[username]:
        raise HTTPException(status_code=401, detail="账号已被停用或凭证已重置，请重新登录")
    # 跨实例共享（REDIS_URL 配置后生效）：本实例无记录时查共享存储
    shared = get_store().get(_REVOKE_PREFIX + username)
    if shared is not None and iat < float(shared):
        raise HTTPException(status_code=401, detail="账号已被停用或凭证已重置，请重新登录")


def require_user(
    creds: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> dict:
    if not settings.auth_enabled:
        return {"username": "anonymous", "role": "admin", "dept": ""}
    if not creds or not creds.credentials:
        raise HTTPException(status_code=401, detail="未认证：请先登录 /api/v1/auth/login 获取令牌")
    payload = decode_token(creds.credentials)
    if not payload:
        raise HTTPException(status_code=401, detail="令牌无效或已过期，请重新登录")
    sub = payload.get("sub")
    try:
        iat = float(payload.get("iat") or 0)
    except (TypeError, ValueError):
        iat = 0.0
    ensure_not_revoked(sub, iat)
    u = get_user(sub)
    if not u:  # 撤销表为进程内存态，重启后丢失：回查用户表兜底（fail-closed）
        raise HTTPException(status_code=401, detail="账号不存在或已停用")
    return {"username": sub, "role": payload.get("role"), "dept": u.get("dept", "")}


def require_role(*roles: str):
    def _dep(user: dict = Depends(require_user)) -> dict:
        if roles and user.get("role") not in roles:
            raise HTTPException(status_code=403, detail=f"权限不足：需角色 {list(roles)}")
        return user
    return _dep


def _identity(request: Request) -> str:
    h = request.headers.get("authorization", "")
    if h.startswith("Bearer "):
        p = decode_token(h[7:])
        if p:
            return "u:" + str(p.get("sub", ""))
    return "ip:" + (request.client.host if request.client else "unknown")


def rate_limit(request: Request):
    """按登录用户或 IP 的滑动窗口限流；超限 429。"""
    if not allow(_identity(request), limit=settings.rate_limit_per_min, window=60):
        raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
