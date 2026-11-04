-- ============================================================================
-- V56：电子票据推送权限点（G21）
-- 推送属于对外报送动作，授予收费岗位与管理员；查询沿用 clinical:charge:read。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:receipt:push', '推送电子票据到财政/第三方平台', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:receipt:push'),
    ('REGISTRAR', 'clinical:receipt:push')
ON CONFLICT DO NOTHING;
