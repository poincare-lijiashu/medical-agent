-- ============================================================================
-- V36：会诊权限
--
-- clinical:consultation:read  —— 查会诊收件箱、按就诊查会诊记录
-- clinical:consultation:write —— 发起 / 受理 / 拒绝 / 完成 / 撤回
--
-- 授权面与大原则一致：会诊是医师行为；护士与质控只读（需要看会诊落实与意见），
-- 管理员兜底。药师、挂号收费员与会诊无关，不授予。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:consultation:read',  '查看会诊申请与会诊意见', 'clinical'),
    ('clinical:consultation:write', '发起、受理、拒绝、完成与撤回会诊', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',  'clinical:consultation:read'),
    ('DOCTOR', 'clinical:consultation:read'),
    ('NURSE',  'clinical:consultation:read'),
    ('QC',     'clinical:consultation:read'),
    ('ADMIN',  'clinical:consultation:write'),
    ('DOCTOR', 'clinical:consultation:write')
ON CONFLICT DO NOTHING;
