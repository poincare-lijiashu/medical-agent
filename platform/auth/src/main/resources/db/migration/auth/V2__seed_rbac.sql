-- ============================================================================
-- RBAC 种子数据：内置角色、权限点、角色-权限映射（幂等，可重复执行）
-- 权限命名规范：域:资源:动作
-- ============================================================================

-- ---- 内置角色 ----
INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('ADMIN',      '系统管理员', '全院配置、账号与权限管理', true),
    ('DOCTOR',     '医师',       '门诊/住院诊疗、开方、会诊', true),
    ('NURSE',      '护士',       '执行医嘱、护理记录', true),
    ('PHARMACIST', '药师',       '处方审核与发药', true),
    ('QC',         '质控员',     '病历质控、病案审核', true)
ON CONFLICT (code) DO NOTHING;

-- ---- 权限点 ----
INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('auth:user:read',        '查询账号',           'auth'),
    ('auth:user:create',      '新建账号',           'auth'),
    ('auth:user:update',      '修改账号',           'auth'),
    ('auth:user:disable',     '停用账号',           'auth'),
    ('auth:role:read',        '查询角色',           'auth'),
    ('auth:role:assign',      '分配角色',           'auth'),
    ('empi:patient:read',     '查询患者',           'empi'),
    ('empi:patient:create',   '患者建档',           'empi'),
    ('empi:patient:update',   '修改患者信息',       'empi'),
    ('empi:patient:merge',    '患者档案合并',       'empi'),
    ('empi:patient:read_phi', '查看患者敏感信息',   'empi'),
    ('ai:assist:use',         '调用 AI 临床辅助',   'ai'),
    ('audit:log:read',        '查询审计日志',       'audit')
ON CONFLICT (code) DO NOTHING;

-- ---- 角色-权限映射 ----
-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
ON CONFLICT DO NOTHING;

-- DOCTOR：患者读/建/改 + 敏感信息 + AI 辅助
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'empi:patient:read'),
    ('DOCTOR', 'empi:patient:create'),
    ('DOCTOR', 'empi:patient:update'),
    ('DOCTOR', 'empi:patient:read_phi'),
    ('DOCTOR', 'ai:assist:use')
ON CONFLICT DO NOTHING;

-- NURSE：患者只读 + AI 辅助
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'empi:patient:read'),
    ('NURSE', 'ai:assist:use')
ON CONFLICT DO NOTHING;

-- PHARMACIST：患者只读 + AI 辅助（处方审核权在业务域校验）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('PHARMACIST', 'empi:patient:read'),
    ('PHARMACIST', 'ai:assist:use')
ON CONFLICT DO NOTHING;

-- QC：患者只读 + 审计只读 + AI 辅助
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'empi:patient:read'),
    ('QC', 'audit:log:read'),
    ('QC', 'ai:assist:use')
ON CONFLICT DO NOTHING;
