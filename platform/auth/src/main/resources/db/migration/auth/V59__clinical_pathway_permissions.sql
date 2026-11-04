-- ============================================================================
-- V59：临床路径权限点（G29）
--   inpatient:pathway:read     查看模板与在径患者（医生/护士/登记员/质控/管理员）
--   inpatient:pathway:manage   维护模板、入径与退出路径（医生/登记员/管理员）
--   inpatient:pathway:execute  执行/跳过路径任务、结束路径（医生/护士/登记员/管理员）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('inpatient:pathway:read', '临床路径查看（模板与在径患者）', 'inpatient'),
    ('inpatient:pathway:manage', '临床路径维护（模板/入径/退出）', 'inpatient'),
    ('inpatient:pathway:execute', '临床路径任务执行（完成/跳过/结束）', 'inpatient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'inpatient:pathway:read'),
    ('ADMIN', 'inpatient:pathway:manage'),
    ('ADMIN', 'inpatient:pathway:execute'),
    ('DOCTOR', 'inpatient:pathway:read'),
    ('DOCTOR', 'inpatient:pathway:manage'),
    ('DOCTOR', 'inpatient:pathway:execute'),
    ('REGISTRAR', 'inpatient:pathway:read'),
    ('REGISTRAR', 'inpatient:pathway:manage'),
    ('REGISTRAR', 'inpatient:pathway:execute'),
    ('NURSE', 'inpatient:pathway:read'),
    ('NURSE', 'inpatient:pathway:execute'),
    ('NURSE_MANAGER', 'inpatient:pathway:read'),
    ('NURSE_MANAGER', 'inpatient:pathway:execute'),
    ('QC', 'inpatient:pathway:read')
ON CONFLICT DO NOTHING;
