"""Vue3 迁移轮3 E2E 冒烟（docs/archive/plans/vue3_migration.md）：管理视图可打开。

链路：pharm01 登录 → 审核中心（待处理含开药待审区 / 我的历史 / 药品字典 tab 免责声明 /
相互作用规则 tab 覆盖率 chip）；admin01 → 知识库（向量总数统计）/ 数据面板（审计流/
留痕模式/应用日志/LLM 配置）+ 病例库 tab；qc01 → 审核中心病例库 tab；doctor01 →
无管理菜单（知识库管理/数据面板不出现）。**不触发任何 LLM 调用、不执行任何删除/
写操作**（只打开视图与断言只读元素，删除/审核按钮一律不点击）。

说明：
- 轮4 起默认入口 / 直出 Vue3 应用；/app 双入口共用同一份产物均可跑（二选一：
  本文件统一走 / 主入口，与 test_smoke_flow.py 一致）。
- 依赖运行中的服务 127.0.0.1:8001（healthz 不通则 skip）；未装 playwright 整文件 skip。
- 种子口令 = seed（AUTH_DEMO_PASSWORD）Med@2026，与 tests/e2e/test_vue_assist.py 同源。
"""
import urllib.request

import pytest

pytest.importorskip("playwright")  # 环境没装 playwright → 自动 skip，保证 CI 全绿

from playwright.sync_api import expect, sync_playwright  # noqa: E402

BASE_URL = "http://127.0.0.1:8001"

# 种子账号（backend/core/auth.py seed_default_users 同源；口令=AUTH_DEMO_PASSWORD）
DEMO_PASSWORD = "Med@2026"

# 免责声明片段（DRUG_DISCLAIMER 前半句，tests/test_drug_dict_admin.py 同源锁定）
DRUG_DISCLAIMER_SNIPPET = "药品数据由 AI 辅助生成"


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
    """以指定种子账号登录主入口 /：先清登录态（换角色隔离），再走登录表单。"""
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


def test_vue_admin_pharmacist_review_center(page):
    """pharm01：审核中心药剂科视图——开药待审区 + 三 tab + 字典/规则两个管理 tab。"""
    _login(page, "pharm01")
    _nav(page, "审核中心")

    # 头部文案（pharmacist 分支逐字）+ 开药待审区
    expect(page.locator('[data-testid="review-title"]')).to_have_text("审核中心 · 药剂科")
    expect(page.locator('[data-testid="rx-pending-section"]')).to_contain_text("开药待审（处方队列）")

    # tab 集：待处理 / 我的历史 / 药品字典 / 相互作用规则；病例库 tab 不渲染（非 qc/admin）
    expect(page.locator('[data-testid="review-tab-pending"]')).to_be_visible()
    expect(page.locator('[data-testid="review-tab-history"]')).to_have_text("我的历史")
    expect(page.locator('[data-testid="review-tab-casearchive"]')).to_have_count(0)

    # 药品字典 tab：免责声明逐字片段 + 新增药品按钮
    page.click('[data-testid="review-tab-drugdict"]')
    expect(page.get_by_text(DRUG_DISCLAIMER_SNIPPET).first).to_be_visible()
    expect(page.locator('[data-testid="drug-dict-save"]')).to_be_visible()

    # 相互作用规则 tab：覆盖率 chip（审校 X/Y · 高危 Z/W）+ 批量标记按钮
    page.click('[data-testid="review-tab-drugrules"]')
    expect(page.locator('[data-testid="drug-coverage"]')).to_contain_text("审校")
    expect(page.locator('[data-testid="drug-rule-review"]')).to_be_visible()


def test_vue_admin_admin_panels(page):
    """admin01：知识库 / 数据面板（审计流+留痕模式+日志+LLM 配置）/ 病例库 tab。"""
    _login(page, "admin01")

    # 知识库管理：向量总数统计 + 上传按钮
    _nav(page, "知识库管理")
    expect(page.locator('[data-testid="kb-stats"]')).to_contain_text("向量总数")
    expect(page.locator('[data-testid="kb-upload"]')).to_be_visible()

    # 数据面板：审计流（含导出 CSV）/ 留痕模式卡 / 应用日志 / 模型配置
    _nav(page, "数据面板")
    expect(page.locator('[data-testid="audit-panel"]')).to_contain_text("审计流")
    expect(page.locator('[data-testid="full-flag"]')).to_contain_text("留痕模式")
    expect(page.locator('[data-testid="log-panel"]')).to_be_visible()
    expect(page.locator('[data-testid="llm-panel"]')).to_contain_text("模型配置")

    # 整改轮 B 任务3：建号表单角色→科室下拉联动过滤（只读断言，不实际建号）
    # pharmacist 只见药剂科；qc 只见质控科/医务处；doctor 隐藏职能部门（药剂科/医务处/质控科/病案室）。
    # 注：<option> 在收起的 select 下被 Playwright 判为 hidden，故用 to_have_count/to_have_text
    # （读 textContent，不依赖可见性）断言。
    role_sel = page.locator('[data-testid="nu-role"]')
    dept_sel = page.locator('[data-testid="nu-dept"]')
    expect(dept_sel.locator("option")).to_have_count(12, timeout=10_000)  # 科室字典已加载（占位+11 临床科室）
    role_sel.select_option("pharmacist")
    expect(dept_sel.locator("option")).to_have_count(2)  # 占位 + 药剂科
    expect(dept_sel.locator("option").nth(1)).to_have_text("药剂科")
    role_sel.select_option("qc")
    expect(dept_sel.locator("option")).to_have_count(3)  # 占位 + 质控科/医务处
    qc_opts = [o.text_content() for o in dept_sel.locator("option").all()]
    assert "质控科" in qc_opts and "医务处" in qc_opts and "药剂科" not in qc_opts
    role_sel.select_option("doctor")
    doc_opts = [o.text_content() for o in dept_sel.locator("option").all()]
    assert doc_opts, "doctor 应有临床科室可选"
    for f in ("药剂科", "医务处", "质控科", "病案室"):
        assert f not in doc_opts, f"doctor 建号不应见职能部门：{f}"

    # 审核中心：admin 专属 tab（待处理/历史记录/病例库）——病例库 tab 按钮存在即断言（不点击移除）
    _nav(page, "审核中心")
    expect(page.locator('[data-testid="review-title"]')).to_have_text("审核中心 · 高风险双人核对")
    expect(page.locator('[data-testid="review-tab-casearchive"]')).to_be_visible()


def test_vue_admin_qc_case_archive_tab(page):
    """qc01：审核中心渲染病例库 tab（仅 qc/admin 可见；点开断言统计头）。"""
    _login(page, "qc01")
    _nav(page, "审核中心")
    expect(page.locator('[data-testid="review-tab-casearchive"]')).to_be_visible()
    page.click('[data-testid="review-tab-casearchive"]')
    expect(page.locator('[data-testid="case-archive"]')).to_contain_text("病例库（质控通过自动归档）")
    expect(page.locator('[data-testid="review-tab-drugdict"]')).to_have_count(0)  # 字典 tab 仅药剂科


def test_vue_admin_doctor_no_admin_nav(page):
    """doctor01：侧边导航无管理菜单（知识库管理/数据面板不出现）。"""
    _login(page, "doctor01")
    expect(page.locator('.rail a.nav:has-text("知识库管理")')).to_have_count(0)
    expect(page.locator('.rail a.nav:has-text("数据面板")')).to_have_count(0)
    expect(page.locator('.rail a.nav:has-text("审核中心")')).to_have_count(1)  # 医生仍有审核中心入口
