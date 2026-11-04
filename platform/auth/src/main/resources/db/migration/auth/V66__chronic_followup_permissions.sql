-- ============================================================================
-- V66：随访与慢病管理权限（G38）
--   chronic:record:read       慢病档案/随访记录/趋势查看
--   chronic:record:manage     建档、风险分级、结案
--   chronic:plan:manage       随访计划创建与停止
--   chronic:followup:read     随访任务查看
--   chronic:followup:execute  随访任务完成与记录录入
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('CHRONIC_CARE', '慢病管理', '慢病建档、随访计划、随访执行与指标趋势', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('chronic:record:read', '慢病档案与随访查看', 'chronic'),
    ('chronic:record:manage', '慢病建档、风险分级与结案', 'chronic'),
    ('chronic:plan:manage', '随访计划创建与停止', 'chronic'),
    ('chronic:followup:read', '随访任务查看', 'chronic'),
    ('chronic:followup:execute', '随访任务完成与记录录入', 'chronic')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'chronic:record:read'),
    ('ADMIN', 'chronic:record:manage'),
    ('ADMIN', 'chronic:plan:manage'),
    ('ADMIN', 'chronic:followup:read'),
    ('ADMIN', 'chronic:followup:execute'),
    ('CHRONIC_CARE', 'chronic:record:read'),
    ('CHRONIC_CARE', 'chronic:record:manage'),
    ('CHRONIC_CARE', 'chronic:plan:manage'),
    ('CHRONIC_CARE', 'chronic:followup:read'),
    ('CHRONIC_CARE', 'chronic:followup:execute'),
    ('DOCTOR', 'chronic:record:read'),
    ('DOCTOR', 'chronic:record:manage'),
    ('DOCTOR', 'chronic:plan:manage'),
    ('DOCTOR', 'chronic:followup:read'),
    ('DOCTOR', 'chronic:followup:execute'),
    ('NURSE', 'chronic:record:read'),
    ('NURSE', 'chronic:followup:read'),
    ('NURSE', 'chronic:followup:execute'),
    ('NURSE_MANAGER', 'chronic:record:read'),
    ('NURSE_MANAGER', 'chronic:record:manage'),
    ('NURSE_MANAGER', 'chronic:plan:manage'),
    ('NURSE_MANAGER', 'chronic:followup:read'),
    ('NURSE_MANAGER', 'chronic:followup:execute'),
    ('QC', 'chronic:record:read'),
    ('QC', 'chronic:followup:read'),
    ('REGISTRAR', 'chronic:record:read'),
    ('REGISTRAR', 'chronic:followup:read')
ON CONFLICT DO NOTHING;
