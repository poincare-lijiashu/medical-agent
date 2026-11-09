"""认证路由：登录换令牌、当前用户信息、修改/重置密码。

W3.1 httpOnly cookie（转型计划书 WF-3）：登录/刷新把令牌写入 httpOnly cookie
（浏览器同源自动携带，JS 不可读），响应体令牌字段置空；Bearer header 兼容保留。
"""
from __future__ import annotations

import asyncio
import math

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from backend.api.deps import (
    _COOKIE_AT,
    _COOKIE_RT,
    _client_ip,
    _token_from_refresh_request,
    ensure_not_revoked,
    rate_limit,
    require_role,
    require_user,
    revoke_user,
)
from backend.config import settings
from backend.core import login_guard, pg_store
from backend.core.auth import (
    authenticate,
    create_access_token,
    create_refresh_token,
    decode_refresh_payload,
    get_user,
    set_password,
    verify_password,
)
from backend.core.medical_audit import get_audit_logger

router = APIRouter(dependencies=[Depends(rate_limit)])


def _set_auth_cookies(response: Response, access_token: str, refresh_token: str) -> None:
    """令牌写 httpOnly cookie：secure 仅生产开启（本机 HTTP 开发环境不设）；SameSite=Lax
    为 CSRF 主防线（跨站 POST 不携带）。"""
    secure = settings.app_env == "production"
    response.set_cookie(_COOKIE_AT, access_token, max_age=settings.auth_token_expire_minutes * 60,
                        httponly=True, samesite="lax", secure=secure, path="/")
    response.set_cookie(_COOKIE_RT, refresh_token, max_age=settings.auth_refresh_expire_minutes * 60,
                        httponly=True, samesite="lax", secure=secure, path="/")


# 审查修正：鉴权请求体统一 max_length（与全库请求模型纪律一致）——
# 超长口令会线性烧 PBKDF2 CPU（0.2s/次放大为 DoS 面），超长用户名徒增查表负担。
class LoginReq(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=128)


class LoginResp(BaseModel):
    # W3.1：令牌只经 httpOnly cookie 下发，响应体字段保留但恒为空（兼容旧字段消费方）
    access_token: str = ""
    token_type: str = "bearer"
    username: str
    role: str
    refresh_token: str = ""


class RefreshReq(BaseModel):
    refresh_token: str = ""  # W3.1：为空时读 httpOnly cookie（body 显式携带仍兼容）


class ChangePwdReq(BaseModel):
    old_password: str = Field(max_length=128)
    new_password: str = Field(max_length=128)


class ResetPwdReq(BaseModel):
    username: str = Field(max_length=64)
    new_password: str = Field(max_length=128)


def _login_resp(username: str, role: str, at: str, rt: str) -> LoginResp:
    """按 AUTH_TOKEN_IN_BODY 决定响应体是否回带令牌（默认空=只走 httpOnly cookie；
    测试/脚本调试环境显式开启以兼容 Bearer 消费方；生产绝不开启）。"""
    return LoginResp(username=username, role=role,
                     access_token=at if settings.auth_token_in_body else "",
                     refresh_token=rt if settings.auth_token_in_body else "")


def _lock_detail(seconds: int) -> str:
    """锁定提示：只给"稍后再试"，不含账号是否存在的信息（防枚举）。"""
    minutes = max(1, math.ceil(int(seconds or 0) / 60))
    return f"登录失败次数过多，请约 {minutes} 分钟后再试"


@router.post("/auth/login", response_model=LoginResp)
async def login(body: LoginReq, request: Request, response: Response):
    # 评测 04 P1-6：先查锁定（不查用户是否存在——锁定键只由"该账号名 + IP 的失败"产生，
    # 对不存在账号同样生效，因此响应不泄露账号存在性）。
    ip = _client_ip(request)
    wait = await asyncio.to_thread(login_guard.locked_seconds, body.username, ip)
    if wait > 0:
        raise HTTPException(status_code=429, detail=_lock_detail(wait),
                            headers={"Retry-After": str(wait)})
    # PBKDF2 600k 迭代 ~0.2s CPU + users.json 读盘，必须移出事件循环（QUALITY-001）
    user = await asyncio.to_thread(authenticate, body.username, body.password)
    if not user:
        lock_s = await asyncio.to_thread(login_guard.record_failure, body.username, ip)
        if lock_s > 0:
            raise HTTPException(status_code=429, detail=_lock_detail(lock_s),
                                headers={"Retry-After": str(lock_s)})
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    # 成功登录清零：正常用户偶尔输错不会累积到锁定
    await asyncio.to_thread(login_guard.clear, body.username, ip)
    at = create_access_token(user["username"], user["role"])
    rt = create_refresh_token(user["username"])
    _set_auth_cookies(response, at, rt)
    return _login_resp(user["username"], user["role"], at, rt)


@router.post("/auth/refresh", response_model=LoginResp)
def refresh(body: RefreshReq, request: Request, response: Response):
    rt = _token_from_refresh_request(body.refresh_token, request)
    if not rt:
        raise HTTPException(status_code=401, detail="刷新令牌无效或已过期")
    payload = decode_refresh_payload(rt)
    if not payload:
        raise HTTPException(status_code=401, detail="刷新令牌无效或已过期")
    username = str(payload.get("sub") or "")
    u = get_user(username)
    if not u:
        raise HTTPException(status_code=401, detail="用户不存在或已禁用")
    try:
        iat = float(payload.get("iat") or 0)
    except (TypeError, ValueError):
        iat = 0.0
    ensure_not_revoked(username, iat)  # 重置密码/删除用户后，旧刷新令牌一律拒绝续签
    at = create_access_token(username, u["role"])
    rt2 = create_refresh_token(username)
    _set_auth_cookies(response, at, rt2)
    return _login_resp(username, u["role"], at, rt2)


@router.post("/auth/logout")
def logout(response: Response):
    """登出：清除 httpOnly cookie。不做鉴权（无副作用动作；改密/删号撤销后
    旧令牌本应失效，若此处要求认证反而清不掉过期 cookie）。服务端无状态 JWT
    不做服务端撤销——令牌随 cookie 清除与自然过期失效。"""
    response.delete_cookie(_COOKIE_AT, path="/")
    response.delete_cookie(_COOKIE_RT, path="/")
    return {"ok": True}


@router.get("/auth/me")
def me(user: dict = Depends(require_user)):
    return user


@router.post("/auth/change-password")
async def change_password(body: ChangePwdReq, user: dict = Depends(require_user)):
    """登录用户修改本人密码：校验原密码，新密码 ≥8 位。"""
    username = user.get("username", "")
    u = await asyncio.to_thread(get_user, username)
    if not u or not await asyncio.to_thread(verify_password, body.old_password, u["password_hash"]):
        raise HTTPException(400, "原密码不正确")
    if len(body.new_password or "") < 8:
        raise HTTPException(400, "新密码至少 8 位")
    await asyncio.to_thread(set_password, username, body.new_password)
    revoke_user(username)  # 改密后撤销该用户全部旧令牌（含访问令牌），须重新登录
    # 本人改密：actor 即操作对象、action 已含语义（E：payload 附带操作者角色）
    await asyncio.to_thread(get_audit_logger().write, event_type="auth", action="password_changed",
                            actor=username, payload={"role": user.get("role", "")})
    rec = await asyncio.to_thread(get_user, username)
    if rec:
        await pg_store.mirror_user(username, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}


@router.post("/auth/reset-password")
async def reset_password(body: ResetPwdReq, user: dict = Depends(require_role("admin"))):
    """管理员重置任意用户密码（忘记密码的线下通道）。"""
    if len(body.new_password or "") < 8:
        raise HTTPException(400, "新密码至少 8 位")
    if not await asyncio.to_thread(get_user, body.username):
        raise HTTPException(404, "用户不存在")
    await asyncio.to_thread(set_password, body.username, body.new_password)
    revoke_user(body.username)  # 该用户已签发的令牌立即失效，须重新登录
    await asyncio.to_thread(get_audit_logger().write, event_type="auth", action="password_reset",
                            actor=user.get("username", "admin"),
                            payload={"user": body.username, "role": user.get("role", "")})
    rec = await asyncio.to_thread(get_user, body.username)
    if rec:
        await pg_store.mirror_user(body.username, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}
