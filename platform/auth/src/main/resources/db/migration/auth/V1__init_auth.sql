-- ============================================================================
-- 用户与权限中心（auth 域）初始表结构
-- 设计约束（计划书 D2/D3/D4）：
--   * 服务自治 schema（auth_svc），不与其他域共享表；跨域只经 API/事件
--   * 全表乐观锁（version）+ 审计（change_log），变更可回溯
--   * 密码沿用 bcrypt（兼容既有 Python 侧 $2b$ 哈希，迁移期可平滑登录）
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS auth_svc;

-- ---- 组织架构（医院/科室/病区）----
CREATE TABLE IF NOT EXISTS auth_svc.org (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    parent_code text REFERENCES auth_svc.org (code),
    type        text NOT NULL CHECK (type IN ('HOSPITAL', 'DEPT', 'WARD')),
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    sort_no     int  NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE auth_svc.org IS '组织架构：医院/科室/病区（科室编码与 HIS 一致）';

-- ---- 账号 ----
CREATE TABLE IF NOT EXISTS auth_svc.app_user (
    id            uuid PRIMARY KEY,
    username      text NOT NULL,
    password_hash text NOT NULL,
    display_name  text NOT NULL,
    employee_no   text,
    org_code      text REFERENCES auth_svc.org (code),
    status        text NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    version       bigint NOT NULL DEFAULT 0,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
-- 用户名唯一（大小写不敏感：医疗系统常见工号/拼音混输）
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_user_username
    ON auth_svc.app_user (lower(username));
CREATE INDEX IF NOT EXISTS ix_app_user_org ON auth_svc.app_user (org_code);
CREATE INDEX IF NOT EXISTS ix_app_user_status ON auth_svc.app_user (status);
COMMENT ON TABLE auth_svc.app_user IS '账号（用户名大小写不敏感唯一；密码 bcrypt）';
COMMENT ON COLUMN auth_svc.app_user.version IS '乐观锁版本号，更新时 CAS';

-- ---- 角色 ----
CREATE TABLE IF NOT EXISTS auth_svc.role (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    description text,
    builtin     boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE auth_svc.role IS '角色（内置角色 builtin=true，禁止删除）';

-- ---- 权限点（资源:动作）----
CREATE TABLE IF NOT EXISTS auth_svc.permission (
    code   text PRIMARY KEY,
    name   text NOT NULL,
    domain text NOT NULL
);
COMMENT ON TABLE auth_svc.permission IS '权限点，命名规范 域:资源:动作，如 empi:patient:create';

-- ---- 角色-权限 ----
CREATE TABLE IF NOT EXISTS auth_svc.role_permission (
    role_code       text NOT NULL REFERENCES auth_svc.role (code) ON DELETE CASCADE,
    permission_code text NOT NULL REFERENCES auth_svc.permission (code) ON DELETE CASCADE,
    PRIMARY KEY (role_code, permission_code)
);

-- ---- 用户-角色 ----
CREATE TABLE IF NOT EXISTS auth_svc.user_role (
    user_id   uuid NOT NULL REFERENCES auth_svc.app_user (id) ON DELETE CASCADE,
    role_code text NOT NULL REFERENCES auth_svc.role (code) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_code)
);
CREATE INDEX IF NOT EXISTS ix_user_role_role ON auth_svc.user_role (role_code);

-- ---- 变更审计（auth 域自治；后续接入统一审计服务）----
CREATE TABLE IF NOT EXISTS auth_svc.change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_change_log_target ON auth_svc.change_log (target_type, target_id);
COMMENT ON TABLE auth_svc.change_log IS 'auth 域变更审计（谁、何时、对什么、做了什么）';
