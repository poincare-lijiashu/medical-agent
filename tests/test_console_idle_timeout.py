"""会话空闲自动退出（第 221 批）。

共用医生站/护士站最危险的不是“没登录”，而是“人走了，屏幕还亮着”。
本批加空闲超时：默认 30 分钟（可配 5–240），提前 60 秒倒计时，超时自动登出并广播
到其它标签页；登录页给出明确原因。

锁死：
1. 用时间戳判定，不用累加计数（后台节流不会“多送”在线时间）；
2. 只对已登录会话生效；
3. 告警窗口 60s，可“继续使用”重置；
4. 超时：清会话 + 广播登出 + 跳登录页（带 reason=idle），且只触发一次；
5. 监听器与定时器必须成对清理。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
IDLE = SRC / "idle.ts"
SHELL = SRC / "layouts/AppShell.vue"
LOGIN = SRC / "views/LoginView.vue"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_idle_policy_defaults_and_bounds():
    src = _read(IDLE)
    assert "IDLE_DEFAULT_MIN = 30" in src
    assert "IDLE_MIN_MIN = 5" in src and "IDLE_MAX_MIN = 240" in src
    assert "Math.min(IDLE_MAX_MIN, Math.max(IDLE_MIN_MIN, Math.round(value)))" in src
    assert "IDLE_WARN_MS = 60_000" in src
    assert "return remainingMs <= IDLE_WARN_MS" in src
    assert "Math.ceil(remainingMs / 1000)" in src, "倒计时应向上取整"


def test_shell_uses_timestamps_and_guards_login():
    src = _read(SHELL)
    assert "idleLimitMs - (Date.now() - idleLast)" in src, "必须用时间戳判定空闲"
    assert "if (!session.isLoggedIn.value || idleFired) return" in src
    assert "window.setInterval(tickIdle, 1_000)" in src
    assert "for (const name of IDLE_EVENTS) window.addEventListener(name, touchIdle, { passive: true })" in src
    assert "window.removeEventListener(name, touchIdle)" in src, "卸载必须移除监听"
    assert "window.clearInterval(idleTimer)" in src


def test_idle_logout_is_one_shot_and_broadcast():
    src = _read(SHELL)
    assert "idleFired = true" in src
    assert "await session.logout()" in src
    assert "broadcastLogout()" in src, "空闲登出必须广播到其它标签页"
    assert "query: { reason: 'idle' }" in src
    assert "userMenuOpen.value = false" in src and "todoOpen.value = false" in src, "登出前收起浮层"


def test_idle_dialog_is_accessible_and_localized():
    src = _read(SHELL)
    assert 'role="alertdialog"' in src and 'aria-modal="true"' in src
    assert 'aria-labelledby="idle-title"' in src and 'aria-describedby="idle-desc"' in src
    assert "t('idle.desc', { seconds: idleSecondsLeft })" in src
    assert '@click="stayActive"' in src and '@click="logoutNow"' in src

    zh, en = _read(ZH), _read(EN)
    for key in ("idle.title", "idle.desc", "idle.stay", "idle.logout"):
        assert f"'{key}':" in zh and f"'{key}':" in en, f"文案缺 key：{key}"
    for line in en.splitlines():
        if line.strip().startswith("'idle."):
            value = line.split("':", 1)[-1]
            assert not re.search(r"[\u4e00-\u9fff]", value), f"英文混入中文：{line.strip()}"


def test_login_page_explains_idle_logout():
    src = _read(LOGIN)
    assert "route.query.reason === 'idle'" in src
    assert 'role="status"' in src
    assert "已自动退出登录" in src
