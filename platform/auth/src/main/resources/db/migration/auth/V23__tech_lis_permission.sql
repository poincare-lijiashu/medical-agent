-- ============================================================================
-- V23：LIS 入站接口权限（N6 第一批）
--
-- LIS 结果回写是**机器对机器**动作：既要能独立授权给接口账号（不借用技师个人权限），
-- 又不能让普通只读账号触发报告写入。因此单列一个权限点。
--
-- 授予：ADMIN（管理）、TECHNICIAN（技师代传/补传场景）。不含 DOCTOR/NURSE/REGISTRAR。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:lis:write', 'LIS 结果回写（HL7 ORU）', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',      'tech:lis:write'),
    ('TECHNICIAN', 'tech:lis:write')
ON CONFLICT DO NOTHING;

DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'tech:lis:write'
   AND role_code NOT IN ('ADMIN', 'TECHNICIAN');
