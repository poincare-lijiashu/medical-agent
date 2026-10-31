-- ============================================================================
-- V12：医技域权限点与角色映射
--
-- 岗位划分（沿用临床的"申请-执行-报告"分离原则）：
--   * 医师：开单、取消、审核报告与确认危急值；
--   * 护士：采集标本、确认危急值；
--   * 技师（新增 TECHNICIAN 角色）：接收标本、开始执行、书写/修订报告、关闭危急值；
--   * 报告审核由上级技师或医师完成，避免"自己写自己审"。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('TECHNICIAN', '医技技师', '标本接收、检查执行、报告书写与修订', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:read',                    '医技申请/报告查询',   'tech'),
    ('tech:application:create',      '开立检查/检验申请',   'tech'),
    ('tech:application:cancel',      '取消申请',             'tech'),
    ('tech:application:close',       '关闭申请',             'tech'),
    ('tech:specimen:collect',        '采集标本',             'tech'),
    ('tech:specimen:receive',        '接收/拒收标本',        'tech'),
    ('tech:report:write',            '书写与修订报告',       'tech'),
    ('tech:report:review',           '审核签发报告',         'tech'),
    ('tech:critical:acknowledge',    '危急值确认与关闭',     'tech')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p WHERE p.domain = 'tech'
ON CONFLICT DO NOTHING;

-- DOCTOR：开单/取消/关闭 + 审核报告 + 确认危急值 + 只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'tech:read'),
    ('DOCTOR', 'tech:application:create'),
    ('DOCTOR', 'tech:application:cancel'),
    ('DOCTOR', 'tech:application:close'),
    ('DOCTOR', 'tech:report:review'),
    ('DOCTOR', 'tech:critical:acknowledge')
ON CONFLICT DO NOTHING;

-- NURSE：采集标本 + 确认危急值 + 只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'tech:read'),
    ('NURSE', 'tech:specimen:collect'),
    ('NURSE', 'tech:critical:acknowledge')
ON CONFLICT DO NOTHING;

-- TECHNICIAN：接收标本、执行、书写报告、关闭危急值、关闭申请（技师也可关闭）+ 只读
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('TECHNICIAN', 'tech:read'),
    ('TECHNICIAN', 'tech:specimen:receive'),
    ('TECHNICIAN', 'tech:report:write'),
    ('TECHNICIAN', 'tech:application:close'),
    ('TECHNICIAN', 'tech:critical:acknowledge')
ON CONFLICT DO NOTHING;

-- REGISTRAR（窗口）：只读（便于查询患者检查进度）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'tech:read')
ON CONFLICT DO NOTHING;
