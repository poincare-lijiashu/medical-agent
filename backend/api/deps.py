"""FastAPI 鉴权依赖：校验 Bearer 令牌，返回当前用户。"""
from __future__ import annotations

import secrets
import time
from typing import Optional

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from backend.config import settings
from backend.core.auth import decode_token, get_user
from backend.core.security_rate import allow
from backend.core.stores import get_store

_bearer = HTTPBearer(auto_error=False)

# W3.1 httpOnly cookie 认证（转型计划书 WF-3）：令牌不再进 JS 可达存储。
# 来源优先级：Bearer header（脚本/评测/服务间调用）→ httpOnly cookie（浏览器同源）。
_COOKIE_AT = "medassist_at"   # 访问令牌 cookie（max_age=access 过期分钟）
_COOKIE_RT = "medassist_rt"   # 刷新令牌 cookie（max_age=refresh 过期分钟）


def _token_from_request(creds: Optional[HTTPAuthorizationCredentials],
                        request: Request, allow_cookie: bool = True) -> str:
    """Bearer 优先；``allow_cookie=False`` 时**不**回落到 cookie。

    评审 S5：抓取令牌通道只允许显式 `Authorization: Bearer`，避免 cookie 成为
    第二条令牌输入路径（浏览器会自动携带同源 cookie）。
    """
    if creds and creds.credentials:
        return creds.credentials
    if not allow_cookie:
        return ""
    return request.cookies.get(_COOKIE_AT, "")


def _token_from_refresh_request(body_token: str, request: Request) -> str:
    """刷新令牌来源：请求体显式携带优先（兼容既有脚本），否则读 httpOnly cookie。"""
    if body_token:
        return body_token
    return request.cookies.get(_COOKIE_RT, "")

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


def _user_from_token(token: str) -> dict:
    payload = decode_token(token)
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


def require_user(
    request: Request,
    creds: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> dict:
    if not settings.auth_enabled:
        return {"username": "anonymous", "role": "admin", "dept": ""}
    token = _token_from_request(creds, request)
    if not token:
        raise HTTPException(status_code=401, detail="未认证：请先登录 /api/v1/auth/login 获取令牌")
    return _user_from_token(token)


def _metrics_token_matches(token: str, expected: str) -> bool:
    """常量时间比较；按字节比较以容忍任意 header 编码，非 ASCII 令牌直接不匹配。"""
    try:
        return secrets.compare_digest(str(token).encode("utf-8"),
                                      str(expected).encode("utf-8"))
    except (UnicodeEncodeError, TypeError):
        return False


def require_metrics_access(
    request: Request,
    creds: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> dict:
    """Prometheus 抓取鉴权：静态 METRICS_TOKEN 或 admin 登录态，二者其一。

    静态令牌仅用于抓取，不映射真实用户；未配置时该通道关闭（fail-closed）。
    """
    # 评审 S5：抓取令牌只认 Bearer 头，不接受 cookie 回落
    token = _token_from_request(creds, request, allow_cookie=False)
    expected = (settings.metrics_token or "").strip()
    if expected and token and _metrics_token_matches(token.strip(), expected):
        return {"username": "prometheus", "role": "metrics", "dept": ""}
    if not settings.auth_enabled:
        # 评审 S5：无鉴权部署下 /metrics 也不公开（fail-closed）；
        # 要抓取必须显式配置 METRICS_TOKEN。
        raise HTTPException(status_code=503,
                            detail="未配置 METRICS_TOKEN 且鉴权关闭：/metrics 不可用")
    if not token:
        raise HTTPException(status_code=401, detail="未认证：需 admin 登录或有效抓取令牌")
    user = _user_from_token(token)
    if user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="权限不足：需 admin 或有效抓取令牌")
    return user


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
    ck = request.cookies.get(_COOKIE_AT, "")  # W3.1：cookie 会话的限流身份同样按用户聚合
    if ck:
        p = decode_token(ck)
        if p:
            return "u:" + str(p.get("sub", ""))
    return "ip:" + (request.client.host if request.client else "unknown")


def rate_limit(request: Request):
    """按登录用户或 IP 的滑动窗口限流；超限 429。"""
    if not allow(_identity(request), limit=settings.rate_limit_per_min, window=60):
        raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")


def model_quota(request: Request):
    """模型调用类端点专用配额（评审 03-S8）。

    与通用请求限流分开计：通用限流防的是"刷接口"，这里防的是"单用户把模型算力吃满"。
    默认 ``AGENT_MODEL_QUOTA_PER_MIN=30``；设为 0 可关闭（不建议生产关闭）。
    仅作用于真正调用模型的端点（consult/stream、second-opinion、协作、工具循环）。
    """
    limit = int(settings.agent_model_quota_per_min or 0)
    if limit <= 0:
        return
    if not allow("model:" + _identity(request), limit=limit, window=60):
        raise HTTPException(status_code=429,
                            detail="模型调用配额已用尽（每分钟），请稍后再试")
