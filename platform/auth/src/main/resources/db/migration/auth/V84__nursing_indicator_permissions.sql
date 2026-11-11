-- ============================================================================
-- V84：护理敏感质量指标权限（第 266 批）
--
--   inpatient:nursing:indicator         不良事件录入、设备日录入、报表生成/提交
--   inpatient:nursing:indicator:review  报表审核（通过/退回）
--
-- 为什么审核单独授权：上报数据的审核是质量管理部门/护理部的职责，
-- 病区护士长可以录入与提交，但不能自己审自己提交的数据（服务层另有职责分离校验）。
-- 查询沿用 inpatient:nursing:read。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:nursing:indicator', '护理敏感指标录入与上报', 'inpatient'),
    ('inpatient:nursing:indicator:review', '护理敏感指标上报审核', 'inpatient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'inpatient:nursing:indicator'),
    ('ADMIN', 'inpatient:nursing:indicator:review'),
    ('NURSE_MANAGER', 'inpatient:nursing:indicator'),
    ('NURSE_MANAGER', 'inpatient:nursing:indicator:review')
ON CONFLICT DO NOTHING;
