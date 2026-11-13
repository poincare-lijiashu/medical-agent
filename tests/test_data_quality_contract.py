"""数据质量与指标稽核（第 299 批 / 计划书 G4）。

锁死的约束：

1. **探针只读**：只允许单条 SELECT/WITH；禁止写入、DDL、系统目录、分号与注释；5 秒超时。
2. **执行留痕**：每次执行落运行记录（观测值/通过与否/错误/耗时），规则修改不回溯历史。
3. **失败自动开单**：同一规则只保留一个未闭环问题，重复失败累加次数，不刷屏。
4. **闭环有证据**：解决必须写解决方案；豁免必须写原因且需要数据治理负责人权限（mdm:approve）。
5. **前端只做展示与合法动作**：不 v-html、不直连 fetch、不前端自报操作人。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SQL = REPO / "platform/masterdata/src/main/resources/db/migration/masterdata/V3__data_quality.sql"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V107__data_quality_permissions.sql"
SERVICE = REPO / "platform/masterdata/src/main/java/com/medassist/masterdata/service/DataQualityService.java"
REPO_JAVA = REPO / "platform/masterdata/src/main/java/com/medassist/masterdata/repo/DataQualityRepository.java"
CONTROLLER = REPO / "platform/masterdata/src/main/java/com/medassist/masterdata/web/DataQualityController.java"
SCHEDULER = REPO / "platform/masterdata/src/main/java/com/medassist/masterdata/service/DataQualityScheduler.java"
ERROR_CODE = REPO / "platform/common/src/main/java/com/medassist/common/api/ErrorCode.java"
HANDLER = REPO / "platform/masterdata/src/main/java/com/medassist/masterdata/web/MasterDataExceptionHandler.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/quality.ts"
RULE_PANEL = CONSOLE / "components/domain/quality/QualityRulePanel.vue"
RUN_PANEL = CONSOLE / "components/domain/quality/QualityRunPanel.vue"
ISSUE_PANEL = CONSOLE / "components/domain/quality/QualityIssuePanel.vue"
VIEW = CONSOLE / "views/DataQualityView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_schema_and_seed_rules():
    sql = _read(SQL)
    for table in (
        "mdm_svc.md_dq_rule",
        "mdm_svc.md_dq_run",
        "mdm_svc.md_dq_issue",
    ):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in sql, f"缺少表 {table}"
    assert "DQ-MDM-MAPPING" in sql and "DQ-METRIC-PUBLISH" in sql
    assert "ux_md_dq_issue_open" in sql, "同一规则只允许一个未闭环问题"
    assert "status IN ('OPEN', 'ACKNOWLEDGED')" in sql
    assert "DQ_ISSUE_RESOLVE" in sql and "DQ_ISSUE_WAIVE" in sql


def test_service_sql_guard_and_issue_flow():
    src = _read(SERVICE)
    assert "FORBIDDEN" in src and "assertSafeSql" in src
    assert "insert|update|delete|drop|alter|create|truncate" in src
    assert "information_schema" in src and "pg_catalog" in src
    assert "text.contains(\";\")" in src
    assert "MDM_DQ_SQL_UNSAFE" in src
    assert "openOrTouchIssue" in src
    assert "occurrenceCount() + 1" in src
    assert "MDM_DQ_ISSUE_RESOLUTION_REQUIRED" in src
    assert "MDM_DQ_ISSUE_WAIVER_REASON_REQUIRED" in src
    assert "\"BETWEEN\"" in src

    repo = _read(REPO_JAVA)
    assert "setQueryTimeout(5)" in repo
    assert "SELECT * FROM (\" + sql + \") dq_probe" in repo


def test_controller_permissions():
    src = _read(CONTROLLER)
    assert "mdm:quality:read" in src and "mdm:quality:run" in src and "mdm:quality:manage" in src
    for path in (
        '"/dashboard"',
        '"/rules"',
        '"/rules/{id}/run"',
        '"/rules/run-all"',
        '"/runs"',
        '"/issues"',
        '"/issues/{id}/resolve"',
        '"/issues/{id}/waive"',
    ):
        assert path in src, f"缺少接口 {path}"
    waive = src.split('"/issues/{id}/waive"', 1)[1].split("Permissions.require", 1)[1]
    assert "APPROVE" in waive.split(";", 1)[0]
    assert "mdm:approve" in src


def test_error_codes_and_handler():
    src = _read(ERROR_CODE)
    for code in (
        "MDM_DQ_RULE_NOT_FOUND",
        "MDM_DQ_SQL_UNSAFE",
        "MDM_DQ_SQL_FAILED",
        "MDM_DQ_ISSUE_RESOLUTION_REQUIRED",
        "MDM_DQ_ISSUE_WAIVER_REASON_REQUIRED",
    ):
        assert code in src, f"缺少错误码 {code}"
    handler = _read(HANDLER)
    assert "MDM_DQ_SQL_UNSAFE" in handler and "MDM_DQ_RULE_NOT_FOUND" in handler


def test_auth_permissions_and_roles():
    src = _read(AUTH_SQL)
    for perm in ("mdm:quality:read", "mdm:quality:run", "mdm:quality:manage"):
        assert f"('{perm}'" in src, f"缺少权限 {perm}"
    assert "('DATA_STEWARD', 'mdm:quality:manage')" in src
    assert "('DATA_GOVERNOR', 'mdm:quality:run')" in src
    assert "('DATA_GOVERNOR', 'mdm:quality:manage')" not in src
    assert "('QC', 'mdm:quality:run')" in src


def test_scheduler_is_guarded():
    src = _read(SCHEDULER)
    assert "ConditionalOnProperty" in src
    assert "dq-scan-enabled" in src
    assert "runAll(\"system:dq\")" in src
    assert "catch (Exception" in src


def test_frontend_wiring():
    api = _read(API)
    for path in (
        "'/mdm/quality/dashboard'",
        "'/mdm/quality/rules'",
        "'/mdm/quality/runs'",
        "'/mdm/quality/issues'",
    ):
        assert path in api, f"缺少前端端点 {path}"
    assert "runAll" in api and "waive" in api
    assert "fetch(" not in api

    rule_panel = _read(RULE_PANEL)
    assert "mdm:quality:manage" in rule_panel and "mdm:quality:run" in rule_panel
    assert "只允许 SELECT/WITH" in rule_panel
    assert "v-html" not in rule_panel and "fetch(" not in rule_panel
    assert "localStorage" not in rule_panel

    issue_panel = _read(ISSUE_PANEL)
    assert "mdm:approve" in issue_panel and "mdm:quality:manage" in issue_panel
    assert "解决方案" in issue_panel and "豁免原因" in issue_panel

    assert "观测值" in _read(RUN_PANEL)

    view = _read(VIEW)
    for component in (
        "PageHeader",
        "KpiCard",
        "QualityRulePanel",
        "QualityRunPanel",
        "QualityIssuePanel",
    ):
        assert component in view, f"质量页缺少 {component}"

    nav = _read(NAV)
    assert "name: 'data-quality'" in nav and "path: '/quality'" in nav
    assert "permissions: ['mdm:quality:read']" in nav

    router = _read(ROUTER)
    assert "DataQualityView.vue" in router and "name: 'data-quality'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.data-quality.title'" in i18n and "'nav.data-quality.hint'" in i18n
