-- ============================================================================
-- V8：住院费用（按日计费）与预交金权限
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:billing:daily', '住院按日计费',   'inpatient'),
    ('inpatient:deposit:manage', '预交金存入/退还', 'inpatient')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
ON CONFLICT DO NOTHING;

-- REGISTRAR（住院处/收费窗口）：按日计费 + 预交金
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'inpatient:billing:daily'),
    ('REGISTRAR', 'inpatient:deposit:manage')
ON CONFLICT DO NOTHING;
