"""租户身份平面（第 281 批 / ADR-001 P0）契约锁。

锁的是"隔离契约"里能在代码层证明的部分（不假装验证 chaos 演练）：

1. **归属唯一**：一个账号只属于一个租户；用户名唯一性从全平台收敛到租户内
   （两家医院都有 admin01 是常态，全局唯一会让第二家被迫改工号）；
2. **入口一致**：令牌带 tenant + instance，网关按 Host 映射校验，
   拿 A 院令牌打 B 院入口返回 403，客户端自带的 X-Tenant 一律剥离；
3. **停用即下线**：租户停用后不能登录、不能续期，并撤销全院刷新令牌；
4. **跨租户不可见**：管理端访问别院账号/租户一律 404（不是 403——403 等于承认存在）；
5. **最小权限**：只有平台运营（PLATFORM_OPERATOR）持有 tenant:manage，
   医院管理员不能停用别家租户。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

AUTH_MAIN = REPO / "platform/auth/src/main"
MIGRATION = AUTH_MAIN / "resources/db/migration/auth/V95__tenant_registry.sql"
APP_YML = AUTH_MAIN / "resources/application.yml"
JWT = AUTH_MAIN / "java/com/medassist/auth/security/JwtTokenService.java"
AUTH_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/AuthService.java"
TENANT_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/TenantService.java"
TENANT_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/TenantRepository.java"
TENANT_CTRL = AUTH_MAIN / "java/com/medassist/auth/web/TenantController.java"
USER_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/UserService.java"
USER_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/UserRepository.java"
SSO_PROVISIONER = AUTH_MAIN / "java/com/medassist/auth/service/SsoUserProvisioner.java"

COMMON = REPO / "platform/common/src/main/java/com/medassist/common"
AUTH_HEADERS = COMMON / "security/AuthHeaders.java"
ERROR_CODE = COMMON / "api/ErrorCode.java"

GATEWAY = REPO / "platform/gateway/src/main"
GATEWAY_FILTER = GATEWAY / "java/com/medassist/gateway/filter/AuthGlobalFilter.java"
GATEWAY_TENANT_PROPS = GATEWAY / "java/com/medassist/gateway/config/GatewayTenantProperties.java"
GATEWAY_YML = GATEWAY / "resources/application.yml"

CONSOLE = REPO / "frontend-console/src"
TENANT_API = CONSOLE / "api/tenant.ts"
TENANT_VIEW = CONSOLE / "views/TenantView.vue"
ROUTER = CONSOLE / "router/index.ts"
NAV = CONSOLE / "navigation.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"

RUN_SCRIPT = REPO / "scripts/run_platform_demo.ps1"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 数据模型


def test_tenant_registry_is_metadata_only_and_instance_code_is_unique():
    sql = _read(MIGRATION)
    assert "CREATE TABLE IF NOT EXISTS auth_svc.tenant" in sql
    # 实例码与授权书一一对应：重复实例码等于"一张授权书开两家院"
    assert "CREATE UNIQUE INDEX IF NOT EXISTS ux_tenant_instance_code" in sql
    assert "instance_code text NOT NULL" in sql
    # 台账只存目录元数据；出现患者相关列说明设计被写歪了
    for forbidden in ("patient", "diagnosis", "visit_id"):
        assert forbidden not in sql.lower(), f"租户台账不应包含临床字段：{forbidden}"
    assert "cell_endpoint" in sql and "db_host" in sql and "quotas" in sql


def test_existing_instance_is_registered_as_first_tenant():
    sql = _read(MIGRATION)
    assert "'tenant-001'" in sql
    assert "MEDASSIST-DEV-0001" in sql
    # 存量数据全部归入 tenant-001，不迁移、不改业务表
    assert "UPDATE auth_svc.app_user SET tenant_code = 'tenant-001'" in sql
    assert "ALTER TABLE auth_svc.app_user ALTER COLUMN tenant_code SET NOT NULL" in sql
    # 外键保证"账号一定属于某个已登记租户"（fail-closed）
    assert "fk_app_user_tenant" in sql
    assert "REFERENCES auth_svc.tenant (code)" in sql


def test_username_uniqueness_is_scoped_to_tenant():
    sql = _read(MIGRATION)
    # 全局唯一索引必须被替换，否则第二家医院不能用同样的工号
    assert "DROP INDEX IF EXISTS auth_svc.ux_app_user_username" in sql
    assert "ux_app_user_tenant_username" in sql
    assert "ON auth_svc.app_user (tenant_code, lower(username))" in sql

    repo = _read(USER_REPO)
    assert "WHERE tenant_code = ? AND lower(username) = lower(?)" in repo


def test_tenant_permissions_keep_manage_off_hospital_admin():
    sql = _read(MIGRATION)
    for perm in ("tenant:read", "tenant:self:manage", "tenant:manage"):
        assert f"'{perm}'" in sql, f"缺少权限点 {perm}"
    # 平台运营是唯一持有 tenant:manage 的角色
    assert "'PLATFORM_OPERATOR'" in sql
    assert "('PLATFORM_OPERATOR', 'tenant:manage')" in sql
    assert "('ADMIN', 'tenant:manage')" not in sql, "医院管理员不能停用别家租户"
    assert "('ADMIN', 'tenant:self:manage')" in sql

    controller = _read(TENANT_CTRL)
    assert 'private static final String MANAGE = "tenant:manage";' in controller
    assert "Permissions.require(permissions, MANAGE)" in controller


# ---------------------------------------------------------------- 令牌与登录


def test_token_carries_tenant_and_instance_claims():
    jwt = _read(JWT)
    assert '.claim("tenant", tenantCode.trim())' in jwt
    assert '.claim("instance", instanceCode.trim())' in jwt
    assert "String tenantCode, String instanceCode" in jwt

    auth = _read(AUTH_SERVICE)
    # 租户取账号归属，而不是入参：刷新令牌无法把身份"漂移"到别家
    assert "user.tenantCode()" in auth
    assert "tenantProps.effectiveInstanceCode()" in auth


def test_login_checks_tenant_status_before_password():
    auth = _read(AUTH_SERVICE)
    login_start = auth.index("public LoginResp login(LoginReq req, String clientIp, String userAgent, String tenantCode)")
    body = auth[login_start:]
    assert "tenants.requireUsableTenant(tenant)" in body
    # 租户检查必须早于密码校验：停用医院连"密码错误"都不应该回
    assert body.index("requireUsableTenant") < body.index("hasher.verify(req.password(), user.passwordHash())")
    # 按"租户 + 用户名"查账号
    assert "users.findByUsername(tenant, username)" in auth

    # 刷新同样要受租户状态约束，否则停用只挡新登录
    refresh_start = auth.index("public LoginResp refresh(")
    refresh_body = auth[refresh_start:auth.index("public void logout(")]
    assert "requireUsableTenant" in refresh_body


def test_suspended_tenant_revokes_all_refresh_tokens():
    service = _read(TENANT_SERVICE)
    assert "repo.revokeRefreshTokensOfTenant(current.code())" in service
    repo = _read(TENANT_REPO)
    assert "UPDATE auth_svc.refresh_token" in repo
    assert "user_id IN (SELECT id FROM auth_svc.app_user WHERE tenant_code = :code)" in repo


def test_sso_provisioning_lands_in_entry_tenant():
    provisioner = _read(SSO_PROVISIONER)
    assert "String tenantCode) {" in provisioner
    assert "tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim()" in provisioner
    assert "SSO_PROVISION" in provisioner

    # 回调侧要把入口租户传下去，否则自动开户永远落在默认租户
    sso = _read(AUTH_MAIN / "java/com/medassist/auth/service/SsoService.java")
    assert "username, displayName, email, tenant);" in sso
    assert "users.findByUsername(tenant, username)" in sso
    assert "requireSameTenant(user, tenant)" in sso


# ---------------------------------------------------------------- 跨租户不可见


def test_tenant_admin_cannot_see_or_touch_other_tenants():
    service = _read(TENANT_SERVICE)
    assert "requireVisible(" in service
    # 不可见统一按"不存在"处理，避免泄露"某院是否已开通"
    assert "throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);" in service

    user_service = _read(USER_SERVICE)
    assert "private UserAccount requireInTenant(UUID id, String tenantCode)" in user_service
    assert "if (!tenantCode.equals(account.tenantCode()))" in user_service
    # 计数必须按租户：别家有几个管理员与本院"最后一个管理员"无关
    assert "countActiveByRole(tenantCode, ADMIN_ROLE)" in user_service

    repo = _read(USER_REPO)
    assert "FROM auth_svc.app_user WHERE tenant_code = :tenantCode" in repo


# ---------------------------------------------------------------- 网关


def test_client_cannot_forge_tenant_header():
    headers = _read(AUTH_HEADERS)
    assert 'public static final String TENANT = "X-Tenant";' in headers
    assert "ACTOR, USER_ID, ROLES, PERMISSIONS, TOKEN_ID, TENANT, ORG, CAMPUS" in headers


def test_gateway_rejects_cross_tenant_and_foreign_instance():
    gw = _read(GATEWAY_FILTER)
    assert "tenantProps.resolveHost(" in gw
    assert "ErrorCode.TENANT_MISMATCH" in gw
    assert "ErrorCode.TENANT_INSTANCE_MISMATCH" in gw
    # 校验通过后才注入 X-Tenant
    assert ".header(AuthHeaders.TENANT, tenant)" in gw
    # 未鉴权路径（登录）也要带租户
    assert "withTenant(cleaned, entryTenant)" in gw
    assert "gateway.tenant.mismatch" in gw

    props = _read(GATEWAY_TENANT_PROPS)
    assert '@ConfigurationProperties(prefix = "medassist.gateway.tenant")' in props
    assert "boolean enabled" in props
    assert "resolveHost(String host)" in props
    # 通配按最长后缀匹配：子域不能被更宽的规则抢走
    assert 'lookup("*" + normalized.substring(idx))' in props

    yml = _read(GATEWAY_YML)
    assert "TENANT_ROUTING_ENABLED:false" in yml
    assert "TENANT_HOSTS:" in yml


def test_error_codes_follow_http_prefix_convention():
    codes = _read(ERROR_CODE)
    for snippet in ("TENANT_NOT_FOUND(56401", "TENANT_INVALID(56402",
                    "TENANT_STATUS_INVALID(56403", "TENANT_DUPLICATE(56409",
                    "TENANT_SUSPENDED(40320", "TENANT_MISMATCH(40321",
                    "TENANT_INSTANCE_MISMATCH(40322"):
        assert snippet in codes, f"缺少错误码 {snippet}"


def test_gateway_accepts_token_sized_headers():
    """令牌现在多带 tenant + instance，管理员令牌约 7.6KB。

    网关的请求头上限必须与业务服务（32KB）对齐：否则浏览器额外带上 UA/cookie 后
    整体超过 8KB，整站 431 —— 命令行复现不出来，只在真实浏览器出现。
    """
    yml = _read(GATEWAY_YML)
    assert "max-http-request-header-size: ${GATEWAY_MAX_HEADER_SIZE:32KB}" in yml


# ---------------------------------------------------------------- 控制台


def test_console_has_tenant_page_wired_with_permission_gate():
    api = _read(TENANT_API)
    assert "'/auth/tenants'" in api
    assert "/status`" in api
    view = _read(TENANT_VIEW)
    assert "tenant:self:manage" in view
    assert "platformLevel" in view
    # 停用必须填原因（前端拦截 + 后端校验双保险）
    assert "statusReason.trim().length < 2" in view

    nav = _read(NAV)
    assert "name: 'tenant'" in nav
    assert "path: '/tenant'" in nav
    assert "permissions: ['tenant:read']" in nav

    router = _read(ROUTER)
    assert "component: () => import('@/views/TenantView.vue')" in router

    assert "nav.tenant.title" in _read(ZH)
    assert "nav.tenant.title" in _read(EN)


def test_demo_script_pins_instance_code_to_license_default():
    script = _read(RUN_SCRIPT)
    assert "TENANT_INSTANCE_CODE" in script
    assert "MEDASSIST-DEV-0001" in script
    # 本地单入口：多租户路由默认关闭，避免开发环境被 Host 映射挡住
    assert 'TENANT_ROUTING_ENABLED = "false"' in script


def test_auth_service_config_exposes_tenant_settings():
    yml = _read(APP_YML)
    assert "tenant:" in yml
    assert "TENANT_DEFAULT_CODE:tenant-001" in yml
    assert "TENANT_INSTANCE_CODE:" in yml
    assert "TENANT_ENFORCE_ACTIVE:true" in yml
