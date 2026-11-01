-- ============================================================================
-- V26：诊断权限点（结构化诊断 / ICD-10）
--
-- 医师负责下达与调整诊断；ADMIN 全量；其他角色暂不授予写权限（读权限给质控/病案）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:diagnosis:read',  '查询诊断',       'clinical'),
    ('clinical:diagnosis:write', '下达/调整诊断',  'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
 WHERE p.code IN ('clinical:diagnosis:read', 'clinical:diagnosis:write')
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'clinical:diagnosis:read'),
    ('DOCTOR', 'clinical:diagnosis:write'),
    ('QC', 'clinical:diagnosis:read'),
    ('NURSE', 'clinical:diagnosis:read')
ON CONFLICT DO NOTHING;
