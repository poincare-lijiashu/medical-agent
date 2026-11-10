"""前端双入口托管锁（第 183 批）：一个镜像、一个域名，两套 UI 各有明确路径。

背景：仓库里有两代前端——`frontend-vue`（AI 中台工作台，14 视图，`/api/v1`）与
`frontend-console`（HIS 临床控制台，46 视图，`/api/v2` + `/api/v1`）。此前控制台只存在于
`npm run dev`：产物不入库、镜像不打包、生产没有任何入口，等于"写了但交付不出去"。

本文件锁住收敛后的契约：
1. `/`（与 `/app`）仍是 AI 工作台；`/console/**` 是控制台，两者同镜像同域；
2. 控制台的 dist 入库、Dockerfile 打包，`vite base` 与 router 的 history base 都是 `/console/`；
3. `/console` 深链刷新回落 index.html，静态资源按文件直出，`/api/**` 不被 HTML 壳吞掉。
"""
from __future__ import annotations

import glob
import os
import re

from fastapi.testclient import TestClient

from backend import main as app_main
from backend.main import app

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONSOLE = os.path.join(REPO, "frontend-console")
CONSOLE_DIST = os.path.join(CONSOLE, "dist")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_console_dist_dir_points_to_frontend_console():
    assert app_main.CONSOLE_DIST_DIR == CONSOLE_DIST
    assert "console" in app_main._SPA_FALLBACK_EXCLUDED, \
        "根路径 SPA 回落必须排除 /console，否则控制台路径会被旧前端壳吞掉"


def test_console_is_packaged_and_committed():
    dockerfile = _read(os.path.join(REPO, "Dockerfile"))
    assert "COPY frontend-console/dist" in dockerfile, "镜像必须打包控制台产物"
    assert "COPY frontend-vue/dist" in dockerfile, "AI 工作台产物不能丢"
    gitignore = _read(os.path.join(REPO, ".gitignore"))
    assert "!frontend-console/dist/" in gitignore and "!frontend-console/dist/**" in gitignore, \
        "控制台 dist 未豁免则不会被提交，镜像构建时目录为空"


def test_console_build_base_matches_mount_path():
    vite = _read(os.path.join(CONSOLE, "vite.config.ts"))
    assert "command === 'build' ? '/console/'" in vite, "构建 base 必须是 /console/"
    router = _read(os.path.join(CONSOLE, "src", "router", "index.ts"))
    assert "createWebHistory(import.meta.env.BASE_URL)" in router, \
        "router 必须用 BASE_URL，否则深链会按根路径解析"


def test_console_index_uses_sub_path_assets():
    idx = os.path.join(CONSOLE_DIST, "index.html")
    assert os.path.isfile(idx), "控制台产物应随轮入库（frontend-console/dist/index.html 缺失）"
    html = _read(idx)
    assert re.search(r'src="/console/assets/[^"]+\.js"', html), "index.html 必须引用 /console/assets/*"


def test_console_routes_serve_spa_and_assets():
    c = TestClient(app)
    r = c.get("/console/")
    assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    assert "/console/assets/" in r.text

    # 深链刷新（控制台真实路由：医嘱工作台 / 药房）
    for path in ("/console/orders", "/console/pharmacy"):
        deep = c.get(path)
        assert deep.status_code == 200 and "text/html" in deep.headers["content-type"], \
            f"{path} 未回落 index.html"

    # 静态资源真实直出（不是 HTML 壳）
    js_files = sorted(glob.glob(os.path.join(CONSOLE_DIST, "assets", "*.js")))
    assert js_files, "控制台产物缺少 assets"
    asset = "/console/assets/" + os.path.basename(js_files[0])
    ra = c.get(asset)
    assert ra.status_code == 200 and "javascript" in ra.headers["content-type"]

    # API 未匹配路径仍返回 JSON 404，不能回落到任一前端壳
    api = c.get("/api/v1/definitely-not-a-route")
    assert api.status_code == 404 and "application/json" in api.headers["content-type"]


def test_ai_workbench_still_served_at_root():
    c = TestClient(app)
    r = c.get("/")
    assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    # 旧前端资源仍在根路径（/assets/*），控制台资源在 /console/assets/*，两者不冲突
    assert "/console/assets/" not in r.text


def test_cross_entry_links_between_two_uis():
    """第 185 批：两套 UI 同域后必须能互相跳转（否则用户要靠记 URL，仍像两套系统）。"""
    console_shell = _read(os.path.join(CONSOLE, "src", "layouts", "AppShell.vue"))
    assert 'href="/"' in console_shell and "shell.aiWorkbench" in console_shell, \
        "临床控制台必须提供进入 AI 工作台的入口"
    vue_shell = _read(os.path.join(REPO, "frontend-vue", "src", "layouts", "AppShell.vue"))
    assert 'href="/console/"' in vue_shell, "AI 工作台必须提供进入临床控制台的入口"
    i18n_zh = _read(os.path.join(CONSOLE, "src", "i18n", "zh-CN.ts"))
    assert "'shell.aiWorkbench'" in i18n_zh, "跨端入口文案必须走 i18n 字典"
