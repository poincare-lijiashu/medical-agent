"""Vue3 迁移轮1 壳验收（docs/plans/vue3_migration.md）：/app 挂载存在且不影响 legacy。

轻量断言四件套：
1. TestClient GET /app 直出新壳 HTML（Vite 构建标记：资源引用带 /app/assets/ 前缀）；
2. frontend-vue/dist/index.html 构建产物随轮入库（回退免 node 的手册策略）；
3. legacy 主入口 / 完全不受影响（仍含 /js/state.js 外链标记）；
4. frontend-vue/.npmrc 锁定 npmmirror 镜像（网络受限环境保 npm 安装成功）。

Playwright 浏览器层验证在轮4 前始终指向 legacy 主入口（本轮不动）。
"""
import os
from pathlib import Path

from fastapi.testclient import TestClient

from backend.main import app

VUE_ROOT = Path(__file__).resolve().parent.parent / "frontend-vue"
VUE_INDEX = VUE_ROOT / "dist" / "index.html"

client = TestClient(app)


def test_app_mount_serves_vue_shell():
    """GET /app 返回 Vue3 新壳：构建产物 HTML 带 /app/assets/ 资源引用标记。"""
    r = client.get("/app")
    assert r.status_code == 200
    assert "/app/assets/" in r.text, "新壳 HTML 应引用 /app/assets/*（vite base=/app/）"
    assert 'id="app"' in r.text, "新壳 HTML 应含 Vue 挂载点 #app"


def test_vue_dist_committed():
    """dist 构建产物随轮提交：dist/index.html 存在且含至少一个 assets 静态文件。"""
    assert VUE_INDEX.is_file(), "frontend-vue/dist/index.html 应入库（回退免 node 策略）"
    assets = VUE_INDEX.parent / "assets"
    assert assets.is_dir() and any(assets.iterdir()), "dist/assets 应存在构建出的 JS/CSS"


def test_legacy_root_untouched():
    """legacy 主入口零改动：GET / 仍返回 vanilla 壳（/js/state.js 外链标记）。"""
    r = client.get("/")
    assert r.status_code == 200
    assert "/js/state.js" in r.text, "legacy 首页应仍引用 /js/state.js"


def test_npmrc_mirror_lock():
    """npm registry 锁 npmmirror（.npmrc 入库，保证任何环境安装一致）。"""
    npmrc = VUE_ROOT / ".npmrc"
    assert npmrc.is_file()
    content = npmrc.read_text(encoding="utf-8")
    assert "registry=https://registry.npmmirror.com" in content
