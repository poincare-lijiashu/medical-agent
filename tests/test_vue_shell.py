"""Vue3 迁移轮4 壳验收（docs/archive/plans/vue3_migration.md）：/ 与 /app 双入口均直出新应用。

轻量断言四件套：
1. TestClient GET / 直出 Vue3 新壳（默认入口切换：/assets/ 产物引用 + Vue 挂载点 #app）；
2. GET /app 直出同一份产物（轮1-3 旧入口保留兼容，/app/assets 旧引用并行可达）；
3. frontend-vue/dist/index.html 构建产物随轮入库（回退免 node 的手册策略）；
4. frontend-vue/.npmrc 锁定 npmmirror 镜像（网络受限环境保 npm 安装成功）。

legacy vanilla 前端已于轮4 删除（git tag v-legacy-frontend 永久保存，回退见手册）。
"""
from pathlib import Path

from fastapi.testclient import TestClient

from backend.main import app

VUE_ROOT = Path(__file__).resolve().parent.parent / "frontend-vue"
VUE_INDEX = VUE_ROOT / "dist" / "index.html"

client = TestClient(app)


def test_root_serves_vue_shell():
    """轮4 切换验收：GET / 默认入口直出 Vue3 新壳（/assets/* 产物引用 + 挂载点 #app）。"""
    r = client.get("/")
    assert r.status_code == 200
    assert "/assets/" in r.text, "新壳 HTML 应引用 /assets/*（轮4 起 vite base=/）"
    assert 'id="app"' in r.text, "新壳 HTML 应含 Vue 挂载点 #app"


def test_app_mount_serves_vue_shell():
    """/app 旧入口并行保留：GET /app 直出同一份 Vue3 产物（历史书签兼容）。"""
    r = client.get("/app")
    assert r.status_code == 200
    assert "/assets/" in r.text and 'id="app"' in r.text, "/app 应直出同一份 Vue3 产物"


def test_vue_dist_committed():
    """dist 构建产物随轮提交：dist/index.html 存在且含至少一个 assets 静态文件。"""
    assert VUE_INDEX.is_file(), "frontend-vue/dist/index.html 应入库（回退免 node 策略）"
    assets = VUE_INDEX.parent / "assets"
    assert assets.is_dir() and any(assets.iterdir()), "dist/assets 应存在构建出的 JS/CSS"


def test_vue_asset_served_from_root():
    """静态资源可达：/assets/*.js 经根挂载 StaticFiles 返回 200（默认入口资源链路）。"""
    idx = VUE_INDEX.read_text(encoding="utf-8")
    import re

    m = re.search(r'src="(/assets/[^"]+\.js)"', idx)
    assert m, "dist/index.html 应含 /assets/*.js 产物引用"
    r = client.get(m.group(1))
    assert r.status_code == 200, f"构建产物 JS 应可从根路径取到：{m.group(1)}"


def test_npmrc_mirror_lock():
    """npm registry 锁 npmmirror（.npmrc 入库，保证任何环境安装一致）。"""
    npmrc = VUE_ROOT / ".npmrc"
    assert npmrc.is_file()
    content = npmrc.read_text(encoding="utf-8")
    assert "registry=https://registry.npmmirror.com" in content


# ---- F5：SPA fallback（vue-router createWebHistory 深链刷新 404 修复）----

def test_spa_fallback_deep_links_serve_shell():
    """/review /rx /kb 等前端路由深链刷新 → 200 直出新壳（不再 404），vue-router 接管。"""
    for path in ("/review", "/rx", "/kb", "/overview", "/literature", "/mdt", "/qc", "/data"):
        r = client.get(path)
        assert r.status_code == 200, f"深链 {path} 应 SPA fallback 直出新壳"
        assert "/assets/" in r.text and 'id="app"' in r.text, f"深链 {path} 应返回 Vue 新壳 HTML"


def test_api_prefix_never_falls_back():
    """API 前缀不回退：/api/v1/nonexistent 仍 404 JSON（不吐 HTML 壳，前端误调用可辨）。"""
    r = client.get("/api/v1/nonexistent")
    assert r.status_code == 404
    assert r.headers["content-type"].startswith("application/json")
    assert r.json().get("detail") == "Not Found"


def test_spa_fallback_excluded_prefixes_stay_404():
    """排除清单前缀不回退 HTML 壳：healthz/readyz 有显式路由；/js 等不存在前缀 404 JSON。"""
    assert client.get("/healthz").status_code == 200       # 显式路由优先不受 catch-all 影响
    assert client.get("/readyz").status_code == 200
    r = client.get("/js/app.js")                            # /js 历史残留前缀：404 JSON 不回壳
    assert r.status_code == 404 and r.headers["content-type"].startswith("application/json")


def test_spa_fallback_assets_still_served():
    """/assets 静态资源在 catch-all 之下仍 200（文件存在性直出，等价原 StaticFiles 行为）。"""
    idx = VUE_INDEX.read_text(encoding="utf-8")
    import re

    m = re.search(r'src="(/assets/[^"]+\.js)"', idx)
    assert m
    r = client.get(m.group(1))
    assert r.status_code == 200, f"catch-all 之后静态资源必须仍可达：{m.group(1)}"
