-- ============================================================================
-- V15：医保域权限点与角色映射
--
-- 岗位划分：
--   * 医保办/收费窗口（REGISTRAR）：目录维护、参保登记、结算清单生成与确认；
--   * 医师/质控：只读（查看结算结果）；
--   * 管理员：全部。
-- 真实医保结算需省局前置机与数字证书，本服务只做本地目录/测算/留档。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:catalog:manage',    '医保目录维护',       'insurance'),
    ('insurance:member:manage',     '参保登记',           'insurance'),
    ('insurance:settlement:read',   '医保结算查询',       'insurance'),
    ('insurance:settlement:write',  '医保结算生成/确认',  'insurance')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p WHERE p.domain = 'insurance'
ON CONFLICT DO NOTHING;

-- REGISTRAR（收费/医保窗口）：全部医保权限
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'insurance:catalog:manage'),
    ('REGISTRAR', 'insurance:member:manage'),
    ('REGISTRAR', 'insurance:settlement:read'),
    ('REGISTRAR', 'insurance:settlement:write')
ON CONFLICT DO NOTHING;

-- DOCTOR / QC：只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'insurance:settlement:read'),
    ('QC',     'insurance:settlement:read')
ON CONFLICT DO NOTHING;
