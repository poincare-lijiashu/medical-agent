-- ============================================================================
-- V68：输血闭环权限（G40）
--   transfusion:request:read/write  输血申请查看/开立与取消
--   transfusion:crossmatch:manage   配血记录与结果
--   transfusion:issue:manage        发血
--   transfusion:execute             输注双人核对、开始/完成、不良反应上报
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('BLOOD_BANK', '输血科', '配血、发血与血袋追溯', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('transfusion:request:read', '输血申请查看', 'transfusion'),
    ('transfusion:request:write', '输血申请开立与取消', 'transfusion'),
    ('transfusion:crossmatch:manage', '配血记录与结果', 'transfusion'),
    ('transfusion:issue:manage', '输血发血', 'transfusion'),
    ('transfusion:execute', '输注双人核对与不良反应上报', 'transfusion')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'transfusion:request:read'),
    ('ADMIN', 'transfusion:request:write'),
    ('ADMIN', 'transfusion:crossmatch:manage'),
    ('ADMIN', 'transfusion:issue:manage'),
    ('ADMIN', 'transfusion:execute'),
    ('DOCTOR', 'transfusion:request:read'),
    ('DOCTOR', 'transfusion:request:write'),
    ('NURSE', 'transfusion:request:read'),
    ('NURSE', 'transfusion:execute'),
    ('NURSE_MANAGER', 'transfusion:request:read'),
    ('NURSE_MANAGER', 'transfusion:execute'),
    ('BLOOD_BANK', 'transfusion:request:read'),
    ('BLOOD_BANK', 'transfusion:crossmatch:manage'),
    ('BLOOD_BANK', 'transfusion:issue:manage'),
    ('QC', 'transfusion:request:read')
ON CONFLICT DO NOTHING;
