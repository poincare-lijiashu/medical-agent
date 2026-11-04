-- ============================================================================
-- V54：药事/物资运营日快照权限点（G18）
--
-- 读：药师、护士、质控与管理员回看历史快照；
-- 写：手动补采只授予 ADMIN（日常采集由服务内定时任务完成）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:report:snapshot:read',  '查看药事日快照', 'pharmacy'),
    ('pharmacy:report:snapshot:write', '手动补采药事日快照', 'pharmacy'),
    ('material:report:snapshot:read',  '查看物资日快照', 'material'),
    ('material:report:snapshot:write', '手动补采物资日快照', 'material')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',      'pharmacy:report:snapshot:read'),
    ('ADMIN',      'pharmacy:report:snapshot:write'),
    ('PHARMACIST', 'pharmacy:report:snapshot:read'),
    ('QC',         'pharmacy:report:snapshot:read'),
    ('ADMIN',      'material:report:snapshot:read'),
    ('ADMIN',      'material:report:snapshot:write'),
    ('PHARMACIST', 'material:report:snapshot:read'),
    ('NURSE',      'material:report:snapshot:read'),
    ('QC',         'material:report:snapshot:read')
ON CONFLICT DO NOTHING;
