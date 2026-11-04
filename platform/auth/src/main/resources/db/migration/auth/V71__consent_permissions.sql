-- ============================================================================
-- V71：电子知情同意书权限（G44）
--   emr:consent:read    同意书与模板查看
--   emr:consent:write   创建同意书
--   emr:consent:sign    患者/家属签署（纸笔登记或 CA）
--   emr:consent:revoke  撤销已签同意书
--   emr:consent:manage  模板维护/发布/停用
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:consent:read', '知情同意书查看', 'emr'),
    ('emr:consent:write', '知情同意书创建', 'emr'),
    ('emr:consent:sign', '知情同意书签署', 'emr'),
    ('emr:consent:revoke', '知情同意书撤销', 'emr'),
    ('emr:consent:manage', '知情同意书模板维护', 'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:consent:read'),
    ('ADMIN', 'emr:consent:write'),
    ('ADMIN', 'emr:consent:sign'),
    ('ADMIN', 'emr:consent:revoke'),
    ('ADMIN', 'emr:consent:manage'),
    ('DOCTOR', 'emr:consent:read'),
    ('DOCTOR', 'emr:consent:write'),
    ('DOCTOR', 'emr:consent:sign'),
    ('NURSE', 'emr:consent:read'),
    ('NURSE', 'emr:consent:sign'),
    ('QC', 'emr:consent:read')
ON CONFLICT DO NOTHING;
