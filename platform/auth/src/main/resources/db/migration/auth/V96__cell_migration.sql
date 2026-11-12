-- ============================================================================
-- V96：Cell 迁移台账（第 282 批 / ADR-001 P1 数据面隔离）
--
-- 物理隔离之后，最大的运维问题是"哪家跑到了哪版"：18 个服务 × N 家医院，
-- 没有台账就只能在故障时逐个连库确认。本表由控制面从各 Cell 的
-- flyway_schema_history 汇总而来，只记版本事实，不参与临床读路径。
--
-- 为什么不在 Cell 里记：Cell 随院交付后可能离线/被隔离，台账必须在控制面可查；
-- 且"谁落后于发布列车"是跨院视角的问题，单院库看不到别院。
-- ============================================================================

ALTER TABLE auth_svc.tenant
    ADD COLUMN IF NOT EXISTS db_name text NOT NULL DEFAULT 'medagent';

COMMENT ON COLUMN auth_svc.tenant.db_name IS
    '该 Cell 的业务库名（默认 medagent）；迁移台账与开通编排按它连库';

CREATE TABLE IF NOT EXISTS auth_svc.cell_migration (
    id           bigserial PRIMARY KEY,
    tenant_code  text NOT NULL REFERENCES auth_svc.tenant (code) ON DELETE RESTRICT,
    service      text NOT NULL,
    version      text NOT NULL,
    description  text,
    checksum     integer,
    installed_on timestamptz,
    success      boolean NOT NULL,
    recorded_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_cell_migration
    ON auth_svc.cell_migration (tenant_code, service, version);
CREATE INDEX IF NOT EXISTS ix_cell_migration_tenant
    ON auth_svc.cell_migration (tenant_code, service, installed_on DESC);

COMMENT ON TABLE auth_svc.cell_migration IS
    '各 Cell 的 Flyway 迁移事实（按租户+服务+版本唯一）：回答"哪家跑到哪版、是否有失败迁移"';
COMMENT ON COLUMN auth_svc.cell_migration.success IS
    'Flyway 记录的成败：失败的迁移必须暴露出来，不能只看"有行=已迁移"';
