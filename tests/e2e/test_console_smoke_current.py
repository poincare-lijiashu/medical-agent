"""当前控制台主链路冒烟（真实浏览器，CI 阻断项）。

背景：`tests/e2e/test_vue_*.py` 与 `test_smoke_flow.py` 是 Vue3 迁移前（旧 vanilla UI）
的用例，其中菜单项（开药工作台/Agent 控制台/病例总结/架构 · 合规…）在当前导航中已不存在，
评测 E7-L3 之前整组从未在 CI 执行。这里不做"改断言凑绿"，而是：
- 旧用例显式 skip 并登记待迁移（见各文件模块级 skip reason）；
- 本文件用**当前真实菜单与真实页面**覆盖最关键的一条链路，作为可阻断的回归门禁。

覆盖：登录 → 运营驾驶舱渲染 → 侧栏可用 → 进入「上线指挥台」→ 进入「药房工作台」→
返回驾驶舱（验证会话未丢、未跳登录页）。

环境要求：服务已在 127.0.0.1:8001 运行且注入了演示账号（AUTH_SEED_DEMO=true），
**并且 Java 平台栈可达**——2.0 控制台的登录走 `/api/v2/auth/login`，由本进程反代到
Java 网关（见 backend/main.py `gateway_proxy`），只起 Python 进程时该请求必然 502。
两者任一不可达就整文件 skip（不在无栈的开发机/CI 上制造假红）。
"""
from __future__ import annotations

import urllib.error
import urllib.request

import pytest

pytest.importorskip("playwright")

pytestmark = pytest.mark.e2e

from playwright.sync_api import expect, sync_playwright  # noqa: E402

BASE_URL = "http://127.0.0.1:8001"
DEMO_USER = "admin01"
DEMO_PASSWORD = "Med@2026"
LOGIN_INPUT = 'input[placeholder*="工号"]'


def _service_up() -> bool:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


def _platform_stack_up() -> bool:
    """Java 平台栈是否可达（控制台登录的唯一通道）。

    2026-11-14 CI 现场：Python 进程健康、静态资源全 200，但
    `POST /api/v2/auth/login` → 502（uvicorn.log: "All connection attempts failed"），
    浏览器停在登录页 20s 超时。这不是前端缺陷，而是"只起了半个栈"。
    探针用无副作用、免登录的 SSO 配置接口：上游不可达时反代返回 5xx。
    """
    try:
        with urllib.request.urlopen(BASE_URL + "/api/v2/auth/sso/config", timeout=5) as r:
            return r.status < 500
    except urllib.error.HTTPError as exc:  # 4xx 说明网关在、路由在；5xx 说明上游不可达
        return exc.code < 500
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def page():
    if not _service_up():
        pytest.skip(f"服务不可达：{BASE_URL}（healthz 未通过）")
    if not _platform_stack_up():
        pytest.skip(f"平台栈不可达：{BASE_URL}/api/v2/auth/** 返回 5xx"
                    "（控制台登录经 Java 网关；仅起 Python 进程无法完成登录）")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(base_url=BASE_URL, locale="zh-CN")
        pg = ctx.new_page()
        pg.set_default_timeout(20_000)
        yield pg
        ctx.close()
        browser.close()


def _login(page) -> None:
    """清空两种登录态（Cookie + Storage）后以管理员登录。

    登录态同时存在于 httpOnly Cookie 与 localStorage；只清 Storage 会让 "/" 直接进
    驾驶舱，登录表单不存在（这是旧用例整组超时的直接原因之一）。
    """
    page.context.clear_cookies()
    page.goto("/login")
    page.evaluate("localStorage.clear(); sessionStorage.clear()")
    page.goto("/login")
    page.wait_for_selector(LOGIN_INPUT)
    page.fill(LOGIN_INPUT, DEMO_USER)
    page.fill('input[type="password"]', DEMO_PASSWORD)
    page.get_by_role("button", name="登录").click()
    page.wait_for_selector(".nav-item", timeout=20_000)


def _nav(page, label: str) -> None:
    page.click(f'.nav-item:has-text("{label}")')


def test_login_lands_on_dashboard(page):
    _login(page)
    expect(page).to_have_url(f"{BASE_URL}/dashboard")
    # 驾驶舱必须有真实标题与 KPI 区块（不是空壳）
    expect(page.locator("h1").first).to_contain_text("运营驾驶舱")
    assert page.locator(".card, .kpi, [class*=stat]").count() > 0


def test_sidebar_navigates_without_logout(page):
    """侧栏跳转即被登出是历史现场问题（§304），这里锁死回归。"""
    _login(page)
    _nav(page, "上线指挥台")
    expect(page.locator("h1").first).to_contain_text("上线指挥台")
    assert "/login" not in page.url

    _nav(page, "药房工作台")
    expect(page.locator("h1").first).to_contain_text("药房工作台")
    assert "/login" not in page.url

    _nav(page, "运营驾驶舱")
    expect(page.locator("h1").first).to_contain_text("运营驾驶舱")
    assert "/login" not in page.url


def test_no_section_load_error_on_dashboard(page):
    """驾驶舱任一区块"数据加载失败"都是 P1 级回归（现场曾出现多页同时失败）。"""
    _login(page)
    page.wait_for_timeout(1500)
    body = page.inner_text("body")
    for bad in ("数据加载失败", "系统处理异常，已记录日志"):
        assert bad not in body, f"驾驶舱出现加载失败提示：{bad}"
