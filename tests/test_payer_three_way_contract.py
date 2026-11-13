"""三方对账与多支付方分摊契约锁（§292 / 计划书 W2.5+W2.6）。

锁住四条财务底线：
1. 三方勾稽：临床实收、本地清单、省局回传三者可定位；读不到实收必须失败而不是当 0；
2. 分摊守恒：各方合计 = 清单总额，统筹分摊 = 清单统筹支付；
3. 台账只增不改：退费追加 REFUND 并指向原分摊，数据库层禁止改删；
4. 权限与范围：窗口不可见分摊，临床内部接口只走共享令牌。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
INSURANCE_MAIN = REPO / "platform/insurance/src/main"
CLINICAL_MAIN = REPO / "platform/clinical/src/main"
AUTH_MAIN = REPO / "platform/auth/src/main"

V26 = INSURANCE_MAIN / (
    "resources/db/migration/insurance/V26__payer_allocation_and_three_way_recon.sql"
)
AUTH_V101 = AUTH_MAIN / "resources/db/migration/auth/V102__insurance_payer_permissions.sql"
PAYER_SERVICE = INSURANCE_MAIN / (
    "java/com/medassist/insurance/service/PayerAllocationService.java"
)
PAYER_CONTROLLER = INSURANCE_MAIN / "java/com/medassist/insurance/web/PayerController.java"
INSURANCE_SERVICE = INSURANCE_MAIN / (
    "java/com/medassist/insurance/service/InsuranceService.java"
)
RECON_SERVICE = INSURANCE_MAIN / (
    "java/com/medassist/insurance/service/SettlementReconService.java"
)
CLINICAL_CLIENT = INSURANCE_MAIN / "java/com/medassist/insurance/client/ClinicalClient.java"
HTTP_CLINICAL = INSURANCE_MAIN / (
    "java/com/medassist/insurance/client/HttpClinicalClient.java"
)
CLINICAL_CONTROLLER = CLINICAL_MAIN / (
    "java/com/medassist/clinical/web/InternalFinanceController.java"
)
CLINICAL_REPO = CLINICAL_MAIN / "java/com/medassist/clinical/repo/ClinicalRepository.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
PANEL = CONSOLE / "components/domain/InsurancePayerPanel.vue"
RECON_PANEL = CONSOLE / "components/domain/InsuranceReconPanel.vue"
OPS_VIEW = CONSOLE / "views/InsuranceOpsView.vue"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"
RUNTIME = REPO / "docs/AGENT-RUNTIME-20261105.md"
PLAN = REPO / "docs/COMMERCIAL-READINESS-100-PLAN.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_has_payers_ledger_and_three_way_columns():
    sql = _read(V26)
    assert "insurance_svc.payer_config" in sql
    assert "insurance_svc.settlement_allocation" in sql
    assert "ux_allocation_charge" in sql, "同一清单同一支付方只允许一条应收分摊"
    for payer_type in ("BASIC", "SELF", "COMMERCIAL", "ASSISTANCE"):
        assert payer_type in sql
    for category in ("CLINIC_UNDER_COLLECTED", "CLINIC_OVER_COLLECTED"):
        assert category in sql, f"缺少三方差异类别 {category}"
    assert "clinic_total" in sql and "clinic_refund_total" in sql
    # 台账只增不改：数据库层禁止 UPDATE/DELETE
    assert "guard_settlement_allocation" in sql
    assert "不允许修改或删除" in sql


def test_allocation_keeps_financial_semantics():
    src = _read(PAYER_SERVICE)
    assert "分摊合计" in src and "不一致" in src
    assert "必须等于清单统筹支付" in src
    assert "已完成分摊" in src and "退费" in src
    assert "超过可退余额" in src
    assert "不允许修改或删除" not in src  # 由数据库触发器兜底
    assert "SettlementAllocation.REFUND" in src and "original.id()" in src

    insurance = _read(INSURANCE_SERVICE)
    assert "未冲销的支付方分摊" in insurance, "清单作废前必须冲销分摊"


def test_perm_uses_shared_token_and_fails_closed():
    client = _read(CLINICAL_CLIENT)
    assert "Collections collections(LocalDate from, LocalDate to)" in client
    assert "读不到实收" in client

    http = _read(HTTP_CLINICAL)
    assert "/internal/finance/visits/collections" in http
    assert "临床收费汇总暂不可用" in http

    controller = _read(CLINICAL_CONTROLLER)
    assert '"/visits/collections"' in controller
    assert "X-Internal-Token" in controller
    assert "内部接口不可用" in controller

    repo = _read(CLINICAL_REPO)
    assert "listVisitCollections" in repo
    assert "AT TIME ZONE :zone" in repo, "业务日期必须按医院时区换算"


def test_recon_extends_to_three_way():
    src = _read(RECON_SERVICE)
    assert "loadCollections" in src
    assert "CLINIC_UNDER_COLLECTED" in src and "CLINIC_OVER_COLLECTED" in src
    assert "临床实收" in src
    assert "不计入省局差额" in src
    assert "无法完成三方对账" in src


def test_permissions_separate_window_from_finance():
    sql = _read(AUTH_V101)
    assert "'insurance:payer:read'" in sql and "'insurance:payer:manage'" in sql
    assert "('INSURANCE_OFFICER', 'insurance:payer:manage')" in sql
    assert "('OPS_MANAGER', 'insurance:payer:read')" in sql
    assert "('REGISTRAR', 'insurance:payer" not in sql, "窗口角色不应获得分摊权限"

    controller = _read(PAYER_CONTROLLER)
    assert '"/payers"' in controller
    assert '"/allocations/bills"' in controller
    assert '"/allocations/{id}/refund"' in controller
    assert "insurance:payer:read" in controller and "insurance:payer:manage" in controller


def test_frontend_wires_payer_panel_and_three_way_columns():
    api = _read(API)
    for method in ("payers", "createPayer", "togglePayer", "allocationBills",
                   "allocationView", "allocate", "refundAllocation"):
        assert method in api, f"前端缺少 {method}"

    types = _read(TYPES)
    assert "CLINIC_UNDER_COLLECTED" in types and "CLINIC_OVER_COLLECTED" in types
    assert "InsuranceAllocationView" in types and "clinicMismatchCount" in types

    panel = _read(PANEL)
    assert "分摊合计" not in panel  # 规则在后端与提示文案中，前端不重复实现
    assert "统筹" in panel and "退费" in panel and "台账只增不改" in panel
    assert "insurance:payer:manage" in panel, "前端必须按 manage 权限隐藏操作"

    recon = _read(RECON_PANEL)
    assert "临床未实收" in recon and "临床实收" in recon
    assert "CLINIC_UNDER_COLLECTED" in recon

    view = _read(OPS_VIEW)
    assert "InsurancePayerPanel" in view and "支付方分摊" in view


def test_docs_record_the_batch():
    runbook = _read(RUNBOOK)
    for text in ("三方对账", "支付方分摊", "insurance:payer:manage",
                 "/internal/finance/visits/collections"):
        assert text in runbook, f"运维手册缺少：{text}"

    runtime = _read(RUNTIME)
    assert "## 292." in runtime and "三方对账与多支付方分摊" in runtime

    plan = _read(PLAN)
    assert "§292" in plan
