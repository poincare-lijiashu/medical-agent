-- ============================================================================
-- V69：CA 电子签名权限（G41）
--   emr:ca:read     CA 签名台账查看
--   emr:ca:sign     病历签名时同时获取 CA 签名
--   emr:ca:verify   验签
--   emr:ca:manage   撤销 CA 签名
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:ca:read', 'CA 电子签名台账查看', 'emr'),
    ('emr:ca:sign', '病历签名时获取 CA 签名', 'emr'),
    ('emr:ca:verify', 'CA 电子签名验签', 'emr'),
    ('emr:ca:manage', 'CA 电子签名撤销', 'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:ca:read'),
    ('ADMIN', 'emr:ca:sign'),
    ('ADMIN', 'emr:ca:verify'),
    ('ADMIN', 'emr:ca:manage'),
    ('DOCTOR', 'emr:ca:read'),
    ('DOCTOR', 'emr:ca:sign'),
    ('QC', 'emr:ca:read'),
    ('QC', 'emr:ca:verify')
ON CONFLICT DO NOTHING;
