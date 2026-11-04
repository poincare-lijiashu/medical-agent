-- ============================================================================
-- G9：耗材未使用退回权限点
--
-- 分权思路：
--   * return:write   —— 科室发起未使用耗材退回、撤回（护士/物资员）；
--   * return:approve —— 审批/驳回退回单（库房/物资科）。
-- 四眼原则：审批人不得是申请人，由 material 服务在状态变更前强制校验；
-- 权限点分开只是第一层，不能替代"同一个人不能自审"的业务校验。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('material:return:write',   '耗材未使用退回（建单/撤回）', 'material'),
    ('material:return:approve', '耗材退回审批/驳回',          'material')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：建单 + 审批
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'material:return:write'),
    ('ADMIN', 'material:return:approve')
ON CONFLICT DO NOTHING;

-- NURSE：建单 + 审批（病区/手术室；自审由服务层拦截）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'material:return:write'),
    ('NURSE', 'material:return:approve')
ON CONFLICT DO NOTHING;

-- PHARMACIST：审批（药械科常兼管物资库）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('PHARMACIST', 'material:return:approve')
ON CONFLICT DO NOTHING;
