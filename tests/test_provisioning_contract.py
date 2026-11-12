"""租户开通与生命周期契约锁（第 284 批 / ADR-001 P3）。

锁的是"商业版开通"的四条底线，防止后续迭代把它们悄悄改回 demo 形态：

1. 开通是**可重入流水线**：步骤落库、失败可 resume、管理员不重复创建；
2. **安全护栏**：不得开进控制面库、不得两家医院共用一个 Cell 库、一次性口令不落库；
3. **生命周期**：停用同步到 Cell 并即时生效；注销是终态，保留期与原因必须落台账；
4. **健康度**：离线医院只影响自己那一行，且结论可解释（state + issues）。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

AUTH_MAIN = REPO / "platform/auth/src/main"
V98 = AUTH_MAIN / "resources/db/migration/auth/V98__tenant_provisioning.sql"
PROVISION_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/ProvisioningService.java"
PROVISION_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/ProvisionRepository.java"
CELL_GATEWAY = AUTH_MAIN / "java/com/medassist/auth/service/JdbcCellDatabaseGateway.java"
TENANT_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/TenantService.java"
HEALTH_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/TenantHealthService.java"
TENANT_CONTROLLER = AUTH_MAIN / "java/com/medassist/auth/web/TenantController.java"

LICENSE_INTERNAL = (REPO / "platform/license/src/main/java/com/medassist/license/web"
                    / "InternalLicenseController.java")

SCRIPT = REPO / "scripts/provision_cell.ps1"
LIFECYCLE = REPO / "scripts/e2e/tenant_lifecycle_suite.py"

CONSOLE = REPO / "frontend-console/src"
TENANT_VIEW = CONSOLE / "views/TenantView.vue"
TENANT_API = CONSOLE / "api/tenant.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 迁移与模型


def test_provisioning_tables_and_constraints():
    sql = _read(V98)
    assert "CREATE TABLE IF NOT EXISTS auth_svc.provision_job" in sql
    assert "CREATE TABLE IF NOT EXISTS auth_svc.provision_step" in sql
    # 同一家医院同时只能有一个进行中的开通任务
    assert "ux_provision_active" in sql and "WHERE status = 'RUNNING'" in sql
    # 一院一库：同一 (host, db) 不能被两家租户占用
    assert "ux_tenant_cell_db" in sql
    # 注销保留策略
    assert "closed_at" in sql and "closed_reason" in sql and "retention_until" in sql


def test_step_order_is_explicit_and_resumable():
    service = _read(PROVISION_SERVICE)
    for step in ("SPEC", "TENANT", "CELL_CONNECT", "CELL_TENANT", "ADMIN",
                 "LICENSE", "ACTIVATE", "HEALTH"):
        assert f'"{step}"' in service, f"缺少步骤 {step}"
    # 失败不再往下跑；resume 复用同一 job 并置 RUNNING
    assert "STEP_FAILED" in service and "jobs.finishJob" in service
    assert "jobs.markRunning" in service
    assert "resume(UUID jobId" in service


def test_control_plane_database_is_forbidden():
    service = _read(PROVISION_SERVICE)
    assert "requireNotControlPlaneDatabase" in service
    assert "spring.datasource.url" in service
    assert "禁止把租户开通到控制面数据库" in service
    assert "cellDatabaseTaken" in service


def test_one_time_password_is_never_persisted():
    repo = _read(PROVISION_REPO)
    service = _read(PROVISION_SERVICE)
    # 任务/步骤仓储完全不处理口令字段
    assert "password" not in repo.lower()
    assert 'result.put("adminCreated"' in service
    assert "hasher.encode(password)" in service
    # §285 起口令生成收敛到统一策略，避免"开通"与"重置"各生成一套
    assert "passwordPolicy.generateTemporary()" in service


def test_admin_step_is_idempotent():
    service = _read(PROVISION_SERVICE)
    assert "hasAdmin" in service
    assert "管理员已存在，未重置其口令" in service
    assert "Outcome.skip" in service


# ---------------------------------------------------------------- 生命周期


def test_suspend_propagates_to_cell_but_never_blocks_control_plane():
    service = _read(TENANT_SERVICE)
    assert "propagateToCell" in service
    assert "updateTenantStatus" in service
    # Cell 不可达不能阻止停用（撤销刷新令牌已经生效）
    assert "tenant.status.cell_sync_failed" in service
    assert "cellPropagated" in service
    gateway = _read(CELL_GATEWAY)
    assert "UPDATE auth_svc.tenant" in gateway
    assert "closed_at = COALESCE" in gateway


def test_close_requires_retention_and_is_terminal():
    sql = _read(V98)
    assert "retention_until date" in sql
    service = _read(TENANT_SERVICE)
    assert "retentionUntil" in service
    assert "plusDays(365)" in service
    domain = _read(AUTH_MAIN / "java/com/medassist/auth/domain/Tenant.java")
    # CLOSED 不应出现在任何来源状态的分支里
    assert "case SUSPENDED -> ACTIVE.equals(target) || CLOSED.equals(target);" in domain
    assert "default -> false;" in domain


def test_health_is_parallel_and_degradable():
    service = _read(HEALTH_SERVICE)
    assert "newFixedThreadPool(4" in service
    assert "PER_TENANT_TIMEOUT_SECONDS" in service
    assert "健康检查超时" in service
    for state in ("OK", "PENDING", "SUSPENDED", "CLOSED", "DEGRADED", "DOWN"):
        assert f'"{state}"' in service, f"缺少健康状态 {state}"
    assert "issues" in service


def test_provisioning_endpoints_are_platform_operator_only():
    controller = _read(TENANT_CONTROLLER)
    assert '@PostMapping("/provisions")' in controller
    assert '@PostMapping("/provisions/{id}/resume")' in controller
    assert '@GetMapping("/provisions")' in controller
    assert '@GetMapping("/health")' in controller
    assert '@GetMapping("/{code}/health")' in controller
    for marker in ('"/provisions"', '"/health"'):
        tail = controller.split(marker, 1)[1][:900]
        assert "Permissions.require(permissions, MANAGE)" in tail


def test_license_summary_internal_endpoint_is_fail_closed():
    controller = _read(LICENSE_INTERNAL)
    assert '@RequestMapping("/internal/license")' in controller
    assert "X-Internal-Token" in controller
    assert "MessageDigest.isEqual" in controller
    # 网关默认拒绝 /internal/**：开通编排只能内网直连
    gateway = _read(REPO / "platform/gateway/src/main/resources/application.yml")
    assert "/internal/**" in gateway


# ---------------------------------------------------------------- 脚本与界面


def test_provision_script_uses_api_and_utf8():
    raw = SCRIPT.read_bytes()
    assert raw.startswith(b"\xef\xbb\xbf"), "provision_cell.ps1 必须以 UTF-8 BOM 保存"
    text = raw.decode("utf-8-sig")
    assert "/auth/tenants/provisions" in text
    assert "ConvertTo-Json -Depth 8" in text
    assert "UTF8.GetBytes" in text
    assert "一次性管理员口令" in text
    # 迁移仍由 Cell 自己执行（控制面不跑 Flyway）
    assert "--medassist.migrate-only=true" in text


def test_lifecycle_suite_covers_retention_semantics():
    text = _read(LIFECYCLE)
    assert "40320" in text  # 停用后登录被拒
    assert "56403" in text  # 注销终态不可恢复
    assert "停用不删除数据" in text
    assert "retentionUntil" in text
    assert "direct_auth=True" in text


def test_console_exposes_health_and_provisioning():
    api = _read(TENANT_API)
    assert "health: ()" in api and "healthOf:" in api
    assert "provision:" in api and "resumeProvision:" in api
    assert "TenantHealth" in api and "ProvisionJob" in api
    view = _read(TENANT_VIEW)
    for text in ("开通 Cell", "健康", "需关注", "从失败步骤继续", "一次性管理员口令"):
        assert text in view, f"租户页缺少 {text}"
    assert "loadHealth" in view and "healthBadge" in view


def test_runbook_documents_production_steps():
    doc = _read(RUNBOOK)
    for text in ("Helm", "provisions", "迁移", "license", "回滚", "30 分钟"):
        assert text in doc, f"开通手册缺少：{text}"
