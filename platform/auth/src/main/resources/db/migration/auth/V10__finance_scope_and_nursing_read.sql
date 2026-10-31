-- ============================================================================
-- V10：第二批安全整改（数据范围与护理文书读取权限）
--
-- 背景：GET /clinical/inpatient/alerts 不带 wardId 时等于"全院欠费患者清单"，
-- 该接口此前只要求 inpatient:finance:read（REGISTRAR/NURSE/DOCTOR 均有），
-- 任一病区护士即可枚举全院欠费患者。本次把"全院视图"拆成独立权限点，
-- 默认只授予 ADMIN；病区级查询仍用 inpatient:finance:read。
--
-- 同时把护理执行记录（属护理文书）的读取从 inpatient:read 收敛到
-- inpatient:nursing:read，避免挂号员等角色读到护理签名文书。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:finance:read:all', '全院费用视图/欠费预警（跨病区）', 'inpatient'),
    ('inpatient:nursing:read',     '护理执行记录查询',                'inpatient')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：两个新权限都授予
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
WHERE p.code IN ('inpatient:finance:read:all', 'inpatient:nursing:read')
ON CONFLICT DO NOTHING;

-- DOCTOR / NURSE：需要看护理执行记录（医嘱执行核对、护理文书）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'inpatient:nursing:read'),
    ('NURSE',  'inpatient:nursing:read')
ON CONFLICT DO NOTHING;

-- 说明：REGISTRAR（住院处）可看病区级欠费（inpatient:finance:read，见 V9），
-- 但不授予全院视图，也不授予护理文书读取。
