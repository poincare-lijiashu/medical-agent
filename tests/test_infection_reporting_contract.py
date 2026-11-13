"""传染病上报与漏报监测契约锁（§294 / 计划书 W1.9）。

锁住四条法定报告底线：
1. 诊断自动匹配法定目录，同就诊同病种只生成一张有效卡；
2. 截止时间 = 诊断时间 + 病种时限（甲类 2 小时、乙/丙类 24 小时）；
3. 状态机严格：提交必须写报告人，受理必须有回执号，已提交不可作废；
4. 流转与审计只增不改，逾期/迟报可量化。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CLINICAL_MAIN = REPO / "platform/clinical/src/main"
AUTH_MAIN = REPO / "platform/auth/src/main"

MIGRATION = CLINICAL_MAIN / "resources/db/migration/clinical/V72__infection_reporting.sql"
SERVICE = CLINICAL_MAIN / (
    "java/com/medassist/clinical/service/InfectionReportService.java"
)
REPO_JAVA = CLINICAL_MAIN / "java/com/medassist/clinical/repo/ClinicalRepository.java"
CONTROLLER = CLINICAL_MAIN / (
    "java/com/medassist/clinical/web/InfectionReportController.java"
)
MODEL = CLINICAL_MAIN / "java/com/medassist/clinical/domain/ClinicalModel.java"
AUTH_V103 = AUTH_MAIN / "resources/db/migration/auth/V103__infection_permissions.sql"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
VIEW = CONSOLE / "views/InfectionReportView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"
RUNTIME = REPO / "docs/AGENT-RUNTIME-20261105.md"
PLAN = REPO / "docs/COMMERCIAL-READINESS-100-PLAN.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_covers_catalog_cards_events_and_scan_state():
    sql = _read(MIGRATION)
    for table in ("infection_disease", "infection_report", "infection_report_event",
                  "infection_scan_state"):
        assert f"clinical_svc.{table}" in sql, f"缺少表 {table}"
    assert "ux_infection_report_active" in sql, "同就诊同病种只能一张有效卡"
    assert "report_hours > 0" in sql
    for disease in ("鼠疫", "霍乱", "肺结核", "流行性感冒"):
        assert disease in sql, f"缺少基线病种 {disease}"
    assert "guard_infection_event" in sql, "流转留痕必须只增不改"
    assert "不允许修改或删除" in sql


def test_service_enforces_deadline_and_state_machine():
    src = _read(SERVICE)
    assert "甲类 2 小时" in src and "24 小时" in src
    assert "reportDeadline" in src or "deadline(" in src
    assert "只有草稿或退回的报告卡可以提交" in src
    assert "已提交/已受理的报告卡不能作废" in src
    assert "报告人" in src and "疾控回执号" in src
    assert "match(catalog" in src, "必须有诊断自动匹配"
    assert "findActiveInfectionReport" in src, "自动生成必须幂等"

    repo = _read(REPO_JAVA)
    assert "AND version = :version" in repo, "状态流转必须 CAS"
    assert "status IN ('DRAFT', 'RETURNED')" in repo, "只有草稿/退回可提交"
    assert "status IN ('DRAFT', 'RETURNED')" in repo
    assert "infection_scan_state" in repo and "last_diagnosed_at" in repo

    model = _read(MODEL)
    assert "InfectionReport" in model and "InfectionSummary" in model
    assert "CLASS_A" in model


def test_controller_permissions_are_separated():
    controller = _read(CONTROLLER)
    for perm in ("clinical:infection:read", "clinical:infection:report",
                 "clinical:infection:manage"):
        assert perm in controller, f"缺少权限 {perm}"
    for path in ('"/detect"', '"/reports/{id}/submit"', '"/reports/{id}/accept"',
                 '"/reports/{id}/return"', '"/reports/{id}/void"'):
        assert path in controller, f"缺少接口 {path}"

    sql = _read(AUTH_V103)
    assert "('DOCTOR', 'clinical:infection:report')" in sql
    assert "('QC', 'clinical:infection:manage')" in sql
    assert "('REGISTRAR', 'clinical:infection" not in sql, "窗口不应获得上报权限"


def test_frontend_wires_infection_page():
    api = _read(API)
    for method in ("infectionApi", "detect", "returnCard", "voidCard"):
        assert method in api, f"前端缺少 {method}"

    types = _read(TYPES)
    for name in ("InfectionReport", "InfectionDisease", "InfectionSummary"):
        assert name in types, f"缺少类型 {name}"

    view = _read(VIEW)
    for text in ("传染病上报", "立即扫描", "已逾期", "疾控回执号", "病种目录"):
        assert text in view, f"页面缺少：{text}"
    assert "clinical:infection:manage" in view, "前端必须按权限隐藏管理动作"

    nav = _read(NAV)
    assert "'/infections'" in nav and "传染病上报" in nav
    router = _read(ROUTER)
    assert "InfectionReportView" in router


def test_docs_record_the_batch():
    runbook = _read(RUNBOOK)
    for text in ("传染病上报", "甲类", "漏报", "回执"):
        assert text in runbook, f"运维手册缺少：{text}"

    runtime = _read(RUNTIME)
    assert "## 294." in runtime and "传染病上报与漏报监测" in runtime

    plan = _read(PLAN)
    assert "§294" in plan
