-- ============================================================================
-- V55：报表订阅权限点（G19）
--
-- read：查看订阅与投递记录（管理/质控）；
-- manage：新建/修改订阅、手动补发（仅 ADMIN）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:report:subscription:read',   '查看报表订阅与投递记录', 'clinical'),
    ('clinical:report:subscription:manage', '管理报表订阅与手动补发', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:report:subscription:read'),
    ('ADMIN', 'clinical:report:subscription:manage'),
    ('QC',    'clinical:report:subscription:read')
ON CONFLICT DO NOTHING;
