-- ============================================================================
-- V46：药品报损权限（G7）
--
-- 与盘点同一分权思路：
--   * write   —— 提交报损（一线药剂师，说明破损/过期/召回情况）；
--   * approve —— 审批扣减库存或驳回（药房主任/管理员）。
-- 服务层强制审批人 ≠ 申请人；角色可以同时持有，但一次报损至少经过两个账号。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:loss:write',   '药品报损提交', 'pharmacy'),
    ('pharmacy:loss:approve', '药品报损审批与驳回', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:loss:write'),
    ('ADMIN', 'pharmacy:loss:approve'),
    ('PHARMACIST', 'pharmacy:loss:write'),
    ('PHARMACIST', 'pharmacy:loss:approve')
ON CONFLICT DO NOTHING;
