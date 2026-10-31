-- ============================================================================
-- V9：住院费用看板/欠费预警 + 护理执行记录权限
--
-- 岗位划分：
--   * 费用看板与欠费预警：住院处（REGISTRAR）、管床医师（DOCTOR）、护士站（NURSE）都需要看，
--     但只有住院处与管理员能调整预交金（earlier: inpatient:deposit:manage）；
--   * 护理执行记录：护士（NURSE）执行并签名，医师/住院处只读（看板里体现）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:finance:read',    '住院费用看板/欠费预警', 'inpatient'),
    ('inpatient:nursing:execute', '护理执行记录',         'inpatient')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
ON CONFLICT DO NOTHING;

-- DOCTOR：看费用看板（不写护理执行）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'inpatient:finance:read')
ON CONFLICT DO NOTHING;

-- NURSE：写护理执行 + 看费用看板
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'inpatient:nursing:execute'),
    ('NURSE', 'inpatient:finance:read')
ON CONFLICT DO NOTHING;

-- REGISTRAR（住院处）：看费用看板
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'inpatient:finance:read')
ON CONFLICT DO NOTHING;
