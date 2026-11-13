"""实施交付与培训考核（第 298 批 / 计划书 G7）。

锁死的约束：

1. **模板可复制**：阶段权重合计 100，任务在项目创建时快照，模板后续修改不影响在建项目。
2. **任务有证据**：DONE 必须有证据；BLOCKED 必须写原因；例外只能走豁免（写原因 + 留复核人）。
3. **上线就绪是组合条件**：必填任务全部完成（或豁免）+ 加权得分 ≥ 下限 + 必修课程通过率 ≥ 95%。
4. **四眼**：项目负责人不能自己批准上线或验收。
5. **培训口径**：同一学员同一考试只保留最新成绩，通过率按唯一学员统计。
6. **前端只做展示与合法动作**：不 v-html、不直连 fetch、不前端自报操作人。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OPS_SQL = REPO / "platform/ops/src/main/resources/db/migration/ops/V5__implementation_training.sql"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V106__impl_permissions.sql"
SERVICE = REPO / "platform/ops/src/main/java/com/medassist/ops/service/ImplementationService.java"
REPO_JAVA = REPO / "platform/ops/src/main/java/com/medassist/ops/repo/ImplementationRepository.java"
CONTROLLER = REPO / "platform/ops/src/main/java/com/medassist/ops/web/ImplementationController.java"
ERROR_CODE = REPO / "platform/common/src/main/java/com/medassist/common/api/ErrorCode.java"
PROPS = REPO / "platform/ops/src/main/java/com/medassist/ops/config/OpsProperties.java"
CONFIG = REPO / "platform/ops/src/main/resources/application.yml"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/impl.ts"
PROJECT_PANEL = CONSOLE / "components/domain/impl/ImplProjectPanel.vue"
TEMPLATE_PANEL = CONSOLE / "components/domain/impl/ImplTemplatePanel.vue"
TRAINING_PANEL = CONSOLE / "components/domain/impl/ImplTrainingPanel.vue"
VIEW = CONSOLE / "views/ImplementationView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_schema_and_builtin_template():
    sql = _read(OPS_SQL)
    for table in (
        "ops_svc.impl_template",
        "ops_svc.impl_template_phase",
        "ops_svc.impl_template_task",
        "ops_svc.impl_project",
        "ops_svc.impl_project_task",
        "ops_svc.impl_project_event",
        "ops_svc.impl_course",
        "ops_svc.impl_question",
        "ops_svc.impl_exam",
        "ops_svc.impl_attempt",
    ):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in sql, f"缺少表 {table}"
    assert "STD-HOSP-v1" in sql, "缺少内置标准实施模板"
    assert "IMPL_PROJECT_GO_LIVE" in sql and "IMPL_ATTEMPT_RECORD" in sql
    assert "trg_impl_project_event_append_only" in sql
    assert "UNIQUE (exam_id, trainee)" in sql


def test_service_enforces_readiness_and_four_eyes():
    src = _read(SERVICE)
    assert "IMPL_TASK_EVIDENCE_REQUIRED" in src
    assert "IMPL_TASK_BLOCKED_REASON_REQUIRED" in src
    assert "IMPL_TASK_WAIVER_REASON_REQUIRED" in src
    assert "actor.equals(project.owner())" in src
    assert "IMPL_SELF_APPROVAL" in src
    assert "IMPL_NOT_READY" in src
    assert "getImplTrainingThreshold" in src
    assert "getImplMinScore" in src
    assert "missing.isEmpty() && rounded >= minScore && trainingMet" in src
    assert "earned * 100.0 / total" in src
    assert "题目 \" + question.questionNo() + \" 未作答" in src

    repo = _read(REPO_JAVA)
    assert "ON CONFLICT (exam_id, trainee) DO UPDATE" in repo
    assert "count(*) FILTER (WHERE a.passed)" in repo


def test_controller_permissions():
    src = _read(CONTROLLER)
    assert "ops:impl:read" in src and "ops:impl:manage" in src and "ops:impl:verify" in src
    for path in (
        '"/templates"',
        '"/projects"',
        '"/projects/{id}/readiness"',
        '"/projects/{id}/go-live"',
        '"/projects/{id}/accept"',
        '"/courses"',
        '"/projects/{id}/exams"',
        '"/exams/{id}/attempts"',
        '"/dashboard"',
    ):
        assert path in src, f"缺少接口 {path}"
    go_live = src.split('"/projects/{id}/go-live"', 1)[1].split("Permissions.require", 1)[1]
    assert "VERIFY" in go_live.split(";", 1)[0]
    waive = src.split('"/projects/{id}/tasks/{taskId}/waive"', 1)[1].split("Permissions.require", 1)[1]
    assert "VERIFY" in waive.split(";", 1)[0]
    # 题库答案键只对实施管理员可见
    course_detail = src.split('"/courses/{id}"', 1)[1].split("Permissions.require", 1)[1]
    assert "MANAGE" in course_detail.split(";", 1)[0]


def test_error_codes_and_thresholds():
    src = _read(ERROR_CODE)
    for code in (
        "IMPL_TEMPLATE_INVALID",
        "IMPL_TASK_EVIDENCE_REQUIRED",
        "IMPL_NOT_READY",
        "IMPL_SELF_APPROVAL",
        "IMPL_ATTEMPT_INVALID",
    ):
        assert code in src, f"缺少错误码 {code}"
    assert "IMPL_ATTEMPT_INVALID(52458" in src

    props = _read(PROPS)
    assert "implMinScore = 90" in props and "implTrainingThreshold = 95" in props
    config = _read(CONFIG)
    assert "impl-min-score" in config and "impl-training-threshold" in config


def test_auth_permissions_and_roles():
    src = _read(AUTH_SQL)
    for perm in ("ops:impl:read", "ops:impl:manage", "ops:impl:verify"):
        assert f"('{perm}'" in src, f"缺少权限 {perm}"
    assert "('OPS_AGENT', 'ops:impl:manage')" in src
    assert "('OPS_AGENT', 'ops:impl:verify')" not in src
    assert "('OPS_MANAGER', 'ops:impl:verify')" in src


def test_frontend_wiring():
    api = _read(API)
    for path in (
        "'/ops/impl/dashboard'",
        "'/ops/impl/templates'",
        "'/ops/impl/projects'",
        "'/ops/impl/courses'",
    ):
        assert path in api, f"缺少前端端点 {path}"
    assert "goLive" in api and "recordAttempt" in api
    assert "fetch(" not in api

    project_panel = _read(PROJECT_PANEL)
    assert "ops:impl:verify" in project_panel and "ops:impl:manage" in project_panel
    assert "项目负责人不能自己批准上线" in project_panel
    assert "v-html" not in project_panel and "fetch(" not in project_panel
    assert "localStorage" not in project_panel

    assert "weightTotal" in _read(TEMPLATE_PANEL)
    assert "通过率" in _read(TRAINING_PANEL)

    view = _read(VIEW)
    for component in ("PageHeader", "KpiCard", "ImplProjectPanel", "ImplTemplatePanel", "ImplTrainingPanel"):
        assert component in view, f"实施页缺少 {component}"

    nav = _read(NAV)
    assert "name: 'impl-delivery'" in nav and "path: '/ops/impl'" in nav
    assert "permissions: ['ops:impl:read']" in nav
    block = nav.split("name: 'impl-delivery'", 1)[1].split("},", 1)[0]
    assert "group: '平台运维'" in block

    router = _read(ROUTER)
    assert "ImplementationView.vue" in router and "name: 'impl-delivery'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.impl-delivery.title'" in i18n and "'nav.impl-delivery.hint'" in i18n
