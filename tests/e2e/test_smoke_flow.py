"""E2E 浏览器冒烟（评测行动项 2，路径 A）：Playwright 真浏览器全链路。

链路：doctor01 登录 → 开药工作台 → 粘贴病例 → 字典搜「阿司匹林」加入药单 →
提交开药 → 我的处方出现待审卡 → 登出 → pharmacist 登录 → 审核中心开药待审 →
通过签发 → 断言成功提示且处方离开待审队列。

说明：
- 故意跳过「生成开药建议」（LLM 真调用慢且花钱），走手动加药等价路径。
- 环境要求：pip install pytest-playwright && playwright install chromium；
  未安装 playwright 时整文件自动 skip（不污染 CI）。
- 依赖运行中的服务 127.0.0.1:8001（healthz 不通则 skip）；测试会在该服务上
  真实创建一条演示处方并由药师签发（演示数据，无高危组合）。
"""
import urllib.request

import pytest

pytest.importorskip("playwright")  # 环境没装 playwright → 自动 skip，保证 CI 全绿

from playwright.sync_api import expect, sync_playwright  # noqa: E402

BASE_URL = "http://127.0.0.1:8001"  # 与运行中的后端服务一致（前端单文件由后端托管）
CASE_TEXT = (
    "患者女，45 岁，因膝关节轻度疼痛就诊，既往无药物过敏史，肝肾功能正常，"
    "无消化道溃疡病史，拟行短期口服镇痛治疗，需评估用药方案。"
)


def _service_up() -> bool:
    """服务可达性预检：healthz 非 200 → skip（浏览器冒烟是增强项，不阻塞单测）。"""
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def page():
    """模块级 headless Chromium 页面（自带 sync_playwright 生命周期管理）。"""
    if not _service_up():
        pytest.skip(f"服务不可达：{BASE_URL}（healthz 未通过），跳过浏览器冒烟")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(base_url=BASE_URL, locale="zh-CN")
        pg = ctx.new_page()
        pg.set_default_timeout(15_000)
        yield pg
        ctx.close()
        browser.close()


def _login(page, username: str) -> None:
    """登录页填账号密码并提交，等待应用主界面（侧边导航）出现。"""
    page.goto("/")
    page.fill("#lu", username)
    page.fill("#lp", "Med@2026")  # seed 演示口令（backend/core/auth.py，AUTH_DEMO_PASSWORD）
    page.click("button.lbtn")
    # 侧边导航项是 button.nav[data-k=...]（rail 容器才是 <nav> 标签）
    page.wait_for_selector('button.nav[data-k="rx"]', state="visible")


def _search_and_add_drug(page, name: str) -> None:
    """字典搜索加药：字典缓存由 loadRx 异步拉取，oninput 触发时若缓存未就绪会显示
    「无匹配」，故带一次重试重输（重触发前端过滤），保证结果列表渲染后再点「加入药单」。"""
    box = page.locator("#rxDictSearch")
    target = page.locator(f'#rxDictList button[data-name="{name}"]')
    for _ in range(10):
        box.fill(name)
        if target.count():
            break
        page.wait_for_timeout(500)
    expect(target).to_be_visible()
    target.first.click()  # 首个命中即规范名「阿司匹林」
    expect(page.locator("#toast")).to_have_text(f"已加入药单：{name}")


def test_smoke_doctor_prescribe_then_pharmacist_approve(page):
    """全链路：医生手动加药开单提交 → 药师审核中心签发通过（跳过 LLM 建议）。"""
    # ---- 医生端：登录 → 开药工作台 ----
    _login(page, "doctor01")
    page.click('button.nav[data-k="rx"]')
    page.wait_for_selector("#rxCase", state="visible")

    # ---- 病例摘要（≥20 字）+ 字典手动加药（跳过 AI 建议）----
    page.fill("#rxCase", CASE_TEXT)
    _search_and_add_drug(page, "阿司匹林")
    expect(page.locator("#rxSelected")).to_contain_text("阿司匹林")
    expect(page.locator("#rxSelected")).to_contain_text("字典内")  # 单药、字典内：无高危，无需联用理由

    # ---- 提交开药 → 成功提示 + 自动跳「我的处方」出现待审卡 ----
    page.click("#rxSubmitBtn")
    expect(page.locator("#toast")).to_have_text("处方已提交，等待药剂科审核")
    mine_card = page.locator("#rxMineList .card[data-rxid]").first
    expect(mine_card).to_be_visible()
    rid = mine_card.get_attribute("data-rxid")
    assert rid and rid.startswith("rx-")
    expect(mine_card).to_contain_text("待药剂科审核")  # RX_STATUS.pending_pharm 徽章文案

    # ---- 登出 → 药师登录 ----
    page.click("#lo")
    page.wait_for_selector("#login", state="visible")
    _login(page, "pharm01")

    # ---- 审核中心 → 开药待审找到该处方卡 → 通过签发 ----
    page.click('button.nav[data-k="review"]')
    card = page.locator(f'.card[data-rxid="{rid}"]')
    expect(card).to_be_visible()  # 开药待审（处方队列）渲染该单
    expect(card).to_contain_text("阿司匹林")
    card.locator('button', has_text="通过").first.click()  # rxReview approve（驳回才需填意见）
    expect(page.locator("#toast")).to_have_text("处方已签发通过")
    expect(card).not_to_be_visible()  # 签发后 loadReview 刷新，处方离开待审队列
