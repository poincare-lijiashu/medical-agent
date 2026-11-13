"""医保日结/月结对账契约锁（§291 / 计划书 W2.5）。

锁住四条财务底线：
1. 口径可审计：期间按 service_date、只对真差异建单、未回传金额不伪造差异；
2. 重算幂等：人工结论不被覆盖，重算冲突显式失败；
3. 关闭即结论：有未处理差异不能关闭，关闭后数据库层禁止修改；
4. 权限与范围：窗口不可 manage，院区受限账号不能做全院对账。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
INSURANCE_MAIN = REPO / "platform/insurance/src/main"
AUTH_MAIN = REPO / "platform/auth/src/main"

MIGRATION = INSURANCE_MAIN / "resources/db/migration/insurance/V25__settlement_recon.sql"
SERVICE = INSURANCE_MAIN / ("java/com/medassist/insurance/service/SettlementReconService.java")
REPO_JAVA = INSURANCE_MAIN / "java/com/medassist/insurance/repo/InsuranceRepository.java"
CONTROLLER = INSURANCE_MAIN / "java/com/medassist/insurance/web/ReconController.java"
METRICS = INSURANCE_MAIN / "java/com/medassist/insurance/config/InsuranceMetrics.java"

AUTH_V100 = AUTH_MAIN / "resources/db/migration/auth/V101__insurance_recon_permissions.sql"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
PANEL = CONSOLE / "components/domain/InsuranceReconPanel.vue"
OPS_VIEW = CONSOLE / "views/InsuranceOpsView.vue"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_has_batches_diffs_and_close_guard():
    sql = _read(MIGRATION)
    for table in ("settlement_recon_batch", "settlement_recon_diff"):
        assert f"insurance_svc.{table}" in sql, f"缺少表 {table}"
    assert "ux_recon_period" in sql, "同期间必须唯一（重算幂等）"
    assert "ux_recon_diff" in sql, "同批次同清单同类别必须唯一"
    for category in ("NOT_SETTLED", "SETTLE_FAILED", "AMOUNT_MISMATCH", "VOIDED_AFTER_SETTLE"):
        assert category in sql, f"缺少差异类别 {category}"
    # 关闭即结论：数据库层禁删、禁改
    assert "guard_recon_batch" in sql
    assert "不允许删除" in sql and "已关闭，不允许修改" in sql


def test_service_keeps_financial_semantics():
    src = _read(SERVICE)
    # 期间与范围
    assert "对账批次必须使用全院视角" in src
    assert "月结对账必须覆盖完整自然月" in src
    assert "不能对未来期间做对账" in src
    # 只对真差异建单；未回传金额不伪造
    assert "unverified" in src and "counterpartyAmounts" in src
    # 重算：先关 OPEN，再重开仍存在的；人工结论不覆盖
    assert "autoCloseAllOpenDiffs" in src
    assert "reopenAutoClosedReconDiff" in src
    assert "RESOLVED" in src and "IGNORED" in src
    # 关闭前置条件与 CAS
    assert "仍有 " in src and "条未处理差异，不能关闭" in src
    assert "updateReconBatch" in src


def test_repository_only_updates_open_and_cas():
    repo = _read(REPO_JAVA)
    assert "ON CONFLICT (batch_id, bill_id, category) DO NOTHING" in repo, (
        "重复差异不能覆盖人工处理状态"
    )
    assert "AND status = 'OPEN'" in repo
    assert "AND status = 'COMPUTED' AND version = :version" in repo, "重算必须 CAS"
    assert "AND open_diff_count = 0" in repo, "关闭必须在 SQL 层再次确认无未处理差异"


def test_permissions_are_separated_from_window_role():
    sql = _read(AUTH_V100)
    assert "'insurance:recon:read'" in sql and "'insurance:recon:manage'" in sql
    assert "('INSURANCE_OFFICER', 'insurance:recon:manage')" in sql
    assert "('OPS_MANAGER', 'insurance:recon:read')" in sql
    assert "('REGISTRAR', 'insurance:recon" not in sql, "窗口角色不应获得对账权限"

    controller = _read(CONTROLLER)
    assert '"/batches"' in controller and '"/batches/{id}/close"' in controller
    assert '"/diffs/{id}/resolve"' in controller
    assert "insurance:recon:read" in controller and "insurance:recon:manage" in controller


def test_metrics_and_console_are_wired():
    metrics = _read(METRICS)
    assert "medassist.insurance.recon_action" in metrics
    assert "medassist.insurance.recon_open_diffs" in metrics

    api = _read(API)
    for method in ("reconBatches", "computeRecon", "reconDetail", "closeRecon", "resolveReconDiff"):
        assert method in api, f"前端缺少 {method}"
    assert "InsuranceReconBatch" in _read(TYPES)
    panel = _read(PANEL)
    assert "未结算" in panel and "金额不一致" in panel and "作废后已结算" in panel
    assert "insurance:recon:manage" in panel, "前端必须按 manage 权限隐藏操作按钮"
    view = _read(OPS_VIEW)
    assert "InsuranceReconPanel" in view and "结算对账" in view


def test_runbook_documents_reconciliation():
    doc = _read(RUNBOOK)
    for text in ("医保日结/月结对账", "service_date", "不可验证", "insurance:recon:manage"):
        assert text in doc, f"运维手册缺少：{text}"
