-- ============================================================================
-- V25：影像（PACS）集成权限（N6 第二批）
--
-- 读：技师/医师/管理员（worklist 与检查进度查询）
-- 写：技师/管理员（登记 Study、回传检查状态）
-- 对接账号（PACS）走 TECHNICIAN 角色；不允许 NURSE / PHARMACIST / QC / REGISTRAR 写。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:pacs:read',  '影像检查查询', 'tech'),
    ('tech:pacs:write', '影像检查登记与回传', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',      'tech:pacs:read'),
    ('ADMIN',      'tech:pacs:write'),
    ('TECHNICIAN', 'tech:pacs:read'),
    ('TECHNICIAN', 'tech:pacs:write'),
    ('DOCTOR',     'tech:pacs:read')
ON CONFLICT DO NOTHING;

DELETE FROM auth_svc.role_permission
 WHERE permission_code IN ('tech:pacs:read', 'tech:pacs:write')
   AND role_code NOT IN ('ADMIN', 'TECHNICIAN', 'DOCTOR');

-- 写入权限只保留 ADMIN / TECHNICIAN（上面的 DELETE 会把 DOCTOR 的 write 也清掉）
DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'tech:pacs:write'
   AND role_code NOT IN ('ADMIN', 'TECHNICIAN');
