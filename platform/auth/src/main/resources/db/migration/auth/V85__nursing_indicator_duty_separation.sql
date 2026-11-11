-- ============================================================================
-- V85：护理敏感指标权限细化（第 266 批）
--
-- 把"录入"与"上报"拆开，并新增护理质量管理角色：
--   inpatient:nursing:indicator          不良事件/设备日录入（责任护士）
--   inpatient:nursing:indicator:manage   周期报表生成与提交（护士长）
--   inpatient:nursing:indicator:review   报表审核（护理质量管理/质控）
--
-- 为什么拆：一线护士需要随时上报不良事件；周期报表的生成与提交属于病区管理动作；
-- 审核属于质量管理部门职责。三者混成一个权限会导致"谁都能报、谁都能审"，
-- 既不符合职责分离，也会让病区不敢如实上报。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('NURSING_QUALITY', '护理质量管理', '护理敏感指标审核与质量分析', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:nursing:indicator:manage', '护理指标报表生成与提交', 'inpatient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'inpatient:nursing:indicator:manage'),
    ('NURSE_MANAGER', 'inpatient:nursing:indicator:manage'),
    ('NURSE', 'inpatient:nursing:indicator'),
    ('NURSING_QUALITY', 'inpatient:nursing:read'),
    ('NURSING_QUALITY', 'inpatient:nursing:indicator:review')
ON CONFLICT DO NOTHING;
