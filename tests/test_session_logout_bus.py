"""跨标签页全局登出（第 187 批）：共用工作站必须"一键全退"。

为什么：sessionStorage 按标签页隔离，控制台退出只清自己那一页；先前打开的工作台
标签页仍持有令牌——医生离开后患者数据还留在屏幕上，这是共用工作站最现实的数据泄漏路径。

锁定的事实（旧前端下线后只剩控制台一份实现）：
1. 控制台使用 localStorage key（`medassist.session.logout.v1`）；
2. 监听只响应 `key 命中 && newValue 非空`（删除 key 的清理动作不能触发二次登出）；
3. 取消订阅后不再响应（组件卸载不泄漏监听）；
4. 控制台登出动作必须广播（否则其它标签页不失效）。
"""
from __future__ import annotations

import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUS_TS = os.path.join(REPO, "frontend-console", "src", "utils", "sessionBus.ts")
CONSOLE_SHELL = os.path.join(REPO, "frontend-console", "src", "layouts", "AppShell.vue")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_console_bus_uses_shared_logout_key():
    src = _read(BUS_TS)
    assert "medassist.session.logout.v1" in src, "sessionBus 的 key 必须与会话层一致"
    assert "addEventListener('storage'" in src and "removeEventListener('storage'" in src


def test_console_shell_broadcasts_and_listens():
    console = _read(CONSOLE_SHELL)
    assert "broadcastLogout()" in console, "控制台登出必须广播"
    assert "onRemoteLogout(" in console, "控制台必须监听其他标签页的登出"
    assert "clearSession()" in console and "offRemoteLogout?.()" in console, \
        "必须清理会话并在卸载时取消监听"


def test_bus_guard_ignores_empty_value():
    """守卫语义：key 命中且 newValue 非空才触发（删除 key 不算登出信号）。"""
    ts = _read(BUS_TS)
    assert "e.key !== LOGOUT_KEY || !e.newValue" in ts
