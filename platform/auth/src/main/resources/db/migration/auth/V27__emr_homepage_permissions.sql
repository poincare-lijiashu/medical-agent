-- ============================================================================
-- V27：病案首页权限点（HQMS / DRG 前置）
--
-- 岗位划分：
--   * 病案质控（QC）：生成、补录、提交、归档（病案室本职），并负责四眼归档；
--   * 医师/护士：只读（出院小结核对、打印）；
--   * 窗口（REGISTRAR）：只读（患者索取复印件时打印）；
--   * 管理员：全部。
--
-- 提交与归档由不同的人完成（服务层强制），因此不把"归档"合并进 write。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:homepage:read',    '病案首页查阅',   'emr'),
    ('emr:homepage:write',   '病案首页生成/补录/提交', 'emr'),
    ('emr:homepage:archive', '病案首页归档',   'emr')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部首页权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
 WHERE p.code IN ('emr:homepage:read', 'emr:homepage:write', 'emr:homepage:archive')
ON CONFLICT DO NOTHING;

-- QC（病案质控）：首页全流程
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'emr:homepage:read'),
    ('QC', 'emr:homepage:write'),
    ('QC', 'emr:homepage:archive')
ON CONFLICT DO NOTHING;

-- 医师/护士/窗口：只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'emr:homepage:read'),
    ('NURSE', 'emr:homepage:read'),
    ('REGISTRAR', 'emr:homepage:read')
ON CONFLICT DO NOTHING;
