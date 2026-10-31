-- ============================================================================
-- V21：医保 DRG/DIP 分组配置权限（N5 第二批 C）
--
-- 分组方案/权重/费率直接影响预算测算与科室考核口径，属于"支付政策配置"，
-- 只授予医保办与管理员；普通医师/窗口只能做模拟测算（reads settlement:read）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:grouping:manage', '医保 DRG/DIP 分组配置', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',               'insurance:grouping:manage'),
    ('INSURANCE_OFFICER',   'insurance:grouping:manage')
ON CONFLICT DO NOTHING;

DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'insurance:grouping:manage'
   AND role_code NOT IN ('ADMIN', 'INSURANCE_OFFICER');
