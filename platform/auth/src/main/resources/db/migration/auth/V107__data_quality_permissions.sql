-- ============================================================================
-- V107：数据质量与指标稽核权限（§299 / 计划书 G4）
--
--   mdm:quality:read    质量规则、执行记录、问题与看板查询
--   mdm:quality:run     手动执行单条/全部质量规则
--   mdm:quality:manage  维护规则、确认与解决质量问题
--   豁免质量问题复用 mdm:approve（数据治理负责人），避免"自己开的单自己豁免"。
--
-- 角色：
--   DATA_STEWARD（数据管家）：定义/执行/维护规则；
--   DATA_GOVERNOR（数据治理负责人）：只读+执行+豁免（mdm:approve 已在 V69 授予）；
--   QC：只读+执行；OPS_MANAGER / INTEGRATION：只读。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('mdm:quality:read', '数据质量查询与看板', 'mdm'),
    ('mdm:quality:run', '数据质量规则执行', 'mdm'),
    ('mdm:quality:manage', '数据质量规则与问题维护', 'mdm')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'mdm:quality:read'),
    ('ADMIN', 'mdm:quality:run'),
    ('ADMIN', 'mdm:quality:manage'),
    ('DATA_STEWARD', 'mdm:quality:read'),
    ('DATA_STEWARD', 'mdm:quality:run'),
    ('DATA_STEWARD', 'mdm:quality:manage'),
    ('DATA_GOVERNOR', 'mdm:quality:read'),
    ('DATA_GOVERNOR', 'mdm:quality:run'),
    ('QC', 'mdm:quality:read'),
    ('QC', 'mdm:quality:run'),
    ('OPS_MANAGER', 'mdm:quality:read'),
    ('INTEGRATION', 'mdm:quality:read')
ON CONFLICT DO NOTHING;
