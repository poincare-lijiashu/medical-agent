"""凭据生命周期与首登强制改密契约锁（第 285 批）。

锁的是"一次性口令"从文档承诺变成系统事实的四条底线：

1. **标记落库**：`must_change_password` 是账号状态的一部分，不是前端临时变量；
2. **入口唯一**：所有写口令的路径共用 `PasswordPolicy`，不存在"某条入口能设出弱口令"；
3. **闭环完整**：自助改密要校验当前口令、清标记、撤销其他会话并给当前端换发令牌；
4. **交付可见**：开通/重置生成临时口令、管理员能看到"待首次改密"、前端不可跳过改密。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

AUTH_MAIN = REPO / "platform/auth/src/main"
V99 = AUTH_MAIN / "resources/db/migration/auth/V99__password_lifecycle.sql"

POLICY = AUTH_MAIN / "java/com/medassist/auth/security/PasswordPolicy.java"
AUTH_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/AuthService.java"
AUTH_CONTROLLER = AUTH_MAIN / "java/com/medassist/auth/web/AuthController.java"
USER_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/UserService.java"
USER_REPO = AUTH_MAIN / "java/com/medassist/auth/repo/UserRepository.java"
USER_ACCOUNT = AUTH_MAIN / "java/com/medassist/auth/domain/UserAccount.java"
CELL_GATEWAY = AUTH_MAIN / "java/com/medassist/auth/service/JdbcCellDatabaseGateway.java"
PROVISION_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/ProvisioningService.java"
USER_CONTROLLER = AUTH_MAIN / "java/com/medassist/auth/web/UserController.java"

CONSOLE = REPO / "frontend-console/src"
SESSION = CONSOLE / "stores/session.ts"
API_INDEX = CONSOLE / "api/index.ts"
SHELL = CONSOLE / "layouts/AppShell.vue"
DIALOG = CONSOLE / "components/auth/ChangePasswordDialog.vue"
SYSTEM_VIEW = CONSOLE / "views/SystemView.vue"
I18N_ZH = CONSOLE / "i18n/zh-CN.ts"
I18N_EN = CONSOLE / "i18n/en.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 模型与策略


def test_migration_adds_flag_with_default():
    sql = _read(V99)
    assert "must_change_password boolean NOT NULL DEFAULT false" in sql
    assert "ALTER TABLE auth_svc.app_user" in sql
    # 幂等：开通/重跑迁移不会因为列已存在而失败
    assert "ADD COLUMN IF NOT EXISTS" in sql
    assert "COMMENT ON COLUMN auth_svc.app_user.must_change_password" in sql


def test_account_and_repository_carry_the_flag():
    account = _read(USER_ACCOUNT)
    assert "boolean mustChangePassword" in account
    repo = _read(USER_REPO)
    assert "must_change_password" in repo
    assert "rs.getBoolean(\"must_change_password\")" in repo
    # 语义相反的两种写入口令必须显式传入，禁止默认值替调用方决定
    assert "updatePassword(UUID id, String passwordHash, boolean mustChangePassword)" in repo


def test_password_policy_is_the_single_gate():
    policy = _read(POLICY)
    for rule in ("MIN_LENGTH", "WEAK_PASSWORDS", "密码不能与用户名相同", "密码不能包含用户名"):
        assert rule in policy, f"口令策略缺少：{rule}"
    assert "generateTemporary" in policy

    # 开户/重置与自助改密都必须过同一策略，不得各自实现一份
    user_service = _read(USER_SERVICE)
    assert "passwordPolicy.validate(effective" in user_service
    assert "passwordPolicy.validate(password, username)" in user_service
    auth_service = _read(AUTH_SERVICE)
    assert "passwordPolicy.validate(newPassword" in auth_service


# ---------------------------------------------------------------- 自助改密闭环


def test_self_service_change_endpoint_and_effects():
    controller = _read(AUTH_CONTROLLER)
    assert '@PostMapping("/me/password")' in controller
    assert "ChangePasswordReq" in controller

    service = _read(AUTH_SERVICE)
    assert "changeOwnPassword" in service
    # 1) 校验当前口令：只有访问令牌不足以改密
    assert "hasher.verify(currentPassword" in service
    # 2) 清首登标记：旧口令从此是本人设置的最终凭据
    assert "users.updatePassword(user.id(), hasher.encode(newPassword), false)" in service
    # 3) 其他设备下线 + 当前访问令牌作废 + 换发新令牌
    assert "tokens.revokeAllForUser(user.id())" in service
    assert '"PASSWORD_CHANGE"' in service
    assert "issueRefresh(user.id(), clientIp, userAgent, null)" in service
    # 4) 失败分支必须留痕（noRollbackFor 保证审计与限速计数不被回滚）
    assert "noRollbackFor = BusinessException.class" in service
    assert "PASSWORD_CHANGE_FAILED" in service

    # SSO 登录时作废管理员代设的临时本地口令：否则本人不知道"当前口令"，强制改密会变成死结
    assert "retireTemporaryPasswordOnSso" in service
    assert "PASSWORD_RETIRED_BY_SSO" in service


def test_admin_reset_issues_temporary_credential():
    controller = _read(USER_CONTROLLER)
    assert "ResetPasswordResp" in controller
    assert "new ResetPasswordResp(temp != null, temp)" in controller
    user_service = _read(USER_SERVICE)
    assert "passwordPolicy.generateTemporary()" in user_service
    assert "generated ? effective : null" in user_service
    # 重置同样要置位：管理员知道的口令不是本人的凭据
    assert "users.updatePassword(id, hasher.encode(effective), true)" in user_service


def test_provisioning_admin_must_change_on_first_login():
    gateway = _read(CELL_GATEWAY)
    assert "must_change_password, version, tenant_code" in gateway
    assert "'ACTIVE', true, 0, ?" in gateway
    service = _read(PROVISION_SERVICE)
    assert "passwordPolicy.generateTemporary()" in service
    assert '"mustChangePassword", true' in service


# ---------------------------------------------------------------- 控制台


def test_console_blocks_on_first_login():
    api = _read(API_INDEX)
    assert "changePassword: (currentPassword: string, newPassword: string)" in api
    assert "/auth/me/password" in api

    session = _read(SESSION)
    assert "async changePassword(" in session
    assert "authApi.changePassword" in session

    shell = _read(SHELL)
    assert "guardPasswordChange" in shell
    assert "ChangePasswordDialog" in shell
    assert "session.user.value?.mustChangePassword" in shell

    dialog = _read(DIALOG)
    assert ":dismissible=\"!forced\"" in dialog
    # 强制态下关闭请求必须被忽略，而不是只隐藏按钮
    assert "if (!open && props.forced) return" in dialog
    assert "pwd.err.mismatch" in dialog and "pwd.err.same" in dialog
    # 但必须留一条"离开"的路：共用工作站不能把人锁死在对话框里
    assert "'signout'" in dialog and "pwd.signout" in _read(I18N_ZH)

    zh = _read(I18N_ZH)
    en = _read(I18N_EN)
    for key in ("pwd.forcedTitle", "pwd.forcedDesc", "pwd.changed", "shell.changePassword"):
        assert f"'{key}'" in zh, f"中文文案缺少 {key}"
        assert f"'{key}'" in en, f"英文文案缺少 {key}"


def test_admin_console_shows_temporary_password_and_pending_state():
    view = _read(SYSTEM_VIEW)
    assert "一次" in view and "temporaryPassword" in view
    assert "待首次改密" in view
    assert "copyTempPassword" in view
    assert "resetForm.mode === 'manual'" in view


def test_runbook_documents_first_login_change():
    doc = _read(RUNBOOK)
    for text in ("首次登录", "must_change_password", "修改"):
        assert text in doc, f"开通手册缺少：{text}"
