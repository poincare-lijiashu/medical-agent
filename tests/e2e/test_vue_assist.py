"""Vue3 迁移轮2 E2E 冒烟（docs/plans/vue3_migration.md）：四助手视图可打开。

链路：主入口 / 登录 doctor01 → 依次导航 医学文献 / 影像辅助 / 多智能体会诊 / 我的会诊 /
开药工作台 → 断言关键元素（提问框 / 上传按钮 / 病例框 / DRUG_DISCLAIMER 免责文案 /
药单区）。**不触发任何 LLM 调用**（只打开视图与断言，不点发送/生成/发起）。

说明：
- 轮4 起默认入口 / 直出 Vue3 应用；/app 双入口共用同一份产物均可跑（二选一：
  本文件统一走 / 主入口，与 test_smoke_flow.py 一致）。
- 依赖运行中的服务 127.0.0.1:8001（healthz 不通则 skip）；未装 playwright 整文件 skip。
- 免责声明断言为 DRUG_DISCLAIMER 逐字片段（tests/test_drug_dict_admin.py 同源锁定）。
"""
import urllib.request

import pytest

pytest.importorskip("playwright")  # 环境没装 playwright → 自动 skip，保证 CI 全绿

pytestmark = [pytest.mark.e2e, pytest.mark.skip(reason="旧 vanilla UI 用例：菜单项已随 Vue3 迁移移除，待按当前 UI 重写；当前门禁见 test_console_smoke_current.py（评测 E7-L3）")]

from playwright.sync_api import expect, sync_playwright  # noqa: E402

from tests.e2e._ratelimit import login_with_rate_limit_retry  # noqa: E402

BASE_URL = "http://127.0.0.1:8001"
# 免责声明片段（DRUG_DISCLAIMER 前半句，足以锁定文案不漂移）
RX_DISCLAIMER_SNIPPET = "药品数据由 AI 辅助生成，临床使用前必须经执业药师核对"


def _service_up() -> bool:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def page():
    if not _service_up():
        pytest.skip(f"服务不可达：{BASE_URL}（healthz 未通过），跳过 Vue 冒烟")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(base_url=BASE_URL, locale="zh-CN")
        pg = ctx.new_page()
        pg.set_default_timeout(15_000)
        yield pg
        ctx.close()
        browser.close()


def _submit_login_app(page) -> None:
    """填表并提交（可重放）。"""
    page.goto("/")
    page.wait_for_selector('input[placeholder*="工号"]')
    page.fill('input[placeholder*="工号"]', "doctor01")
    page.fill('input[type="password"]', "Med@2026")  # seed 演示口令（AUTH_DEMO_PASSWORD）
    page.get_by_role("button", name="登录").click()


def _login_app(page) -> None:
    """登录 Vue 应用：占位输入 + 登录按钮 → 等待侧边导航出现。

    第 114 批：撞到 60 次/分钟限流时自动等窗口重放一次（环境抖动不吞真实失败）。
    """
    login_with_rate_limit_retry(page, lambda: _submit_login_app(page))


def _nav(page, label: str) -> None:
    page.click(f'.nav-item:has-text("{label}")')


def test_vue_app_assist_views_open(page):
    """四助手视图逐个打开并断言关键元素（不触发 LLM）。"""
    _login_app(page)

    # ---- ① 文献助手：提问框 + 发送按钮 + 新建对话 ----
    _nav(page, "医学文献")
    expect(page.locator('[data-testid="lit-input"]')).to_be_visible()
    expect(page.locator('[data-testid="lit-send"]')).to_be_visible()
    expect(page.get_by_text("新建对话")).to_be_visible()

    # ---- ② 影像阅片：上传按钮 + 免责条 ----
    _nav(page, "影像辅助")
    expect(page.locator('[data-testid="img-upload"]')).to_be_visible()
    expect(page.locator('[data-testid="img-disclaimer"]')).to_contain_text("结论须由执业医师复核")

    # ---- ③ MDT 会诊：病例框 + 双发起按钮；我的会诊：页面标题渲染 ----
    _nav(page, "多智能体会诊")
    expect(page.locator('[data-testid="mdt-case"]')).to_be_visible()
    expect(page.locator('[data-testid="mdt-run"]')).to_be_visible()
    expect(page.locator('[data-testid="mdt-real"]')).to_be_visible()
    _nav(page, "我的会诊")
    expect(page.get_by_role("heading", name="我的会诊")).to_be_visible()

    # ---- ④ 开药工作台：DRUG_DISCLAIMER 免责文案逐字片段 + 药单区空态 + 双 tab ----
    _nav(page, "开药工作台")
    expect(page.locator('[data-testid="rx-disclaimer"]')).to_contain_text(RX_DISCLAIMER_SNIPPET)
    expect(page.locator('[data-testid="rx-selected"]')).to_contain_text("药单为空")
    expect(page.locator('[data-testid="rx-tab-compose"]')).to_be_visible()
    expect(page.locator('[data-testid="rx-tab-mine"]')).to_be_visible()
