-- ============================================================================
-- V95：租户登记与租户作用域（第 281 批 / ADR-001「全院级全栈隔离」P0）
--
-- 关键语义（与 ADR 一致，实施时不要"顺手放宽"）：
--   * 租户 = 一家医院 = 一个独立 Cell（独立 PG / 独立服务组 / 独立 ingress）。
--     因此这里<b>没有 tenant_id 行级列</b>，也不需要：临床数据在物理上就不可跨越。
--   * 本表只在控制面（auth 所在库）保存"目录元数据"，绝不存患者数据。
--   * app_user.tenant_code 是"身份归属"，不是数据过滤条件：一个账号只属于一家医院，
--     跨租户访问在网关与 auth 两处被拒，返回 404/403 而不是空列表。
--   * instance_code 与授权书（license V1）绑定，一个租户一个实例码，
--     令牌带 instance 声明，跨 Cell 重放令牌会被入口拒绝。
--
-- 存量兼容：当前单实例（MEDASSIST-DEV-0001）注册为 tenant-001，账号全部归入该租户，
-- 不迁移数据、不改业务表结构。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.tenant (
    code          text PRIMARY KEY,
    instance_code text NOT NULL,
    name          text NOT NULL,
    edition_code  text,
    status        text NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    cell_endpoint text,
    db_host       text,
    region        text,
    quotas        jsonb NOT NULL DEFAULT '{}'::jsonb,
    contact_name  text,
    contact_phone text,
    notes         text,
    version       bigint NOT NULL DEFAULT 0,
    created_by    text NOT NULL DEFAULT 'system',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_code CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$')
);

-- 实例码与授权书一一对应：重复实例码会让"一张授权书开两家院"成立，必须唯一
CREATE UNIQUE INDEX IF NOT EXISTS ux_tenant_instance_code ON auth_svc.tenant (lower(instance_code));
CREATE INDEX IF NOT EXISTS ix_tenant_status ON auth_svc.tenant (status, created_at DESC);

COMMENT ON TABLE auth_svc.tenant IS
    '租户（医院）台账：只存目录元数据与控制面信息，不含任何患者数据；一租户一 Cell';
COMMENT ON COLUMN auth_svc.tenant.instance_code IS
    '部署实例码，与授权书 license_svc.lic_license.instance_code 一致；令牌携带同名声明';
COMMENT ON COLUMN auth_svc.tenant.cell_endpoint IS
    '该院独立入口（DNS/ingress），网关按 Host 映射到本租户';
COMMENT ON COLUMN auth_svc.tenant.db_host IS
    '该 Cell 的数据库主机（仅运维可见，用于开通与排障；不在临床读路径使用）';
COMMENT ON COLUMN auth_svc.tenant.status IS
    'PENDING 待开通 / ACTIVE 在用 / SUSPENDED 停用（禁止登录，数据保留）/ CLOSED 注销';

-- ---- 存量实例注册为首个租户（幂等）----
INSERT INTO auth_svc.tenant (code, instance_code, name, edition_code, status,
                             cell_endpoint, region, notes, created_by)
VALUES ('tenant-001', 'MEDASSIST-DEV-0001', '本地演示院区', 'ENTERPRISE', 'ACTIVE',
        'http://127.0.0.1:8080', 'local',
        'ADR-001：存量单实例注册为首个租户，不迁移数据', 'system')
ON CONFLICT (code) DO NOTHING;

-- ---- 账号归属租户 ----
ALTER TABLE auth_svc.app_user ADD COLUMN IF NOT EXISTS tenant_code text;
UPDATE auth_svc.app_user SET tenant_code = 'tenant-001' WHERE tenant_code IS NULL;
ALTER TABLE auth_svc.app_user ALTER COLUMN tenant_code SET DEFAULT 'tenant-001';
ALTER TABLE auth_svc.app_user ALTER COLUMN tenant_code SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_tenant'
    ) THEN
        ALTER TABLE auth_svc.app_user
            ADD CONSTRAINT fk_app_user_tenant
            FOREIGN KEY (tenant_code) REFERENCES auth_svc.tenant (code) ON DELETE RESTRICT;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_app_user_tenant ON auth_svc.app_user (tenant_code, status);

-- ---- 用户名唯一性从"全平台"收敛到"租户内" ----
-- 两家医院都有 admin01 是常态；登录时先由入口 Host 定位租户，再在该租户内查账号，
-- 因此不会产生歧义。全局唯一会让第二家医院被迫改工号，属于不可接受的上线摩擦。
DROP INDEX IF EXISTS auth_svc.ux_app_user_username;
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_user_tenant_username
    ON auth_svc.app_user (tenant_code, lower(username));

-- ---- 租户权限点 ----
--   tenant:read        查看租户（非平台运营只能看到自己那一条）
--   tenant:self:manage 维护本租户联系人/备注（租户管理员）
--   tenant:manage      跨租户开通/改配/停用（厂商平台运营，唯一可跨租户的角色）
INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tenant:read',        '租户查看',           'tenant'),
    ('tenant:self:manage', '本租户资料维护',      'tenant'),
    ('tenant:manage',      '租户开通与停用',      'tenant')
ON CONFLICT (code) DO NOTHING;

-- 平台运营角色：只有它持有 tenant:manage。刻意不给 ADMIN——
-- 医院管理员若能停用别家租户，等于把可用性风险交给了不可信租户。
INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('PLATFORM_OPERATOR', '平台运营', '租户开通、实例编排与停用；仅厂商运营持有', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'tenant:read'),
    ('ADMIN', 'tenant:self:manage'),
    ('PLATFORM_OPERATOR', 'tenant:read'),
    ('PLATFORM_OPERATOR', 'tenant:manage')
ON CONFLICT DO NOTHING;
