-- ============================================================================
-- V60：出院带药权限点（G30）
--   inpatient:discharge-med:read   查看出院带药（医生/护士/登记员/质控/护士长/管理员）
--   inpatient:discharge-med:write  开立、提交药房、同步与取消（医生/登记员/管理员）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:discharge-med:read', '出院带药查看', 'inpatient'),
    ('inpatient:discharge-med:write', '出院带药开立与提交', 'inpatient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'inpatient:discharge-med:read'),
    ('ADMIN', 'inpatient:discharge-med:write'),
    ('DOCTOR', 'inpatient:discharge-med:read'),
    ('DOCTOR', 'inpatient:discharge-med:write'),
    ('REGISTRAR', 'inpatient:discharge-med:read'),
    ('REGISTRAR', 'inpatient:discharge-med:write'),
    ('NURSE', 'inpatient:discharge-med:read'),
    ('NURSE_MANAGER', 'inpatient:discharge-med:read'),
    ('QC', 'inpatient:discharge-med:read')
ON CONFLICT DO NOTHING;
