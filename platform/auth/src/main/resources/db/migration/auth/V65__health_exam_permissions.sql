-- ============================================================================
-- V65：体检中心权限（G37）
--   health:package:read        体检套餐查看
--   health:package:manage      套餐创建/修改/发布/停用
--   health:registration:read   体检登记与报告查看
--   health:registration:write  体检登记与取消
--   health:exam:execute        分科检查结果录入/未检登记
--   health:report:manage       总检报告草稿与出具
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('HEALTH_EXAM', '体检中心', '体检套餐、登记、分科检查与总检报告', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('health:package:read', '体检套餐查看', 'health'),
    ('health:package:manage', '体检套餐维护', 'health'),
    ('health:registration:read', '体检登记与报告查看', 'health'),
    ('health:registration:write', '体检登记与取消', 'health'),
    ('health:exam:execute', '分科检查结果录入', 'health'),
    ('health:report:manage', '总检报告草稿与出具', 'health')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'health:package:read'),
    ('ADMIN', 'health:package:manage'),
    ('ADMIN', 'health:registration:read'),
    ('ADMIN', 'health:registration:write'),
    ('ADMIN', 'health:exam:execute'),
    ('ADMIN', 'health:report:manage'),
    ('HEALTH_EXAM', 'health:package:read'),
    ('HEALTH_EXAM', 'health:package:manage'),
    ('HEALTH_EXAM', 'health:registration:read'),
    ('HEALTH_EXAM', 'health:registration:write'),
    ('HEALTH_EXAM', 'health:exam:execute'),
    ('HEALTH_EXAM', 'health:report:manage'),
    ('DOCTOR', 'health:package:read'),
    ('DOCTOR', 'health:registration:read'),
    ('DOCTOR', 'health:exam:execute'),
    ('DOCTOR', 'health:report:manage'),
    ('NURSE', 'health:package:read'),
    ('NURSE', 'health:registration:read'),
    ('NURSE', 'health:registration:write'),
    ('NURSE', 'health:exam:execute'),
    ('NURSE_MANAGER', 'health:package:read'),
    ('NURSE_MANAGER', 'health:registration:read'),
    ('NURSE_MANAGER', 'health:registration:write'),
    ('TECHNICIAN', 'health:package:read'),
    ('TECHNICIAN', 'health:registration:read'),
    ('TECHNICIAN', 'health:exam:execute'),
    ('REGISTRAR', 'health:package:read'),
    ('REGISTRAR', 'health:registration:read'),
    ('REGISTRAR', 'health:registration:write'),
    ('QC', 'health:package:read'),
    ('QC', 'health:registration:read')
ON CONFLICT DO NOTHING;
