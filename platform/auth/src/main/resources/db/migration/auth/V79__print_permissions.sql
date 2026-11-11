-- ============================================================================
-- V79：单据打印留痕权限（第 230 批）
--   clinical:print:record   记录一次打印（所有能打印的业务岗位）
--   clinical:print:read     查询打印历史（质控 / 管理员，用于补打核查）
--
-- 为什么 record 给了几乎所有临床岗位：打印动作本身已由各单据入口的权限点判定
-- （票据、处方、输液卡、费用清单、腕带、同意书各不相同），留痕只是"确实打了"的
-- 追加记录；若这里再收紧，会让已经出纸的动作在留痕上报时 403，形成假告警。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:print:record', '单据打印留痕记录', 'clinical'),
    ('clinical:print:read', '单据打印历史查询', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:print:record'),
    ('ADMIN', 'clinical:print:read'),
    ('DOCTOR', 'clinical:print:record'),
    ('REGISTRAR', 'clinical:print:record'),
    ('NURSE', 'clinical:print:record'),
    ('NURSE_MANAGER', 'clinical:print:record'),
    ('PHARMACIST', 'clinical:print:record'),
    ('TECHNICIAN', 'clinical:print:record'),
    ('QC', 'clinical:print:record'),
    ('QC', 'clinical:print:read')
ON CONFLICT DO NOTHING;
