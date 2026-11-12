"""授权与许可（第 279 批 / 计划书 W9.2）契约锁。

锁的是"商业承诺不能被绕过"的四条边界：
1. **签名**：授权书必须 Ed25519 验签通过才入库，且可读视图与签名原文必须一致；
2. **实例绑定**：一张授权书只能用于签发的那个部署实例；
3. **权限**：只有管理员能导入/吊销（运营可看不可改）；
4. **门禁默认观察**：超配额/未授权先告警，不阻断临床（LICENSE_ENFORCE 默认 false）。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
LICENSE_DIR = REPO / "platform/license/src/main"
MIGRATION = LICENSE_DIR / "resources/db/migration/license/V1__init_license.sql"
SERVICE = LICENSE_DIR / "java/com/medassist/license/service/LicenseService.java"
CRYPTO = LICENSE_DIR / "java/com/medassist/license/support/LicenseCrypto.java"
PAYLOADS = LICENSE_DIR / "java/com/medassist/license/support/LicensePayloads.java"
CONTROLLER = LICENSE_DIR / "java/com/medassist/license/web/LicenseController.java"
APP_YML = LICENSE_DIR / "resources/application.yml"

AUTH_V93 = REPO / "platform/auth/src/main/resources/db/migration/auth/V93__license_permissions.sql"
GATEWAY_YML = REPO / "platform/gateway/src/main/resources/application.yml"
RUN_SCRIPT = REPO / "scripts/run_platform_demo.ps1"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/license.ts"
VIEW = CONSOLE / "views/LicenseView.vue"
ROUTER = CONSOLE / "router/index.ts"
NAV = CONSOLE / "navigation.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_has_partial_unique_active_and_append_only_audit():
    sql = _read(MIGRATION)
    assert "CREATE SCHEMA IF NOT EXISTS license_svc" in sql
    for table in ("lic_edition", "lic_license", "lic_usage", "lic_change_log"):
        assert f"license_svc.{table}" in sql, f"缺少表 {table}"
    # 一个实例同时只能有一份生效授权
    assert "CREATE UNIQUE INDEX IF NOT EXISTS ux_lic_active_instance" in sql
    assert "WHERE status = 'ACTIVE'" in sql
    # 审计只允许追加：数据库层兜底，业务代码写错也改不了历史
    assert "reject_audit_mutation" in sql
    assert "BEFORE UPDATE OR DELETE ON license_svc.lic_change_log" in sql
    # 签名原文必须逐字保存（jsonb 会重排键序，不能用于验签对象）
    assert "payload              text NOT NULL" in sql


def test_signing_and_verification_are_ed25519_with_canonical_bytes():
    crypto = _read(CRYPTO)
    assert 'ALG = "Ed25519"' in crypto
    assert "Signature.getInstance(ALG)" in crypto
    assert "MessageDigest.getInstance(\"SHA-256\")" in crypto
    payloads = _read(PAYLOADS)
    # 规范化文本必须自己拼（不依赖通用序列化器的键序/空白）
    assert "canonical(" in payloads
    assert '"\\"schema\\":"' in payloads
    assert "normalizeModules" in payloads


def test_import_verifies_signature_before_storing():
    src = _read(SERVICE)
    import_block = src.split("public License importLicense(", 1)[1].split("public License revoke(", 1)[0]
    assert "LicenseCrypto.verify(" in import_block, "导入必须先验签"
    assert import_block.index("LicenseCrypto.verify(") < import_block.index("repo.insert("), "验签必须早于入库"
    # 可读 payload 与签名原文必须一致（防"看到的内容≠签过的内容"）
    assert 'canonicalOf(candidate).equals(canonical)' in import_block
    assert "LICENSE_INSTANCE_MISMATCH" in import_block
    assert "LICENSE_DUPLICATE" in import_block
    # 超配额只告警
    assert "配额超限" in src
    assert "throw new BusinessException(ErrorCode.LICENSE_USAGE_INVALID" in src


def test_issuance_is_opt_in_for_vendor_builds():
    # §289：默认院方形态（fail-closed）——忘记配置的院内部署不能给自己发证
    assert "deployment-mode: ${LICENSE_DEPLOYMENT_MODE:hospital}" in _read(APP_YML)
    assert "issuance-enabled: ${LICENSE_ISSUANCE_ENABLED:false}" in _read(APP_YML)
    assert "enforce: ${LICENSE_ENFORCE:false}" in _read(APP_YML), "门禁默认必须是观察模式"
    dev = _read(LICENSE_DIR / "resources/application-dev.yml")
    assert "deployment-mode: vendor" in dev, "开发演示必须显式选择厂商构建"
    src = _read(SERVICE)
    assert "props.issuanceEnabled()" in src
    assert "LICENSE_ISSUANCE_DISABLED" in src


def test_hospital_build_with_issuance_enabled_refuses_to_start():
    """§289：院方构建 + 开启签发 = 误配，必须在启动期失败而不是静默降级。"""
    guard = _read(LICENSE_DIR / "java/com/medassist/license/config/LicenseDeploymentGuard.java")
    assert "LICENSE_DEPLOYMENT_MODE 只能是 vendor 或 hospital" in guard
    assert "禁止开启签发能力" in guard
    assert "throw new IllegalStateException" in guard


def test_ai_usage_ingest_is_internal_token_guarded_and_only_updates_calls():
    """§289：AI 中台日报走内部端点；只写 ai_calls，不能覆盖床位/账号。"""
    internal = _read(LICENSE_DIR / "java/com/medassist/license/web/InternalLicenseController.java")
    assert '"/usage/ai"' in internal
    assert "X-Internal-Token" in internal
    assert "ingestAiUsage" in internal

    repo = _read(LICENSE_DIR / "java/com/medassist/license/repo/LicenseRepository.java")
    upsert = repo.split("public void upsertAiCalls", 1)[1].split("/* ---", 1)[0]
    assert "SET ai_calls = EXCLUDED.ai_calls" in upsert
    assert "active_beds = EXCLUDED.active_beds" not in upsert, "AI 上报不能整行覆盖床位"
    assert "active_users = EXCLUDED.active_users" not in upsert, "AI 上报不能整行覆盖账号"

    service = _read(SERVICE)
    assert "只接受当日或前一日" in service
    assert "USAGE_AI_FIRST" in service and "USAGE_AI_DECREASE" in service


def test_permissions_are_admin_only_for_manage():
    import re

    sql = _read(AUTH_V93)
    assert "'license:read'" in sql and "'license:manage'" in sql
    grants = re.findall(r"\('([A-Z_]+)',\s*'(license:(?:read|manage))'\)", sql)
    manage_roles = sorted({role for role, perm in grants if perm == "license:manage"})
    assert manage_roles == ["ADMIN"], f"license:manage 只应授予 ADMIN，实际 {manage_roles}"
    read_roles = sorted({role for role, perm in grants if perm == "license:read"})
    assert "OPS_MANAGER" in read_roles and "QC" in read_roles


def test_gateway_routes_license_with_its_own_rate_bucket():
    yml = _read(GATEWAY_YML)
    assert "Path=/api/v2/license/**" in yml
    assert "PLATFORM_LICENSE_URI:http://127.0.0.1:8095" in yml
    assert "- path: /api/v2/license" in yml
    assert "SENTINEL_QPS_LICENSE" in yml
    assert 'name = "license";   port = 8095' in _read(RUN_SCRIPT)


def test_frontend_page_is_wired_end_to_end():
    api = _read(API)
    for path in ("/license/status", "/license/licenses", "/license/audit", "/license/import", "/license/issue"):
        assert f"'{path}" in api or f"`{path}" in api, f"前端缺少接口 {path}"
    assert "license:manage" in _read(VIEW)

    view = _read(VIEW)
    # 四态齐全：加载骨架 / 无权限 / 错误重试 / 空态
    for marker in ("TableSkeleton", "PermissionDenied", "LoadError", "EmptyState"):
        assert f"<{marker}" in view, f"缺少状态组件 {marker}"
    assert "验签并导入" in view, "导入入口必须说明验签语义"

    router = _read(ROUTER)
    assert "path: 'license'" in router and "@/views/LicenseView.vue" in router
    nav = _read(NAV)
    assert "name: 'license'" in nav and "permissions: ['license:read']" in nav
    assert "'nav.license.title'" in _read(ZH) and "'nav.license.title'" in _read(EN)


def test_controller_separates_read_and_manage():
    src = _read(CONTROLLER)
    assert src.count("Permissions.require(permissions, READ)") >= 5
    assert src.count("Permissions.require(permissions, MANAGE)") >= 4
    assert "@RequestMapping(\"/license\")" in src
