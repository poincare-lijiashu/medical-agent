-- ============================================================================
-- V57：医保实时结算权限点（G22）
-- 提交/重试属对外出账动作，授予医保办与管理员；窗口只读。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:realtime:write', '提交/重试医保实时结算', 'insurance'),
    ('insurance:realtime:read',  '医保实时结算查询',       'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',             'insurance:realtime:write'),
    ('ADMIN',             'insurance:realtime:read'),
    ('INSURANCE_OFFICER', 'insurance:realtime:write'),
    ('INSURANCE_OFFICER', 'insurance:realtime:read'),
    ('REGISTRAR',         'insurance:realtime:read')
ON CONFLICT DO NOTHING;
