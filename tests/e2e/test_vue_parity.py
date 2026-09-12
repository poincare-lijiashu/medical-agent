"""Vue3 迁移整改轮 A 对照矩阵冒烟（docs/archive/qa/vue_parity_matrix.md 轮 A 补齐项验收）。

覆盖轮 A 补齐的 4 项 ❌：
1. CaseView（doctor）：病例总结视图表单元素（病例框/附图/提交/免责条）——**不点提交，
   不触发任何 LLM 调用**（对照矩阵 C-3）；
2. OverviewView 重写：完整仪表盘（hero 欢迎语 / 5 指标卡 / 最近活动 / 快速进入），
   且不再出现「迁移轮1/过渡期」过时文案（对照矩阵 B 全部）；
3. 审核中心「其它科室会诊协助」tab（doctor/pharmacist 可见；admin/qc 无该 tab）
   + 收件箱容器渲染（对照矩阵 D-4/D-5）；
4. ArchView（admin）：架构·合规静态四卡（对照矩阵 F-8）。

整改轮 B 追加（test_parity_rx_reject_and_out_of_dict_flow）：
- 任务1 驳回闭环：API 前置创建真实 pending 处方（doctor01 单药阿司匹林）→ pharm01
  开药待审点「驳回」→ 弹条 → 填意见 → 确认 → 成功提示 + 卡片离开队列；
- 任务2 方案A：doctor UI 手动加字典外药「测试」→ 行内填理由 → 提交 200 →
  pharm01 待审卡见「含字典外药品·重点审核」红徽（两项共用一次 pharm01 会话省限流）。

说明：依赖运行中的服务 127.0.0.1:8001（healthz 不通则 skip）；未装 playwright 整文件
skip；演示口令 = seed（AUTH_DEMO_PASSWORD）Med@2026，与既有 e2e 文件同源。
doctor01 的三项断言合并为单函数顺序执行（同 test_vue_assist 风格），降低全量套件
连续执行时 doctor01 的登录/首刷请求密度（服务端 60 次/分钟滑动窗口限流，429 会红）。
"""
import json
import time
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


# ---- 整改轮 B：任务1 驳回闭环 + 任务2 字典外方案A（共用 pharm01 会话省限流） ----

def _api(method: str, path: str, token: str = "", body: dict | None = None) -> tuple[int, dict]:
    """e2e 内轻量 API 调用（前置造数/断言用，避免为一次 POST 走整套 UI 登录）。"""
    req = urllib.request.Request(BASE_URL + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=10) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def _purge_created_prescriptions(ids: list[str]) -> None:
    """F2 防再犯：teardown 自清理本测试创建的处方（按 id 直调域层删除函数）。
    PG 池初始化在**独立线程**执行——playwright sync（greenlet）上下文内 asyncio.run
    会静默失败（RuntimeWarning: coroutine 'init' was never awaited → 只删 JSON 漏删
    PG 真源，实测残留）；线程内与普通脚本等价。审计 jsonl 不动；失败仅告警，
    残留由 scripts/cleanup_test_data.py 按 [e2e] 标记兜底清。"""
    ids = [i for i in (ids or []) if i]
    if not ids:
        return
    import threading

    def _do() -> None:
        try:
            import asyncio

            from backend.core import pg_store
            from backend.core.prescriptions import delete_prescriptions
            pg_ready = asyncio.run(pg_store.init())
            try:
                removed = delete_prescriptions(ids)
            finally:
                if pg_ready:
                    asyncio.run(pg_store.close())
            print(f"[e2e cleanup] 已清理本测试创建的处方"
                  f"（PG 同步={'on' if pg_ready else 'off'}）：{removed}")
        except Exception as exc:  # noqa: BLE001 —— 自清理失败不掩盖测试结果
            print(f"[e2e cleanup] 清理失败（残留由 cleanup_test_data.py 兜底）："
                  f"{type(exc).__name__}: {exc}")

    th = threading.Thread(target=_do, name="e2e-rx-cleanup", daemon=True)
    th.start()
    th.join(timeout=30)


def test_parity_rx_reject_and_out_of_dict_flow(page):
    """整改轮 B 任务1+2 e2e 闭环（单会话合并，控 60/min 限流密度）：
    ①API 前置：doctor01 登录并 POST /prescriptions 单药阿司匹林（真实 pending 处方，2 次请求）；
    ②doctor01 UI：开药工作台手动加字典外药「测试」→ 点两次「添加」（外典确认流）→
      行内填 ≥5 字理由 → 提交 → 成功提示（方案A：外典药允许提交）；
    ③pharm01 UI：开药待审——外典处方卡见「含字典外药品·重点审核」红徽（任务2）；
      阿司匹林处方点「驳回」→ 弹条出现 → 填意见 → 确认 → 成功提示 + 卡片离开队列（任务1）。
    F2 防再犯：本测试创建的处方统一带 [e2e] 标记（case_text/drugs note），teardown 自清理。"""
    created_ids: list[str] = []
    try:
        _parity_rx_reject_and_out_of_dict_body(page, created_ids)
    finally:
        _purge_created_prescriptions(created_ids)  # F2：teardown 删除本测试创建的处方


def _parity_rx_reject_and_out_of_dict_body(page, created_ids: list[str]) -> None:
    # ---- ① API 前置：真实 pending 处方（阿司匹林为字典内单药；case_text/note 带 [e2e] 标记） ----
    st, d = _api("POST", "/api/v1/auth/login", body={"username": "doctor01", "password": DEMO_PASSWORD})
    assert st == 200, d
    doc_tok = d["access_token"]
    st, rx = _api("POST", "/api/v1/medical/prescriptions", doc_tok,
                  {"case_text": "[e2e] 患者女，52 岁，体检发现血脂偏高，低密度脂蛋白升高，评估后拟抗血小板一级预防。",
                   "drugs": [{"name": "阿司匹林", "dose": "100mg", "freq": "qd", "note": "[e2e]"}],
                   "contraindication_reason": ""})
    assert st == 200, rx
    aspirin_id = rx["id"]
    created_ids.append(aspirin_id)
    assert rx["status"] == "pending_pharm"

    # ---- ② doctor01 UI：字典外药「测试」+ 理由 → 提交 200 ----
    _login(page, "doctor01")
    _nav(page, "开药工作台")
    page.locator('[data-testid="rx-case"]').fill(
        "[e2e] 患者男，45 岁，膝关节术后疼痛，既往用药过敏史不详，需临时镇痛处理观察疗效。")
    manual_name = page.get_by_placeholder("手动输入药名")
    manual_name.fill("测试")
    page.locator('[data-testid="rx-add-manual"]').click()
    try:  # 字典外药首次点击只弹确认条，需再次点击才入药单（字典内药一次即入）
        page.locator('[data-testid="rx-ood-reason"]').wait_for(state="visible", timeout=4_000)
    except Exception:  # noqa: BLE001
        page.locator('[data-testid="rx-add-manual"]').click()
        page.locator('[data-testid="rx-ood-reason"]').wait_for(state="visible")
    page.locator('[data-testid="rx-ood-reason"]').fill("院内制剂目录外镇痛药，临床急需，已告知患者风险 [e2e]")
    page.locator('[data-testid="rx-submit"]').click()
    # 成功判定用状态（提交成功 → 自动切「我的处方」出 data-rxid 卡片），不依赖瞬时 toast；
    # 若撞上 60/min 限流（429，表单状态保留），等待窗口滑动后重试点击。
    submitted = False
    for _ in range(5):
        try:
            page.wait_for_selector('[data-rxid]', timeout=8_000)
            submitted = True
            break
        except Exception:  # noqa: BLE001 —— 未出卡片（典型：429 限流）→ 重试提交
            try:
                page.locator('[data-testid="rx-submit"]').click(timeout=2_000)
            except Exception:  # noqa: BLE001
                pass
    assert submitted, "提交开药未成功（重试耗尽）"
    ood_id = page.locator('[data-rxid]').first.get_attribute("data-rxid")  # 列表新→旧，首卡即新处方
    assert ood_id and ood_id != aspirin_id
    created_ids.append(ood_id)

    # ---- ③ pharm01 UI：红徽断言（任务2）+ 驳回闭环（任务1） ----
    _login(page, "pharm01")
    _nav(page, "审核中心")
    # 全量套件连跑时四个 e2e 文件共享服务端 60/min 的 IP 限流窗口，首刷可能 429 出空
    # 列表——用审核中心头部「刷新」按钮（重发 load()）轮询重试，窗口滑动后必恢复。
    ood_card = page.locator(f'[data-rxid="{ood_id}"]')
    for _ in range(8):
        try:
            expect(ood_card).to_be_visible(timeout=7_000)
            break
        except AssertionError:
            page.get_by_role("button", name="刷新").click()
    else:  # pragma: no cover —— 限流窗口极端拥塞才走到（重试已耗尽）
        expect(ood_card).to_be_visible(timeout=7_000)
    expect(ood_card.locator('[data-testid="rx-ood-badge"]')).to_contain_text("含字典外药品·重点审核")
    expect(ood_card.get_by_text("字典外", exact=True).first).to_be_visible()  # 外典药行红标

    # 任务1 闭环：驳回 → 弹条 → 意见 → 确认 → 成功提示 + 卡片离开队列
    aspirin_card = page.locator(f'[data-rxid="{aspirin_id}"]')
    expect(aspirin_card).to_be_visible()
    aspirin_card.get_by_role("button", name="驳回").click()
    dialog = page.locator('.el-dialog:has-text("驳回处方 · 填写审核意见")')
    expect(dialog).to_be_visible()  # 弹条出现（任务1 复现点：功能正常即锁死闭环）
    dialog.get_by_label("驳回意见").fill("单药适应症依据不足，请补充病程记录后重新提交")
    dialog.get_by_role("button", name="确认驳回").click()
    expect(page.locator(".el-message").first).to_contain_text("已驳回")
    expect(aspirin_card).to_have_count(0)  # 驳回成功后列表刷新，卡片离开队列
    # 弹条已关闭，且时间线留痕（历史行可见审核状态）
    expect(dialog).not_to_be_visible()
    deadline = time.time() + 10
    while time.time() < deadline:  # 「我的历史」合并渲染处方审核记录（rejected 落 rxHistoryItems）
        try:
            page.click('[data-testid="review-tab-history"]')
            if page.get_by_text(aspirin_id).count():
                break
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5)
    expect(page.get_by_text(aspirin_id).first).to_be_visible()


# ---- F1：qc01 审核中心待处理列表可见 + 计数头（与角标对账） ----

def test_qc_pending_list_visible_with_count_header(page):
    """F1 验收：qc01 待处理 tab 列表项数 > 0，且「共 N 条待核对」计数头与服务端真值
    （GET /review/pending，qc 已过滤 drug）一致——角标/计数头/列表三方对账可见。
    修复前：429/加载失败时 v-if/v-else 结构把列表区整体顶掉 =「假空态」，且无计数头可对账。"""
    # API 真值（qc 视角 pending 数，即前端 pendItems 源）
    st, d = _api("POST", "/api/v1/auth/login", body={"username": "qc01", "password": DEMO_PASSWORD})
    assert st == 200, d
    st, pend = _api("GET", "/api/v1/medical/review/pending", d["access_token"])
    assert st == 200, pend
    server_n = len(pend.get("pending") or [])
    assert server_n > 0, "前置期望：演示库存在待核对项（当前 51 条）"

    _login(page, "qc01")
    _nav(page, "审核中心")
    # 计数头：与 API 真值一致（qc 视角「待核对」）
    expect(page.locator('[data-testid="pending-count"]')).to_contain_text(f"共 {server_n} 条待核对")
    # 列表可见（修复前加载失败/渲染异常时为空态或整体消失）
    expect(page.locator(".rv-table tbody tr").first).to_be_visible()
    # 角标与计数头对账：侧边栏审核中心徽标数字 = server_n（qc 口径非 drug）
    badge = page.locator('.rail a.nav:has-text("审核中心") .nav-tag')
    if server_n > 0:
        expect(badge).to_have_text(str(server_n))
