-- ============================================================================
-- V62：静配中心权限（G34）
--   pharmacy:pivas:read     查看静配单与成品输液追溯（医生/药师/质控/管理员）
--   pharmacy:pivas:prepare  建单与配置（药师/管理员；配置会扣批次库存）
--   pharmacy:pivas:check    复核与驳回（药师/管理员；复核人不得等于配置人）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:pivas:read', '静配单与成品输液追溯查看', 'pharmacy'),
    ('pharmacy:pivas:prepare', '静配建单与配置', 'pharmacy'),
    ('pharmacy:pivas:check', '静配复核与驳回', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:pivas:read'),
    ('ADMIN', 'pharmacy:pivas:prepare'),
    ('ADMIN', 'pharmacy:pivas:check'),
    ('PHARMACIST', 'pharmacy:pivas:read'),
    ('PHARMACIST', 'pharmacy:pivas:prepare'),
    ('PHARMACIST', 'pharmacy:pivas:check'),
    ('DOCTOR', 'pharmacy:pivas:read'),
    ('QC', 'pharmacy:pivas:read')
ON CONFLICT DO NOTHING;
