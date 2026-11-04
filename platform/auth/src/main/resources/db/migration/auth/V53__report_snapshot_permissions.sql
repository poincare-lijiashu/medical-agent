-- ============================================================================
-- V53：运营日快照权限点（G17）
--
-- 读：管理/临床/质控岗位回看历史快照；
-- 写：手动补采只授予 ADMIN（日常采集由服务内定时任务完成）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:report:snapshot:read',  '查看运营日快照', 'clinical'),
    ('clinical:report:snapshot:write', '手动补采运营日快照', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',   'clinical:report:snapshot:read'),
    ('ADMIN',   'clinical:report:snapshot:write'),
    ('DOCTOR',  'clinical:report:snapshot:read'),
    ('NURSE',   'clinical:report:snapshot:read'),
    ('QC',      'clinical:report:snapshot:read'),
    ('REGISTRAR', 'clinical:report:snapshot:read')
ON CONFLICT DO NOTHING;
