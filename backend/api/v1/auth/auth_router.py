"""认证路由：登录换令牌、当前用户信息、修改/重置密码。"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

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


class LoginReq(BaseModel):
    username: str
    password: str


class LoginResp(BaseModel):
    access_token: str
    token_type: str = "bearer"
    username: str
    role: str
    refresh_token: str = ""


class RefreshReq(BaseModel):
    refresh_token: str


class ChangePwdReq(BaseModel):
    old_password: str
    new_password: str


class ResetPwdReq(BaseModel):
    username: str
    new_password: str


@router.post("/auth/login", response_model=LoginResp)
def login(body: LoginReq):
    user = authenticate(body.username, body.password)
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
    u = get_user(username)
    if not u or not verify_password(body.old_password, u["password_hash"]):
        raise HTTPException(400, "原密码不正确")
    if len(body.new_password or "") < 8:
        raise HTTPException(400, "新密码至少 8 位")
    set_password(username, body.new_password)
    revoke_user(username)  # 改密后撤销该用户全部旧令牌（含访问令牌），须重新登录
    # 本人改密：actor 即操作对象、action 已含语义，无额外上下文可记（F5 评估后保留空 payload）
    get_audit_logger().write(event_type="auth", action="password_changed",
                             actor=username, payload={})
    rec = get_user(username)
    if rec:
        await pg_store.mirror_user(username, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}


@router.post("/auth/reset-password")
async def reset_password(body: ResetPwdReq, user: dict = Depends(require_role("admin"))):
    """管理员重置任意用户密码（忘记密码的线下通道）。"""
    if len(body.new_password or "") < 8:
        raise HTTPException(400, "新密码至少 8 位")
    if not get_user(body.username):
        raise HTTPException(404, "用户不存在")
    set_password(body.username, body.new_password)
    revoke_user(body.username)  # 该用户已签发的令牌立即失效，须重新登录
    get_audit_logger().write(event_type="auth", action="password_reset",
                             actor=user.get("username", "admin"), payload={"user": body.username})
    rec = get_user(body.username)
    if rec:
        await pg_store.mirror_user(body.username, rec.get("role", ""), rec.get("password_hash", ""))
    return {"ok": True}
