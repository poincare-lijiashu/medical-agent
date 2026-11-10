"""跨标签页全局登出（第 187 批）：共用工作站必须"一键全退"。

为什么：sessionStorage 按标签页隔离，控制台退出只清自己那一页；先前打开的 AI 工作台
标签页仍持有令牌——医生离开后患者数据还留在屏幕上，这是共用工作站最现实的数据泄漏路径。

锁定的事实：
1. 两端使用同一个 localStorage key（`medassist.session.logout.v1`），语义一致；
2. 监听只响应 `key 命中 && newValue 非空`（删除 key 的清理动作不能触发二次登出）；
3. 取消订阅后不再响应（组件卸载不泄漏监听）；
4. 两端登出动作都必须广播（否则只有单向失效）。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess

import pytest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUS_TS = os.path.join(REPO, "frontend-console", "src", "utils", "sessionBus.ts")
BUS_JS = os.path.join(REPO, "frontend-vue", "src", "utils", "sessionBus.js")
CONSOLE_SHELL = os.path.join(REPO, "frontend-console", "src", "layouts", "AppShell.vue")
VUE_SHELL = os.path.join(REPO, "frontend-vue", "src", "layouts", "AppShell.vue")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_both_frontends_share_same_logout_key():
    for path in (BUS_TS, BUS_JS):
        src = _read(path)
        assert "medassist.session.logout.v1" in src, f"{os.path.basename(path)} 的 key 必须一致"
        assert "addEventListener('storage'" in src and "removeEventListener('storage'" in src


def test_both_shells_broadcast_and_listen():
    console = _read(CONSOLE_SHELL)
    assert "broadcastLogout()" in console, "控制台登出必须广播"
    assert "onRemoteLogout(" in console, "控制台必须监听其他标签页的登出"
    assert "clearSession()" in console and "offRemoteLogout?.()" in console, "必须清理会话并在卸载时取消监听"
    vue = _read(VUE_SHELL)
    assert "broadcastLogout()" in vue, "AI 工作台登出必须广播"
    assert "onRemoteLogout(" in vue, "AI 工作台必须监听其他标签页的登出"
    assert "offRemoteLogout?.()" in vue, "组件卸载时必须取消监听"


def test_ts_and_js_bus_have_same_guard():
    """两端实现必须同构（TS 版无法被 node 直接执行，用同一段守卫表达式锁定语义）。"""
    ts = _read(BUS_TS)
    js = _read(BUS_JS)
    assert "e.key !== LOGOUT_KEY || !e.newValue" in ts
    assert "event.key !== LOGOUT_KEY || !event.newValue" in js


def _run_bus_behaviour(node: str, module_path: str) -> dict:
    harness = (
        "const store = new Map()\n"
        "globalThis.localStorage = {\n"
        "  getItem: (k) => (store.has(k) ? store.get(k) : null),\n"
        "  setItem: (k, v) => { store.set(k, String(v)) },\n"
        "  removeItem: (k) => { store.delete(k) },\n"
        "}\n"
        "const mod = await import(process.argv[1])\n"
        "const listeners = new Map()\n"
        "const target = {\n"
        "  addEventListener: (t, fn) => { listeners.set(t, fn) },\n"
        "  removeEventListener: (t) => { listeners.delete(t) },\n"
        "}\n"
        "let calls = 0\n"
        "const off = mod.onRemoteLogout(() => { calls += 1 }, target)\n"
        "const fire = (key, newValue) => { const fn = listeners.get('storage'); if (fn) fn({ key, newValue }) }\n"
        "fire('other.key', 'x')\n"
        "fire(mod.LOGOUT_KEY, null)\n"
        "fire(mod.LOGOUT_KEY, '{\"at\":1}')\n"
        "const afterSignal = calls\n"
        "off()\n"
        "fire(mod.LOGOUT_KEY, '{\"at\":2}')\n"
        "const broadcast = mod.broadcastLogout(123)\n"
        "const stored = JSON.parse(localStorage.getItem(mod.LOGOUT_KEY))\n"
        "console.log(JSON.stringify({ afterSignal, afterOff: calls, removed: !listeners.has('storage'), broadcast, stored }))\n"
    )
    out = subprocess.run([node, "--input-type=module", "-e", harness, "file:///" + module_path.replace("\\", "/")],
                         capture_output=True, text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout.strip().splitlines()[-1])


@pytest.mark.parametrize("module_path", [BUS_JS], ids=["vue-js"])
def test_logout_bus_behaviour(module_path):
    node = shutil.which("node")
    if not node:
        pytest.skip("本机没有 node，跳过行为测试（CI 的 frontend job 覆盖构建）")
    data = _run_bus_behaviour(node, module_path)
    assert data["afterSignal"] == 1, "只应对命中 key 且非空的信号回调一次"
    assert data["afterOff"] == 1, "取消订阅后不得再回调"
    assert data["removed"] is True
    assert data["broadcast"] is True
    assert data["stored"] == {"at": 123}, "广播内容必须带时间戳（便于排查）"
