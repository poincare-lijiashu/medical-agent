"""认证路由：登录换令牌、当前用户信息、修改/重置密码。"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from backend.api.deps import ensure_not_revoked, rate_limit, require_role, require_user, revoke_user
from backend.core import pg_store
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


# 审查修正：鉴权请求体统一 max_length（与全库请求模型纪律一致）——
# 超长口令会线性烧 PBKDF2 CPU（0.2s/次放大为 DoS 面），超长用户名徒增查表负担。
class LoginReq(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=128)


class LoginResp(BaseModel):
    access_token: str
    token_type: str = "bearer"
    username: str
    role: str
    refresh_token: str = ""


class RefreshReq(BaseModel):
    refresh_token: str


class ChangePwdReq(BaseModel):
    old_password: str = Field(max_length=128)
    new_password: str = Field(max_length=128)


class ResetPwdReq(BaseModel):
    username: str = Field(max_length=64)
    new_password: str = Field(max_length=128)


@router.post("/auth/login", response_model=LoginResp)
async def login(body: LoginReq):
    # PBKDF2 600k 迭代 ~0.2s CPU + users.json 读盘，必须移出事件循环（QUALITY-001）
    user = await asyncio.to_thread(authenticate, body.username, body.password)
    if not user:
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = create_access_token(user["username"], user["role"])
    return LoginResp(access_token=token, username=user["username"], role=user["role"],
                     refresh_token=create_refresh_token(user["username"]))


@router.post("/auth/refresh", response_model=LoginResp)
def refresh(body: RefreshReq):
    payload = decode_refresh_payload(body.refresh_token)
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
    return LoginResp(access_token=create_access_token(username, u["role"]), username=username, role=u["role"],
                     refresh_token=create_refresh_token(username))


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
