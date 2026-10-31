-- ============================================================================
-- V24：运营指标查询权限（N7 第一批）
--
-- 报表只含汇总金额与人次，不含患者级明细；但仍属经营敏感数据（收入/药占比），
-- 因此单独授权：ADMIN（管理）、REGISTRAR（窗口对账）、DOCTOR（科主任看科室）。
-- 不含 NURSE / PHARMACIST / TECHNICIAN / QC。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:report:read', '运营指标查询', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',     'clinical:report:read'),
    ('REGISTRAR', 'clinical:report:read'),
    ('DOCTOR',    'clinical:report:read')
ON CONFLICT DO NOTHING;

DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'clinical:report:read'
   AND role_code NOT IN ('ADMIN', 'REGISTRAR', 'DOCTOR');
