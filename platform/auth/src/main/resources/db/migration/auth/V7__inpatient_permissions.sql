-- ============================================================================
-- V7：住院域（ADT）权限点与角色映射
--
-- 岗位划分：住院处/医师办入院与出院；护理部负责分床/转床；病区与床位主数据由护理部/管理员维护。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:read',                '查询住院信息',       'inpatient'),
    ('inpatient:admission:create',    '入院登记',           'inpatient'),
    ('inpatient:admission:discharge', '出院办理',           'inpatient'),
    ('inpatient:bed:assign',          '分床与转床',         'inpatient'),
    ('inpatient:ward:manage',         '病区与床位维护',     'inpatient')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
ON CONFLICT DO NOTHING;

-- DOCTOR：查住院 + 办入院/出院（开医嘱沿用 clinical:order:create）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'inpatient:read'),
    ('DOCTOR', 'inpatient:admission:create'),
    ('DOCTOR', 'inpatient:admission:discharge')
ON CONFLICT DO NOTHING;

-- NURSE：查住院 + 分床/转床
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'inpatient:read'),
    ('NURSE', 'inpatient:bed:assign')
ON CONFLICT DO NOTHING;

-- REGISTRAR（住院处）：查住院 + 入院登记
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'inpatient:read'),
    ('REGISTRAR', 'inpatient:admission:create')
ON CONFLICT DO NOTHING;
