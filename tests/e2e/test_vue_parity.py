"""Vue3 迁移整改轮 A 对照矩阵冒烟（docs/qa/vue_parity_matrix.md 轮 A 补齐项验收）。

覆盖轮 A 补齐的 4 项 ❌：
1. CaseView（doctor）：病例总结视图表单元素（病例框/附图/提交/免责条）——**不点提交，
   不触发任何 LLM 调用**（对照矩阵 C-3）；
2. OverviewView 重写：完整仪表盘（hero 欢迎语 / 5 指标卡 / 最近活动 / 快速进入），
   且不再出现「迁移轮1/过渡期」过时文案（对照矩阵 B 全部）；
3. 审核中心「其它科室会诊协助」tab（doctor/pharmacist 可见；admin/qc 无该 tab）
   + 收件箱容器渲染（对照矩阵 D-4/D-5）；
4. ArchView（admin）：架构·合规静态四卡（对照矩阵 F-8）。

说明：依赖运行中的服务 127.0.0.1:8001（healthz 不通则 skip）；未装 playwright 整文件
skip；演示口令 = seed（AUTH_DEMO_PASSWORD）Med@2026，与既有 e2e 文件同源。
doctor01 的三项断言合并为单函数顺序执行（同 test_vue_assist 风格），降低全量套件
连续执行时 doctor01 的登录/首刷请求密度（服务端 60 次/分钟滑动窗口限流，429 会红）。
"""
import urllib.request

import pytest

pytest.importorskip("playwright")  # 环境没装 playwright → 自动 skip，保证 CI 全绿

from playwright.sync_api import expect, sync_playwright  # noqa: E402

BASE_URL = "http://127.0.0.1:8001"
DEMO_PASSWORD = "Med@2026"


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


def _login(page, username: str) -> None:
    """以指定演示账号登录主入口 /：先清登录态（换角色隔离），再走登录表单。"""
    page.goto("/")
    page.evaluate("localStorage.clear()")
    page.goto("/")
    page.wait_for_selector('input[placeholder="用户名"]')
    page.fill('input[placeholder="用户名"]', username)
    page.fill('input[placeholder="密码"]', DEMO_PASSWORD)
    page.get_by_role("button", name="登录").click()
    page.wait_for_selector(".rail a.nav")  # 布局壳侧边导航


def _nav(page, label: str) -> None:
    page.click(f'.rail a.nav:has-text("{label}")')


def test_parity_doctor_case_overview_consults(page):
    """doctor01 单会话顺序断言（合并为单函数降低登录/首刷请求密度，避免 60/min 限流）：
    ①病例总结表单元素（不点提交→不触发 LLM）；②概览完整仪表盘；③审核中心会诊协助 tab。"""
    _login(page, "doctor01")

    # ---- ① 病例总结：表单元素（legacy AGENTS.case hint/免责条语义） ----
    expect(page.locator('.rail a.nav:has-text("病例总结")')).to_be_visible()
    _nav(page, "病例总结")
    expect(page.get_by_role("heading", name="多模态病例总结")).to_be_visible()
    q = page.locator('[data-testid="case-question"]')
    expect(q).to_be_visible()
    assert "粘贴病例要点" in (q.get_attribute("placeholder") or "")
    expect(page.locator('[data-testid="case-upload"]')).to_be_visible()
    expect(page.locator('[data-testid="case-submit"]')).to_be_visible()
    expect(page.locator('[data-testid="case-disclaimer"]')).to_contain_text("结论须由执业医师复核")

    # ---- ② 概览：hero/指标卡/最近活动/快速进入，且无过渡期过时文案 ----
    _nav(page, "概览")
    expect(page.locator('[data-testid="ov-hero"]')).to_contain_text("欢迎回来，doctor01")
    expect(page.locator('[data-testid="ov-hero"]')).to_contain_text("不构成诊断、不开处方")
    stats = page.locator('[data-testid="ov-stats"]')
    expect(stats).to_be_visible()
    for k in ("待双人核对", "已处理核对", "知识库文档", "近期事件", "系统版本"):
        expect(stats.get_by_text(k)).to_be_visible()
    expect(page.locator('[data-testid="ov-recent"]')).to_contain_text("最近活动")
    quick = page.locator('[data-testid="ov-quick"]')
    expect(quick).to_contain_text("快速进入")
    expect(quick.get_by_text("病例总结")).to_be_visible()
    body = page.locator(".page-body").inner_text()
    assert "迁移轮1" not in body and "过渡期" not in body

    # ---- ③ 审核中心第三栏「其它科室会诊协助」：tab 可见 + 收件箱容器渲染 ----
    _nav(page, "审核中心")
    tab = page.locator('[data-testid="review-tab-consults"]')
    expect(tab).to_be_visible()
    expect(tab).to_contain_text("其它科室会诊协助")
    tab.click()
    box = page.locator('[data-testid="consult-inbox"]')
    expect(box).to_be_visible()
    # 空态文案或会诊卡片任一渲染（数据态不锁定，demo 会诊单可能存在）
    expect(box.get_by_text("会诊").first).to_be_visible()


def test_parity_pharmacist_consults_tab(page):
    """pharmacist 审核中心同样可见「其它科室会诊协助」tab。"""
    _login(page, "pharm01")
    _nav(page, "审核中心")
    tab = page.locator('[data-testid="review-tab-consults"]')
    expect(tab).to_be_visible()
    expect(tab).to_contain_text("其它科室会诊协助")
    # pharmacist 无病例库 tab（既有语义回归：qc/admin 专属）
    expect(page.locator('[data-testid="review-tab-casearchive"]')).to_have_count(0)


def test_parity_admin_arch_view(page):
    """admin 打开「架构 · 合规」断言四卡；且 admin 审核中心无会诊协助 tab。"""
    _login(page, "admin01")
    expect(page.locator('.rail a.nav:has-text("架构 · 合规")')).to_be_visible()
    _nav(page, "架构 · 合规")
    expect(page.get_by_role("heading", name="架构 · 合规")).to_be_visible()
    expect(page.locator('[data-testid="arch-layers"]')).to_contain_text("六层架构")
    expect(page.locator('[data-testid="arch-rag"]')).to_contain_text("反幻觉四闸")
    expect(page.locator('[data-testid="arch-dual"]')).to_contain_text("高危双人核对")
    expect(page.locator('[data-testid="arch-compliance"]')).to_contain_text("合规红线")
    # admin 无「其它科室会诊协助」tab（legacy renderReviewHeader：tc 仅 mine 角色显示）
    _nav(page, "审核中心")
    expect(page.locator('[data-testid="review-tab-consults"]')).to_have_count(0)
