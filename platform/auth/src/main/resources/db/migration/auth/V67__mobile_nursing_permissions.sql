-- ============================================================================
-- V67：移动护理与条码闭环权限（G39）
--   mobile:nursing:read     移动护理任务与扫码记录查看
--   mobile:nursing:execute  腕带/药品/标本扫码核对与异常登记
--   mobile:nursing:manage   从医嘱生成任务、手工建单
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('mobile:nursing:read', '移动护理任务与扫码记录查看', 'mobile'),
    ('mobile:nursing:execute', '移动护理扫码核对与异常登记', 'mobile'),
    ('mobile:nursing:manage', '移动护理任务生成与手工建单', 'mobile')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'mobile:nursing:read'),
    ('ADMIN', 'mobile:nursing:execute'),
    ('ADMIN', 'mobile:nursing:manage'),
    ('NURSE', 'mobile:nursing:read'),
    ('NURSE', 'mobile:nursing:execute'),
    ('NURSE_MANAGER', 'mobile:nursing:read'),
    ('NURSE_MANAGER', 'mobile:nursing:execute'),
    ('NURSE_MANAGER', 'mobile:nursing:manage'),
    ('DOCTOR', 'mobile:nursing:read'),
    ('QC', 'mobile:nursing:read')
ON CONFLICT DO NOTHING;
