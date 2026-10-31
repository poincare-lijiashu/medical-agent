-- ============================================================================
-- V14：病案修改审批权限
--
-- 归档后修改采用"申请—审批"两段式：医师提交申请（复用 emr:record:amend），
-- 审批由病案质控人员执行（新增 emr:record:approve），并强制四眼原则
-- （申请人不得审批自己的申请，由服务端校验）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:record:approve', '病案修改审批', 'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:record:approve'),
    ('QC',    'emr:record:approve')
ON CONFLICT DO NOTHING;
