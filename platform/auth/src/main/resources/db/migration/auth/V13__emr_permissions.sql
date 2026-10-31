-- ============================================================================
-- V13：病历域权限点与角色映射
--
-- 岗位划分：
--   * 医师：书写、提交、签名、归档、修订；不能维护模板（避免"自己改模板放自己过"）；
--   * 质控科（QC）：模板维护 + 全量只读（质控抽查）；
--   * 护士：只读（查阅病历）；
--   * 管理员：全部权限。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:template:manage',  '病历模板维护',       'emr'),
    ('emr:record:read',      '病历查阅',           'emr'),
    ('emr:record:create',    '病历书写（草稿）',   'emr'),
    ('emr:record:submit',    '病历提交（质控）',   'emr'),
    ('emr:record:sign',      '病历签名',           'emr'),
    ('emr:record:archive',   '病历归档',           'emr'),
    ('emr:record:amend',     '病历修订',           'emr')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p WHERE p.domain = 'emr'
ON CONFLICT DO NOTHING;

-- DOCTOR：除模板维护外的全部病历权限
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'emr:record:read'),
    ('DOCTOR', 'emr:record:create'),
    ('DOCTOR', 'emr:record:submit'),
    ('DOCTOR', 'emr:record:sign'),
    ('DOCTOR', 'emr:record:archive'),
    ('DOCTOR', 'emr:record:amend')
ON CONFLICT DO NOTHING;

-- NURSE：只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'emr:record:read')
ON CONFLICT DO NOTHING;

-- QC（质控科）：模板维护 + 全量只读，不做书写与签名
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'emr:template:manage'),
    ('QC', 'emr:record:read')
ON CONFLICT DO NOTHING;

-- REGISTRAR（窗口）：只读（打印病历摘要等）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'emr:record:read')
ON CONFLICT DO NOTHING;
