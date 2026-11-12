"""Cell 数据面隔离（第 282 批 / ADR-001 P1）契约锁。

锁的是"一院一 Cell"里能被自动化证明的部分：

1. **部署形态**：每院一个 release + 独立命名空间 + 独立 PG + 独立配额；
   控制面不留"多租户共用一个库"的口子；
2. **迁移台账**：控制面从各 Cell 的 flyway_schema_history 汇总版本，
   失败迁移必须暴露（不能只看"有记录=已迁移"）；
3. **开通脚本**：本地能一键起第二院（独立 PG + 全量迁移 + 登记台账），
   并且脚本必须是 UTF-8 BOM（PS 5 无 BOM 会把中文读成乱码导致语法错误）；
4. **迁移入口**：每个有库的服务都能以一次性 Job 模式迁移（Helm Hook 以此为前提）。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

AUTH_MAIN = REPO / "platform/auth/src/main"
V96 = AUTH_MAIN / "resources/db/migration/auth/V96__cell_migration.sql"
MIG_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/CellMigrationRepository.java"
MIG_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/CellMigrationService.java"
TENANT_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/TenantRepository.java"
TENANT_CTRL = AUTH_MAIN / "java/com/medassist/auth/web/TenantController.java"

CHART = REPO / "deploy/helm/medassist-platform"
VALUES = CHART / "values.yaml"
VALUES_CELL = CHART / "values-cell-example.yaml"
HELPERS = CHART / "templates/_helpers.tpl"
QUOTA = CHART / "templates/resourcequota.yaml"
LIMIT = CHART / "templates/limitrange.yaml"

SCRIPT = REPO / "scripts/new_cell.ps1"

CONSOLE = REPO / "frontend-console/src"
TENANT_API = CONSOLE / "api/tenant.ts"
TENANT_VIEW = CONSOLE / "views/TenantView.vue"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 部署形态


def test_helm_chart_renders_one_cell_per_release():
    values = _read(VALUES)
    assert "cell:" in values
    assert 'tenantCode: ""' in values
    for key in ("resourceQuota", "limitRange"):
        assert key in values, f"values.yaml 缺少 {key}"

    example = _read(VALUES_CELL)
    assert "tenant-001" in example and "tenant-002" in example
    # 每院独立 Secret：账号/备份互相不可见
    assert "medassist-postgres-tenant-001" in example
    assert "medassist-postgres-tenant-002" in example
    assert "--create-namespace" in example and "medassist-tenant-001" in example


def test_chart_pins_tenant_env_and_cell_label():
    helpers = _read(HELPERS)
    assert "medassist.io/tenant" in helpers
    assert "TENANT_DEFAULT_CODE" in helpers
    assert "TENANT_INSTANCE_CODE" in helpers
    # 入口路由只开在网关：业务服务按 Cell 部署，不该判断入口
    assert "TENANT_ROUTING_ENABLED" in helpers
    assert "and $isGateway $cell.hosts" in helpers

    quota = _read(QUOTA)
    assert "kind: ResourceQuota" in quota
    assert "requests.cpu" in quota and "pods" in quota
    limit = _read(LIMIT)
    assert "kind: LimitRange" in limit
    assert "defaultRequest" in limit


def test_every_db_service_can_migrate_as_one_shot_job():
    services = ["auth", "empi", "clinical", "pharmacy", "material", "tech", "emr",
                "insurance", "kb", "fhir", "masterdata", "migration", "ops", "release", "license"]
    missing = []
    for svc in services:
        candidates = list((REPO / "platform" / svc / "src/main/java").rglob("MigrateOnlyRunner.java"))
        if not candidates:
            missing.append(svc)
    assert not missing, f"以下服务缺少 migrate-only 入口（Helm Hook Job 无法迁移）：{missing}"


# ---------------------------------------------------------------- 迁移台账


def test_cell_migration_ledger_schema():
    sql = _read(V96)
    assert "CREATE TABLE IF NOT EXISTS auth_svc.cell_migration" in sql
    assert "ux_cell_migration" in sql
    assert "(tenant_code, service, version)" in sql
    # 库名是基础设施信息，存库但不进前端视图
    assert "ADD COLUMN IF NOT EXISTS db_name text NOT NULL DEFAULT 'medagent'" in sql
    assert "success      boolean NOT NULL" in sql


def test_ledger_reads_flyway_history_and_never_writes_to_cell():
    service = _read(MIG_SERVICE)
    assert "information_schema.tables" in service and "flyway_schema_history" in service
    # 只读：不能出现任何对 Cell 库的写操作
    for forbidden in ("INSERT INTO", "UPDATE ", "DELETE FROM", "ALTER TABLE"):
        assert forbidden not in service, f"迁移台账不允许写 Cell 库：{forbidden}"
    # 超时：院方库不可达时不能把控制面线程挂死
    assert "connectTimeout=" in service and "socketTimeout=" in service
    # 节流：页面刷新/多人查看不应持续打院方库
    assert "Duration.ofSeconds(30)" in service
    # 凭据来自部署 Secret，不在 tenant 表里
    assert '${medassist.cell-db.user:${PG_USER:medagent}}' in service


def test_ledger_keeps_failure_and_reports_unreachable_gracefully():
    repo = _read(MIG_REPO)
    assert "DISTINCT ON (service)" in repo
    # 曾失败过的版本不能被后来的成功抹掉
    assert "success AND EXCLUDED.success" in repo
    service = _read(MIG_SERVICE)
    assert "Cell 数据库不可达" in service
    # 不可达是"展示状态"而不是异常：视图里带 reachable/error 供前端提示
    dto = _read(AUTH_MAIN / "java/com/medassist/auth/web/dto/CellMigrationDtos.java")
    assert "boolean reachable" in dto and "String error" in dto


def test_ledger_endpoint_is_platform_operator_only():
    ctrl = _read(TENANT_CTRL)
    assert 'GetMapping("/{code}/migrations")' in ctrl
    assert "Permissions.require(permissions, MANAGE)" in ctrl
    tenant_repo = _read(TENANT_REPO)
    assert "findCellDatabase" in tenant_repo
    # 坐标不进 Tenant 聚合/前端视图
    assert "CellDatabase" in tenant_repo


# ---------------------------------------------------------------- 开通脚本


def test_new_cell_script_is_runnable_on_powershell_5():
    raw = SCRIPT.read_bytes()
    # PS 5.1 无 BOM 时按 ANSI 读取中文 → 语法错误（实测）
    assert raw.startswith(b"\xef\xbb\xbf"), "new_cell.ps1 必须以 UTF-8 BOM 保存"
    text = raw.decode("utf-8-sig")
    assert "postgres:15-alpine" in text
    assert "medassist.migrate-only=true" in text
    assert "INSERT INTO auth_svc.tenant" in text
    assert "ON CONFLICT (code) DO UPDATE" in text
    # 可执行包校验：拦截"jar 被运行中服务占用 → 留下普通 jar"的常见故障
    assert "BOOT-INF/" in text


# ---------------------------------------------------------------- 控制台


def test_console_shows_cell_migration_versions():
    api = _read(TENANT_API)
    assert "migrations: (code: string, refresh = false)" in api
    assert "CellMigrationView" in api
    view = _read(TENANT_VIEW)
    assert "Cell 迁移版本" in view
    assert "migrations?.hasFailure" in view
    assert 'v-if="platformLevel"' in view
