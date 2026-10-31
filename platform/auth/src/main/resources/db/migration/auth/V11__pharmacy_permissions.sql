-- ============================================================================
-- V11：药房域权限点与角色映射
--
-- 设计原则（药事管理三权分立）：
--   * 医师只负责开方（pharmacy:prescription:create），不能审核、不能发药；
--   * 药师负责审核（review）、发药（execute）、退药（return），不能开方；
--   * 药品字典与库存由药房主任/管理员维护（drug:manage / stock:manage）；
--   * 全员可查（pharmacy:read），但只读不写。
-- 这样即使前端把按钮放错位置，服务端也不会允许"自己开方自己发药"。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:read',                '药品/处方/库存查询',   'pharmacy'),
    ('pharmacy:drug:manage',         '药品字典维护与调价',   'pharmacy'),
    ('pharmacy:stock:manage',        '药品入库与批次管理',   'pharmacy'),
    ('pharmacy:prescription:create', '开立处方',             'pharmacy'),
    ('pharmacy:prescription:review', '处方审核（通过/驳回）', 'pharmacy'),
    ('pharmacy:dispense:execute',    '发药',                 'pharmacy'),
    ('pharmacy:dispense:return',     '退药',                 'pharmacy')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p WHERE p.domain = 'pharmacy'
ON CONFLICT DO NOTHING;

-- DOCTOR：开方 + 查询（不含审核/发药）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'pharmacy:read'),
    ('DOCTOR', 'pharmacy:prescription:create')
ON CONFLICT DO NOTHING;

-- PHARMACIST：查询 + 审核 + 发药 + 退药 + 字典/库存维护
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('PHARMACIST', 'pharmacy:read'),
    ('PHARMACIST', 'pharmacy:drug:manage'),
    ('PHARMACIST', 'pharmacy:stock:manage'),
    ('PHARMACIST', 'pharmacy:prescription:review'),
    ('PHARMACIST', 'pharmacy:dispense:execute'),
    ('PHARMACIST', 'pharmacy:dispense:return')
ON CONFLICT DO NOTHING;

-- NURSE：只读（病区核对用药时需要看发药状态），不含处方与库存写权限
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'pharmacy:read')
ON CONFLICT DO NOTHING;
