-- ============================================================================
-- V88：历史数据迁移权限（第 270 批）
--
--   migration:read       查看目标/模板/批次/对账/审计
--   migration:write      维护列映射档案、上传并预检批次
--   migration:apply      应用批次（真正写业务历史数据）
--   migration:rollback   回滚批次（反向恢复）
--   migration:audit:read 查询迁移审计
--
-- 角色切分：
--   MIGRATION_ENGINEER（实施工程师）：read/write/apply —— 负责把数据搬进来；
--   DATA_GOVERNOR（数据治理员）：read/rollback/audit:read —— 负责复核与兜底撤销。
--   "能导入"和"能撤销"分离，避免一次误操作被同一个人立即掩盖。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('MIGRATION_ENGINEER', '迁移实施工程师', '维护映射档案、上传预检并应用历史数据迁移批次', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('migration:read', '迁移目标/模板/批次/对账查询', 'migration'),
    ('migration:write', '迁移映射档案与批次预检', 'migration'),
    ('migration:apply', '迁移批次应用', 'migration'),
    ('migration:rollback', '迁移批次回滚', 'migration'),
    ('migration:audit:read', '迁移审计查询', 'migration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'migration:read'),
    ('ADMIN', 'migration:write'),
    ('ADMIN', 'migration:apply'),
    ('ADMIN', 'migration:rollback'),
    ('ADMIN', 'migration:audit:read'),
    ('MIGRATION_ENGINEER', 'migration:read'),
    ('MIGRATION_ENGINEER', 'migration:write'),
    ('MIGRATION_ENGINEER', 'migration:apply'),
    ('DATA_GOVERNOR', 'migration:read'),
    ('DATA_GOVERNOR', 'migration:rollback'),
    ('DATA_GOVERNOR', 'migration:audit:read'),
    -- 集成运维只读：上线对账时需要核对批次与行结果
    ('INTEGRATION', 'migration:read')
ON CONFLICT DO NOTHING;
