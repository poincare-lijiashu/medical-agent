"""前端健壮性与性能预算（第 214 批）。

商业系统的两条底线：
1. **单页崩溃不白屏**：路由内容必须包在全局错误边界里，且错误态可恢复（重试/回首页），
   不能把异常堆栈渲染到界面；
2. **首屏不无限膨胀**：入口资源按 gzip 设预算，路由必须全部懒加载；框架运行时单独
   成块（vendor-vue），业务发版时它的哈希不变，长缓存可命中。
"""
from __future__ import annotations

import gzip
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
DIST = CONSOLE / "dist"
INDEX = DIST / "index.html"
SHELL = CONSOLE / "src/layouts/AppShell.vue"
BOUNDARY = CONSOLE / "src/components/ui/AppErrorBoundary.vue"
ROUTER = CONSOLE / "src/router/index.ts"

# 预算按 gzip 计：当前约 133KB（入口 91 + 框架 42），留出合理增长空间但不放任。
INITIAL_BUDGET_KB = 170
ENTRY_BUDGET_KB = 110
VENDOR_BUDGET_KB = 55


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _gzip_kb(path: Path) -> float:
    return len(gzip.compress(path.read_bytes(), 9)) / 1024


def _initial_assets() -> list[Path]:
    html = _read(INDEX)
    refs = set(re.findall(r'(?:src|href)="(/assets/[^"]+\.(?:js|css))"', html))
    refs |= set(re.findall(r'rel="modulepreload"[^>]*href="(/assets/[^"]+\.js)"', html))
    return [DIST / ref.lstrip("/") for ref in sorted(refs)]


def test_initial_payload_within_budget():
    assert INDEX.is_file(), "dist/index.html 缺失（先 npm run build）"
    assets = _initial_assets()
    assert assets, "index.html 未引用任何入口资源"
    total = sum(_gzip_kb(p) for p in assets if p.is_file())
    detail = ", ".join(f"{p.name}={_gzip_kb(p):.1f}KB" for p in assets if p.is_file())
    assert total <= INITIAL_BUDGET_KB, f"首屏 gzip {total:.1f}KB 超预算 {INITIAL_BUDGET_KB}KB：{detail}"

    entry = next((p for p in assets if p.name.startswith("index-") and p.suffix == ".js"), None)
    assert entry is not None, "找不到入口 index-*.js"
    assert _gzip_kb(entry) <= ENTRY_BUDGET_KB, "入口业务代码过大，考虑拆分或延迟加载"


def test_vendor_chunk_is_split():
    vendors = list(DIST.glob("assets/vendor-vue-*.js"))
    assert len(vendors) == 1, "vue/vue-router 必须单独成块（vendor-vue）"
    assert _gzip_kb(vendors[0]) <= VENDOR_BUDGET_KB, "框架运行时块超预算"


def test_all_views_are_lazy():
    router = _read(ROUTER)
    assert "from '@/views/" not in router, "路由不得静态导入视图（会打进首屏）"
    routes = re.findall(r"component: \(\) => import\('@/views/", router)
    assert len(routes) >= 60, f"懒加载路由只有 {len(routes)} 个，疑似有页面被同步打包"


def test_error_boundary_wraps_router_view():
    shell = _read(SHELL)
    assert "<AppErrorBoundary>" in shell and "</AppErrorBoundary>" in shell
    assert "AppErrorBoundary.vue" in shell
    block = shell.split("<AppErrorBoundary>", 1)[1].split("</AppErrorBoundary>", 1)[0]
    assert "<RouterView />" in block, "错误边界必须包住路由内容"


def test_error_boundary_is_recoverable_and_safe():
    src = _read(BOUNDARY)
    assert "onErrorCaptured" in src
    assert "error.value = null" in src, "必须能清除错误态（重试/切路由）"
    assert "router.push('/dashboard')" in src
    assert "error.stack" not in src, "不得把堆栈渲染到界面"
    for key in ("boundary.title", "boundary.desc", "boundary.retry", "boundary.home"):
        assert f"t('{key}')" in src, f"缺少文案 {key}"
