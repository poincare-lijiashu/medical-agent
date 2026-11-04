-- ============================================================================
-- V58：护理排班权限点与护士长角色（G26）
-- 排班维护授予护士长与管理员；执行护士只读。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('NURSE_MANAGER', '护士长', '病区排班与护理管理', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:nursing:schedule', '护理排班维护（录入/发布/取消）', 'inpatient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'inpatient:nursing:schedule'),
    ('ADMIN', 'inpatient:nursing:read'),
    ('NURSE_MANAGER', 'inpatient:nursing:schedule'),
    ('NURSE_MANAGER', 'inpatient:nursing:read'),
    ('NURSE_MANAGER', 'inpatient:read')
ON CONFLICT DO NOTHING;
