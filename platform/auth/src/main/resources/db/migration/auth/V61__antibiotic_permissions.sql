-- ============================================================================
-- V61：抗菌药物专项权限（G33）
--   pharmacy:antibiotic:read     查看分级使用统计与特殊级待审（医生/药师/质控/管理员）
--   pharmacy:antibiotic:approve  特殊使用级抗菌药物审批（药师/管理员，且不能审批自己开的方）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:antibiotic:read', '抗菌药物分级统计与待审查看', 'pharmacy'),
    ('pharmacy:antibiotic:approve', '特殊使用级抗菌药物审批', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:antibiotic:read'),
    ('ADMIN', 'pharmacy:antibiotic:approve'),
    ('PHARMACIST', 'pharmacy:antibiotic:read'),
    ('PHARMACIST', 'pharmacy:antibiotic:approve'),
    ('DOCTOR', 'pharmacy:antibiotic:read'),
    ('QC', 'pharmacy:antibiotic:read')
ON CONFLICT DO NOTHING;
