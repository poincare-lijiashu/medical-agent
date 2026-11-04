-- ============================================================================
-- G12：耗材跨科室调拨权限点
--
-- 分权思路：
--   * transfer:write  —— 发起/撤回调拨（调出科室护士/物资员）；
--   * transfer:accept —— 接收/拒收（调入科室）。
-- 接收人不得是申请人，由 material 服务在状态变更前强制校验；
-- 与药品调拨、耗材退回保持同一套"发起与接收分开授权"的模型。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('material:transfer:write',  '耗材跨科室调拨（发起/撤回）', 'material'),
    ('material:transfer:accept', '耗材跨科室调拨接收/拒收',     'material')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'material:transfer:write'),
    ('ADMIN', 'material:transfer:accept'),
    ('NURSE', 'material:transfer:write'),
    ('NURSE', 'material:transfer:accept'),
    -- 药械科常兼管物资库：可接收/拒收，不默认拥有发起权
    ('PHARMACIST', 'material:transfer:accept')
ON CONFLICT DO NOTHING;
