"""FastAPI 鉴权依赖：校验 Bearer 令牌，返回当前用户。"""
from __future__ import annotations

import time
from typing import Optional

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from backend.config import settings
from backend.core.auth import decode_token, get_user
from backend.core.security_rate import allow

_bearer = HTTPBearer(auto_error=False)

# 令牌撤销：按用户记录"撤销时间戳"，签发早于该时间的令牌一律 401（删除用户/重置密码时登记）。
# 相比用户名黑名单，新登录签发的令牌立即可用；单实例内存版，多实例应换 Redis（与限流同策略）。
_revoked_before: dict[str, float] = {}
_TOKEN_MAX_AGE = 8 * 86400  # refresh 令牌最长 7 天，超过此前的撤销记录可安全清除


def revoke_user(username: str) -> None:
    """删除用户/重置密码后调用：使该用户此前签发的所有访问令牌立即失效。"""
    now = time.time()
    _revoked_before[username] = now
    for k in [k for k, v in _revoked_before.items() if v < now - _TOKEN_MAX_AGE]:
        _revoked_before.pop(k, None)  # 撤销记录本身超过令牌最大寿命后可清除（旧令牌已自然过期）


def ensure_not_revoked(username: Optional[str], iat: float) -> None:
    """令牌签发时间早于该用户撤销时间戳则拒绝（访问/刷新令牌统一适用）。

    fail-closed：iat 缺失或非法时按 0 处理，一旦该用户存在撤销记录即拒绝。
    """
    if username and username in _revoked_before and iat < _revoked_before[username]:
        raise HTTPException(status_code=401, detail="账号已被停用或凭证已重置，请重新登录")


def require_user(
    creds: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> dict:
    if not settings.auth_enabled:
        return {"username": "anonymous", "role": "admin"}
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
    if not get_user(sub):  # 撤销表为进程内存态，重启后丢失：回查用户表兜底（fail-closed）
        raise HTTPException(status_code=401, detail="账号不存在或已停用")
    return {"username": sub, "role": payload.get("role")}


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
