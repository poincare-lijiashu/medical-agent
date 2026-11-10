"""控制台主数据加载失败态回归锁。

老页面在主数据加载失败时只 `toast.error(...)` 再把列表清空，用户看到的是
"空数据"（没有患者 / 没有床位 / 区间内没有记录），会误判为业务事实。
本文件锁死以下约束：

1. 主数据加载失败必须渲染持久的 `LoadError`（role=alert + 重试），toast 只能是补充；
2. 失败文案必须写进 `loadErr`，且下次加载开始时先清空（否则重试成功仍挂着旧错误）；
3. `LoadError` 组件本身保留 `role="alert"` 与 `retry` 事件，保证"失败"与"空态"可区分。

注意：提交 / 审批 / 删除 / 打印等**操作失败**仍走 toast，不属于主数据加载，
不在本文件的约束范围内。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VIEWS = REPO / "frontend-console" / "src/views"
LOAD_ERROR = REPO / "frontend-console" / "src/components/ui/LoadError.vue"

# 本批要求"主数据加载失败 = 持久错误态"的页面
PAGES = (
    "InpatientView.vue",
    "OrdersWorkbenchView.vue",
    "NursingView.vue",
    "ConsultationView.vue",
    "HomepageQcView.vue",
    "PreopVisitView.vue",
    "NarcoticRegisterView.vue",
    "SurgeryChargesView.vue",
    "KnowledgeView.vue",
    "HisWorkbenchView.vue",
    "InsuranceOpsView.vue",
    "PharmacyConfigView.vue",
    "AiOverviewView.vue",
)

# 主数据加载自身的 catch 里必须写 loadErr。
# InsuranceOpsView 是 tab 容器：目录 / 报盘 / 实时结算面板各自渲染 LoadError，
# 只有 DRG/DIP 面板把失败上抛给页面（onPanelError），因此单独校验。
DIRECT_LOAD_PAGES = tuple(p for p in PAGES if p != "InsuranceOpsView.vue")

CATCH_ASSIGN = re.compile(r"catch\s*\(e\)\s*\{[^{}]*loadErr\.value\s*=", re.S)


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_pages_import_and_render_load_error():
    for name in PAGES:
        src = _read(VIEWS / name)
        assert "import LoadError from '@/components/ui/LoadError.vue'" in src, f"{name} 未引入 LoadError"
        assert "<LoadError" in src, f"{name} 模板缺少 <LoadError>（失败会被渲染成空数据）"
        assert "@retry=" in src, f"{name} 的 LoadError 没有绑定重试入口"


def test_pages_assign_load_err_inside_catch():
    for name in DIRECT_LOAD_PAGES:
        src = _read(VIEWS / name)
        assert re.search(r"const loadErr = ref\(''\)", src), f"{name} 缺少 loadErr"
        assert "loadErr.value =" in src, f"{name} 的 loadErr 从未赋值，失败态形同虚设"
        assert CATCH_ASSIGN.search(src), f"{name} 的主数据加载 catch 未写入 loadErr（只 toast 不算）"
        assert "loadErr.value = ''" in src, f"{name} 重新加载时未清空上一轮的失败文案"


def test_insurance_ops_view_aggregates_panel_error():
    src = _read(VIEWS / "InsuranceOpsView.vue")
    assert re.search(r"const loadErr = ref\(''\)", src)
    assert "loadErr.value = message" in src, "面板加载失败必须上报为页面错误态"
    assert "loadErr.value = ''" in src, "重试前必须清空错误"
    assert "@load-error=" in src, "页面未接收面板加载失败事件"
    panel = _read(REPO / "frontend-console" / "src/components/domain/InsuranceGroupingPanel.vue")
    assert "emit('load-error', errorText(e))" in panel, "DRG/DIP 面板加载失败未上报"


def test_no_page_falls_back_to_toast_only():
    """反向锁：有 LoadError 的页面必须同时有 loadErr 赋值，避免"改一半"。"""
    for name in PAGES:
        src = _read(VIEWS / name)
        assert "loadErr.value =" in src, f"{name} 只有 toast/空列表，没有持久失败态"


def test_load_error_component_keeps_alert_and_retry():
    src = _read(LOAD_ERROR)
    assert 'role="alert"' in src, "LoadError 必须是 role=alert（读屏可播报）"
    assert "defineEmits<{ retry: [] }>()" in src, "LoadError 必须保留 retry 事件"
    assert "$emit('retry')" in src
    assert ':disabled="retrying"' in src, "重试期间必须禁用按钮，避免连点"
