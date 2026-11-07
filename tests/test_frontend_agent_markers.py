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
AGENT = os.path.join(REPO, "frontend-vue", "src", "views", "AgentView.vue")
LABELS = os.path.join(REPO, "frontend-vue", "src", "constants", "labels.js")
VITE = os.path.join(REPO, "frontend-vue", "vite.config.js")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_agent_view_marks_window_semantics_and_quality():
    """评审 02-S4/S14：两类数据窗口必须有显式标签；阈值取后端，质量信号可见。"""
    body = _read(AGENT)
    assert "持久化资产快照" in body
    assert "进程内累计 · 重启归零" in body
    assert "usage.thresholds" in body
    assert "usage.quality.flagged" in body
    assert "errorRateNote" in body
    # 评审 03-S9：开关切换带 expected 做 CAS
    assert "expected: !!flag.enabled" in body


def test_vite_dev_proxy_targets_backend():
    """评审 04-S18：npm run dev 时同源相对请求必须代理到后端，否则全部 404。"""
    body = _read(VITE)
    assert "server:" in body
    assert "'/api'" in body and "http://127.0.0.1:8001" in body


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


def test_enum_labels_have_single_source():
    """评审 04-S3：策略/状态中英文映射只有 constants/labels.js 一份，两页共用。"""
    labels = _read(LABELS)
    assert "STRATEGY_TEXT" in labels and "STATUS_TEXT" in labels
    ask = _read(ASK)
    agent = _read(AGENT)
    assert "from '../constants/labels'" in ask
    assert "from '../constants/labels'" in agent
    # 本地不得再自建一份映射（防止再次漂移）
    assert "const STATUS_TEXT = {" not in agent
    assert "single: '单智能体直达'" not in ask


def test_agent_view_clears_stale_results_and_shows_inline_errors():
    """评审 04-S3/S4：失败就地展示，发起请求前清空旧结果。"""
    body = _read(AGENT)
    assert 'data-testid="agent-route-error"' in body
    assert 'data-testid="agent-consult-error"' in body
    assert "route.value = null" in body
    assert "consultResult.value = null" in body
    assert "md(consultResult.answer" in body


def test_agent_view_refresh_has_loading_and_sequence_guard():
    """评审 04-S5：刷新按钮 loading + 请求序号守卫，旧响应不覆盖新数据。"""
    body = _read(AGENT)
    assert ':loading="loadingAll"' in body
    assert "const seq = ++loadSeq" in body
    assert "seq !== loadSeq" in body
    assert "++loadSeq" in body


def test_agent_view_tables_avoid_horizontal_overflow():
    """评审 04-S9：长字段截断 + 操作列收窄，避免固定列宽溢出 1028px。"""
    body = _read(AGENT)
    assert "show-overflow-tooltip" in body
    assert 'class-name="ops-col"' in body
    assert ".ops-col .el-button" in body


def test_ask_view_new_chat_confirm_and_deactivate_abort():
    """评审 04-S11：生成中清空需确认；KeepAlive 失活时中止请求。"""
    body = _read(ASK)
    assert "ElMessageBox.confirm" in body
    assert "onDeactivated(" in body
    assert "if (loading.value) stop()" in body
    assert "const inputEl = ref(null)" in body


def test_ask_view_history_keeps_complete_pairs():
    """评审 04（历史裁剪）：按问答对丢弃，历史首条永远是 user。"""
    body = _read(ASK)
    assert "pairs.slice(-3)" in body
    assert "kept.flat()" in body


def test_admin_has_ask_entry():
    """评审 04-S6：后端对 admin 开放 /consult，前端侧边栏必须有入口（否则只能用 URL 硬进）。"""
    nav = _read(os.path.join(REPO, "frontend-vue", "src", "constants", "nav.js"))
    assert "admin: ['overview', 'ask'" in nav
