-- ============================================================================
-- V64：急诊分诊与留观权限（G36）
--   emergency:triage:read        分诊队列、看板、床位与留观查询（医护/挂号/质控）
--   emergency:triage:create      预检分诊录入（护士/护士长/医师）
--   emergency:triage:register    急诊挂号（挂号员/护士/医师）
--   emergency:observation:manage 留观入床、转归办理（医师/护士/护士长）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emergency:triage:read', '急诊分诊与留观查看', 'emergency'),
    ('emergency:triage:create', '急诊预检分诊录入', 'emergency'),
    ('emergency:triage:register', '急诊挂号', 'emergency'),
    ('emergency:observation:manage', '急诊留观入床与转归办理', 'emergency')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emergency:triage:read'),
    ('ADMIN', 'emergency:triage:create'),
    ('ADMIN', 'emergency:triage:register'),
    ('ADMIN', 'emergency:observation:manage'),
    ('DOCTOR', 'emergency:triage:read'),
    ('DOCTOR', 'emergency:triage:create'),
    ('DOCTOR', 'emergency:triage:register'),
    ('DOCTOR', 'emergency:observation:manage'),
    ('NURSE', 'emergency:triage:read'),
    ('NURSE', 'emergency:triage:create'),
    ('NURSE', 'emergency:triage:register'),
    ('NURSE', 'emergency:observation:manage'),
    ('NURSE_MANAGER', 'emergency:triage:read'),
    ('NURSE_MANAGER', 'emergency:triage:create'),
    ('NURSE_MANAGER', 'emergency:observation:manage'),
    ('REGISTRAR', 'emergency:triage:read'),
    ('REGISTRAR', 'emergency:triage:register'),
    ('QC', 'emergency:triage:read')
ON CONFLICT DO NOTHING;
