-- ============================================================================
-- V52：住院周转报表权限点（G16）
--
-- 全院口径的住院入/出/在院趋势，与病区级 inpatient:read 分开：
-- 报表不返回患者标识，但仍限制为管理岗与临床医护查看。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:report:read', '住院周转趋势报表', 'inpatient')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', 'inpatient:report:read'
ON CONFLICT DO NOTHING;

-- DOCTOR / NURSE / REGISTRAR：临床与管理岗位可按全院口径查看周转趋势
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'inpatient:report:read'),
    ('NURSE', 'inpatient:report:read'),
    ('REGISTRAR', 'inpatient:report:read')
ON CONFLICT DO NOTHING;
