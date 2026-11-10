"""跨端 SSO 交接已随旧前端下线（第 211 批）。

背景：第 186 批曾用一次性票据把控制台登录态交给根路径的 AI 工作台。旧前端删除后，
AI 能力已内联为控制台 `/ai/*` 页面，不再需要跨端交接——保留它会变成无消费者的死代码，
且用户菜单里会出现"点了又回到同一系统"的假入口。

本文件锁住收口后的契约：
1. `utils/sso.ts` 不再存在，用户菜单不再有"AI 深度工作台"入口；
2. 控制台内不得出现 `window.open('/')` 这类绕过路由的跨端跳转；
3. 平台单点登录回调页仍在（这是登录入口，不是跨端交接）。
"""
from __future__ import annotations

import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "frontend-console", "src")
SHELL = os.path.join(SRC, "layouts", "AppShell.vue")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_legacy_handoff_module_removed():
    assert not os.path.exists(os.path.join(SRC, "utils", "sso.ts")), \
        "旧跨端交接模块必须删除，避免死代码与误用"


def test_shell_has_no_cross_entry_menu():
    shell = _read(SHELL)
    assert "openAiWorkbench" not in shell
    assert "issueHandoff" not in shell
    assert "window.open('/'" not in shell, "不得绕过路由新开旧工作台"
    assert "shell.aiWorkbench" not in shell


def test_platform_sso_callback_still_present():
    view = os.path.join(SRC, "views", "SsoCallbackView.vue")
    assert os.path.isfile(view), "平台单点登录回调页是登录入口，不能随旧前端一起删除"
    router = _read(os.path.join(SRC, "router", "index.ts"))
    assert "sso-callback" in router and "SsoCallbackView.vue" in router
