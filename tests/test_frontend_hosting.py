"""唯一前端托管锁（第 211 批）：控制台在根路径，旧前端已下线。

1. `/` 与 `/index.html` 直出 `frontend-console/dist/index.html`；
2. 深链刷新（/orders、/pharmacy…）回落 index.html，静态资源按文件直出；
3. `/console/**` 与 `/app/**` 仅作历史书签的 307 重定向；
4. `/api/**` 未匹配路径仍返回 JSON 404，不被 HTML 壳吞掉；
5. dist 随轮入库，Dockerfile 只打包控制台产物，vite base 为 `/`。
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


def test_app_dist_points_to_console():
    assert app_main.APP_DIST_DIR == CONSOLE_DIST
    assert app_main.CONSOLE_DIST_DIR == CONSOLE_DIST, "旧命名保留为同一目录的别名"
    assert "console" not in app_main._SPA_FALLBACK_EXCLUDED, \
        "/console 由显式 307 处理，不应进入 SPA 回落排除逻辑"


def test_console_is_the_only_packaged_frontend():
    dockerfile = _read(os.path.join(REPO, "Dockerfile"))
    assert "COPY frontend-console/dist" in dockerfile
    assert "frontend-vue" not in dockerfile, "旧前端不应再被打包"
    gitignore = _read(os.path.join(REPO, ".gitignore"))
    assert "!frontend-console/dist/" in gitignore and "!frontend-console/dist/**" in gitignore


def test_console_build_base_is_root():
    vite = _read(os.path.join(CONSOLE, "vite.config.ts"))
    assert "base: '/'" in vite, "唯一前端必须从根路径构建"
    router = _read(os.path.join(CONSOLE, "src", "router", "index.ts"))
    assert "createWebHistory(import.meta.env.BASE_URL)" in router


def test_console_index_uses_root_assets():
    idx = os.path.join(CONSOLE_DIST, "index.html")
    assert os.path.isfile(idx), "控制台产物应随轮入库（frontend-console/dist/index.html 缺失）"
    html = _read(idx)
    assert re.search(r'src="/assets/[^"]+\.js"', html), "index.html 必须引用 /assets/*"


def test_root_serves_spa_and_assets():
    c = TestClient(app)
    r = c.get("/")
    assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    assert "/assets/" in r.text

    for path in ("/orders", "/pharmacy", "/ai/agent/lab"):
        deep = c.get(path)
        assert deep.status_code == 200 and "text/html" in deep.headers["content-type"], \
            f"{path} 未回落 index.html"

    js_files = sorted(glob.glob(os.path.join(CONSOLE_DIST, "assets", "*.js")))
    assert js_files, "控制台产物缺少 assets"
    asset = "/assets/" + os.path.basename(js_files[0])
    ra = c.get(asset)
    assert ra.status_code == 200 and "javascript" in ra.headers["content-type"]

    api = c.get("/api/v1/definitely-not-a-route")
    assert api.status_code == 404 and "application/json" in api.headers["content-type"]


def test_legacy_paths_redirect_to_root():
    c = TestClient(app)
    cases = {
        "/console": "/",
        "/console/": "/",
        "/console/orders": "/orders",
        "/app": "/",
        "/app/overview": "/overview",
    }
    for old, new in cases.items():
        r = c.get(old, follow_redirects=False)
        assert r.status_code == 307, f"{old} 应 307"
        assert r.headers["location"] == new, f"{old} 应跳转到 {new}"
