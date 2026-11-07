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


def test_ask_view_stream_prevalidation_uses_real_status():
    """评审 S2：前置校验失败（403/422/503/429）不回落非流式，流内错误按 code 分支。"""
    body = _read(ASK)
    assert "err.httpStatus = res.status" in body
    assert "err.noFallback = true" in body
    assert "err.sseCode = d.code" in body
    assert "e.noFallback" in body


def test_ask_view_error_banner_sits_above_thread():
    """评审 04-S7：错误条必须在线程上方，长对话下不能被顶出视口。"""
    body = _read(ASK)
    assert body.index('data-testid="ask-error"') < body.index('class="thread"')


def test_ask_view_skill_selection_limit_is_front_loaded():
    """评审 04-S2：8 张技能卡上限在界面即时拦截，并显示已选计数。"""
    body = _read(ASK)
    assert "const SKILL_LIMIT = 8" in body
    assert "skillLimitReached" in body
    assert 'data-testid="skill-count"' in body


def test_ask_view_sse_terminal_state_and_residual_buffer():
    """评审 04-S12：终态后停止读循环、复位 ev、处理残留缓冲并主动关闭连接。"""
    body = _read(ASK)
    assert "let terminal = false" in body
    assert "terminal = true" in body
    assert "ev = null" in body
    assert "await reader.cancel()" in body
    assert "buf.trim()" in body


def test_ask_view_keeps_bubble_on_abort_and_fallback_failure():
    """评审 04-S8 / S14：停止或回落失败都保留气泡就地说明，不留孤立提问。"""
    body = _read(ASK)
    assert "placeholder.aborted = true" in body
    assert "已停止生成（本次已中止" in body
    assert "placeholder.failNote = '请求失败：' + detailText(e2)" in body


def test_literature_view_401_is_unified():
    """评审 04-S15：文献流式 401 与 AskView 同纪律（await 登出 + 提示 + 不回落）。"""
    lit = os.path.join(REPO, "frontend-vue", "src", "views", "LiteratureView.vue")
    body = _read(lit)
    assert "await auth.logout()" in body
    assert "return 'auth'" in body
    assert "if (streamed === 'auth') return" in body
