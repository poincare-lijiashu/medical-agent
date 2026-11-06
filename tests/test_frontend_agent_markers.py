"""前端 Agent 域健壮性标记（评审 M-54 / M-56 / M-57 / M-58 / M-59）。

这些是静态标记测试：前端没有单元测试框架，用"关键实现必须存在"防止回退。
真正的行为验证靠 `scripts/e2e/agent_ask_stream_smoke.py` 与人工浏览器。
"""
from __future__ import annotations

import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROUTER = os.path.join(REPO, "frontend-vue", "src", "router", "index.js")
API = os.path.join(REPO, "frontend-vue", "src", "api", "index.js")
ASK = os.path.join(REPO, "frontend-vue", "src", "views", "AskView.vue")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_router_has_role_guard():
    """M-56：非授权角色直接访问受限路由必须回落概览。"""
    body = _read(ROUTER)
    assert "rolesForRoute" in body
    assert "ROLE_NAV" in body
    assert "!allowed.includes(auth.role)" in body


def test_axios_awaits_logout_before_redirect():
    """M-54：401 必须先完成登出再跳转。"""
    assert "await auth.logout()" in _read(API)


def test_ask_view_401_does_not_fall_back():
    """M-57：401 后不得再发非流式请求（避免二次登出/二次跳转）。"""
    body = _read(ASK)
    assert "authExpired" in body
    assert "err.authExpired = true" in body


def test_ask_view_excludes_router_agent():
    """M-58：主路由智能体不可作为可选能力域。"""
    assert "a.id !== 'router'" in _read(ASK)


def test_ask_view_pick_tags_are_keyboard_accessible():
    """M-59：el-tag 当按钮用必须可聚焦并支持 Enter/Space。"""
    body = _read(ASK)
    assert 'role="button"' in body
    assert 'aria-pressed' in body
    assert "@keydown.enter.prevent" in body
    assert "@keydown.space.prevent" in body
    assert ":focus-visible" in body
