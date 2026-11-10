"""同域 SSO 交接（第 186 批）：控制台 → AI 工作台，令牌不进 URL。

为什么需要这一层：两套 UI 共用同一枚 Java 签发的 JWT（Python 侧 `deps` 已按 `roles`
映射角色并做吊销校验），但控制台会话在 sessionStorage、AI 工作台在另一个标签页——
不交接就会"跳到对面又要登录一次"，这是最像拼装系统的地方。

设计约束（本文件锁死）：
1. 交接走 localStorage 一次性票据，**不得**把令牌放 URL（历史/Referer/代理日志泄漏面）；
2. 票据 60 秒过期、读即删除（先删再解析，坏票据也只能用一次）；
3. AI 工作台必须在 **router 挂载前**采纳令牌，且仍由 `/auth/me` 服务端确认身份。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess

import pytest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONSOLE_SSO = os.path.join(REPO, "frontend-console", "src", "utils", "sso.ts")
CONSOLE_SHELL = os.path.join(REPO, "frontend-console", "src", "layouts", "AppShell.vue")
VUE_SSO = os.path.join(REPO, "frontend-vue", "src", "utils", "sso.js")
VUE_MAIN = os.path.join(REPO, "frontend-vue", "src", "main.js")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_console_issues_one_time_ticket():
    src = _read(CONSOLE_SSO)
    assert "medassist.sso.handoff.v1" in src
    assert "60_000" in src, "票据必须有过期时间（60 秒）"
    shell = _read(CONSOLE_SHELL)
    assert "issueHandoff(session.state.accessToken)" in shell, "打开 AI 工作台前必须写票据"
    assert "window.open('/', '_blank', 'noopener')" in shell
    assert "token=" not in shell, "控制台不得把令牌拼进 URL"


def test_ai_workbench_consumes_ticket_before_router_mount():
    main = _read(VUE_MAIN)
    assert "takeHandoff()" in main
    assert "adoptToken" in main, "票据令牌必须经 /auth/me 服务端确认"
    assert main.index("takeHandoff()") < main.index("app.use(router)"), \
        "必须先采纳令牌再挂载路由，否则守卫会把首屏踢回登录页"


def test_take_handoff_ttl_and_single_use(tmp_path):
    """用 node 直接跑 sso.js：验证一次性、过期、坏数据三条真实行为。"""
    node = shutil.which("node")
    if not node:
        pytest.skip("本机没有 node，跳过行为测试（CI 的 frontend job 会覆盖构建）")
    harness = tmp_path / "harness.mjs"
    harness.write_text(
        """
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) },
}
const mod = await import(process.argv[2])
const KEY = mod.HANDOFF_KEY
const now = Date.now()
localStorage.setItem(KEY, JSON.stringify({ token: 'tk-1', at: now }))
const first = mod.takeHandoff(now + 1000)
const second = mod.takeHandoff(now + 1000)
localStorage.setItem(KEY, JSON.stringify({ token: 'tk-2', at: now - 120000 }))
const expired = mod.takeHandoff(now)
localStorage.setItem(KEY, '{bad json')
const bad = mod.takeHandoff(now)
const missing = mod.takeHandoff(now)
console.log(JSON.stringify({ first, second, expired, bad, missing }))
""",
        encoding="utf-8",
    )
    out = subprocess.run([node, str(harness), "file:///" + VUE_SSO.replace("\\", "/")],
                         capture_output=True, text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    data = json.loads(out.stdout.strip().splitlines()[-1])
    assert data["first"] == "tk-1"
    assert data["second"] == "", "票据只能使用一次"
    assert data["expired"] == "", "超过 60 秒必须失效"
    assert data["bad"] == "" and data["missing"] == ""
