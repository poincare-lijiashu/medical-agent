"""备份与灾备演练 + 发布门禁（第 297 批）。

锁死的约束：

1. **备份运行幂等**：idempotency_key 唯一，RUNNING 可被终结一次，结束后不可改写
   （失败被重试覆盖成成功，等于安全事件被抹掉）。
2. **演练通过的门槛是组合条件**：必填步骤全通过 + 实测 RTO/RPO 不超目标 +
   数据一致性核对 + 证据非空 + 复核人不是执行人（四眼）。
3. **发布门禁 fail-closed**：生产部署（非回滚）必须存在有效期内的已复核演练；
   查不到、查失败、过期都按"没有演练"处理。
4. **权限与角色切分**：一线运维可执行，不能自己判定通过；发布经理/SRE 只读门禁。
5. **前端只做展示与合法动作**：不 v-html、不直连 fetch、不前端自报操作人。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OPS_SQL = REPO / "platform/ops/src/main/resources/db/migration/ops/V4__backup_dr.sql"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V105__backup_dr_permissions.sql"
SERVICE = REPO / "platform/ops/src/main/java/com/medassist/ops/service/BackupDrService.java"
REPO_JAVA = REPO / "platform/ops/src/main/java/com/medassist/ops/repo/BackupDrRepository.java"
CONTROLLER = REPO / "platform/ops/src/main/java/com/medassist/ops/web/BackupDrController.java"
ERROR_CODE = REPO / "platform/common/src/main/java/com/medassist/common/api/ErrorCode.java"
DEPLOY = REPO / "platform/release/src/main/java/com/medassist/release/service/DeploymentService.java"
RELEASE_PROPS = REPO / "platform/release/src/main/java/com/medassist/release/config/ReleaseProperties.java"
RELEASE_CONFIG = REPO / "platform/release/src/main/resources/application.yml"

CONSOLE = REPO / "frontend-console/src"
OPS_API = CONSOLE / "api/ops.ts"
PANEL = CONSOLE / "components/domain/ops/OpsDrPanel.vue"
VIEW = CONSOLE / "views/DrDrillView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_ops_schema_has_idempotency_and_four_eyes():
    sql = _read(OPS_SQL)
    for table in (
        "ops_svc.ops_backup_policy",
        "ops_svc.ops_backup_run",
        "ops_svc.ops_dr_drill",
        "ops_svc.ops_dr_drill_step",
    ):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in sql, f"缺少表 {table}"
    assert "idempotency_key  text NOT NULL UNIQUE" in sql
    assert "verifier <> executor" in sql, "演练通过必须是四眼"
    assert "rto_actual_minutes <= rto_target_minutes" in sql
    assert "rpo_actual_minutes <= rpo_target_minutes" in sql
    assert "consistency_verified" in sql
    assert "DR_DRILL_PASS" in sql


def test_service_enforces_drill_gates():
    src = _read(SERVICE)
    assert "OPS_DR_SELF_VERIFY" in src and "actor.equals(drill.executor())" in src
    assert "countStepsNotPassed" in src
    assert "OPS_DR_STEPS_INCOMPLETE" in src
    assert "OPS_DR_TARGET_EXCEEDED" in src
    assert "OPS_DR_EVIDENCE_REQUIRED" in src
    assert "req.rtoActualMinutes() > drill.rtoTargetMinutes()" in src
    assert "req.rpoActualMinutes() > drill.rpoTargetMinutes()" in src

    repo = _read(REPO_JAVA)
    assert "findLatestPassed" in repo and "verifier IS NOT NULL" in repo
    assert "idempotency_key = ?" in repo


def test_controller_permissions_and_endpoints():
    src = _read(CONTROLLER)
    assert "ops:backup:read" in src and "ops:backup:manage" in src
    assert "ops:dr:read" in src and "ops:dr:manage" in src and "ops:dr:verify" in src
    for path in (
        '"/backup/policies"',
        '"/backup/runs"',
        '"/dr/drills"',
        '"/dr/drills/{id}/start"',
        '"/dr/drills/{id}/complete"',
        '"/dr/gate"',
    ):
        assert path in src, f"缺少接口 {path}"
    # 复核动作必须单独权限点，不能和执行共用一个权限
    complete_block = src.split('"/dr/drills/{id}/complete"', 1)[1].split("Permissions.require", 1)[1]
    assert "DR_VERIFY" in complete_block.split(";", 1)[0]


def test_error_codes_registered():
    src = _read(ERROR_CODE)
    for code in (
        "OPS_BACKUP_POLICY_NOT_FOUND",
        "OPS_BACKUP_RUN_CONFLICT",
        "OPS_DR_DRILL_NOT_FOUND",
        "OPS_DR_SELF_VERIFY",
        "OPS_DR_TARGET_EXCEEDED",
        "REL_DR_DRILL_REQUIRED",
    ):
        assert code in src, f"缺少错误码 {code}"
    assert "REL_DR_DRILL_REQUIRED(53425" in src


def test_release_gate_is_fail_closed():
    src = _read(DEPLOY)
    assert "requireDrDrill(req.componentCode(), now)" in src
    assert '"PROD".equals(req.environment()) && !"ROLLBACK".equals(req.action())' in src
    assert "catch (RuntimeException e)" in src
    assert "REL_DR_DRILL_REQUIRED" in src
    assert 'repo.latestPassedDrill(componentCode, "PROD", since)' in src
    assert "drDrill" in src, "通过门禁的演练编号应写入部署审计"

    props = _read(RELEASE_PROPS)
    assert "drillMaxAgeDays = 90" in props
    config = _read(RELEASE_CONFIG)
    assert "drill-max-age-days" in config and "REL_DRILL_MAX_AGE_DAYS" in config


def test_auth_permissions_and_roles():
    src = _read(AUTH_SQL)
    for perm in (
        "ops:backup:read",
        "ops:backup:manage",
        "ops:dr:read",
        "ops:dr:manage",
        "ops:dr:verify",
    ):
        assert f"('{perm}'" in src, f"缺少权限 {perm}"
    # 一线运维可执行但不能复核；复核只给运营经理与管理员
    assert "('OPS_AGENT', 'ops:dr:manage')" in src
    assert "('OPS_AGENT', 'ops:dr:verify')" not in src
    assert "('OPS_MANAGER', 'ops:dr:verify')" in src
    assert "('SRE_DEPLOYER', 'ops:dr:read')" in src


def test_frontend_wiring():
    api = _read(OPS_API)
    for path in ("'/ops/backup/policies'", "'/ops/backup/runs'", "'/ops/dr/drills'", "'/ops/dr/gate'"):
        assert path in api, f"缺少前端端点 {path}"
    assert "completeDrill" in api and "drGate" in api
    assert "fetch(" not in api

    panel = _read(PANEL)
    assert "ops:dr:verify" in panel and "ops:dr:manage" in panel
    assert "执行人不能自己复核通过" in panel
    assert "v-html" not in panel
    assert "fetch(" not in panel
    assert "localStorage" not in panel

    view = _read(VIEW)
    assert "OpsDrPanel" in view and "PageHeader" in view

    nav = _read(NAV)
    assert "name: 'dr-drill'" in nav and "path: '/ops/dr'" in nav
    assert "permissions: ['ops:dr:read']" in nav
    block = nav.split("name: 'dr-drill'", 1)[1].split("},", 1)[0]
    assert "group: '平台运维'" in block

    router = _read(ROUTER)
    assert "DrDrillView.vue" in router and "name: 'dr-drill'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.dr-drill.title'" in i18n and "'nav.dr-drill.hint'" in i18n
