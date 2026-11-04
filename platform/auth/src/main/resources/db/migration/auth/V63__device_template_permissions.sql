-- ============================================================================
-- V63：设备质控与结构化报告模板权限（G35）
--   tech:device:read              设备档案与质控查看（技师/质控/管理员）
--   tech:device:write             设备建档 / 状态变更 / 质控登记（技师/管理员）
--   tech:report-template:read     结构化模板查看（技师/管理员）
--   tech:report-template:manage   模板创建 / 发布 / 停用（技师/管理员）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:device:read', '设备档案与质控查看', 'tech'),
    ('tech:device:write', '设备建档、状态变更与质控登记', 'tech'),
    ('tech:report-template:read', '结构化报告模板查看', 'tech'),
    ('tech:report-template:manage', '结构化报告模板维护', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'tech:device:read'),
    ('ADMIN', 'tech:device:write'),
    ('ADMIN', 'tech:report-template:read'),
    ('ADMIN', 'tech:report-template:manage'),
    ('TECHNICIAN', 'tech:device:read'),
    ('TECHNICIAN', 'tech:device:write'),
    ('TECHNICIAN', 'tech:report-template:read'),
    ('TECHNICIAN', 'tech:report-template:manage'),
    ('QC', 'tech:device:read')
ON CONFLICT DO NOTHING;
