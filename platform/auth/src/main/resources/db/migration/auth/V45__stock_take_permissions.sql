-- ============================================================================
-- V45：药品盘点权限（G6）
--
-- 拆成两个权限点而不是复用 pharmacy:stock:manage：
--   * take    —— 建单与实盘录入（一线药剂师）；
--   * approve —— 审批并用差异校准库存（药房主任/管理员）。
-- 角色层面两者都可能由 PHARMACIST 持有，但服务层强制"审批人 ≠ 建单人"，
-- 保证一次盘点至少经过两个账号（四眼原则），不靠角色堆叠做假。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:stock:take',    '药品盘点建单与实盘录入', 'pharmacy'),
    ('pharmacy:stock:approve', '药品盘点审批与库存校准', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:stock:take'),
    ('ADMIN', 'pharmacy:stock:approve'),
    ('PHARMACIST', 'pharmacy:stock:take'),
    ('PHARMACIST', 'pharmacy:stock:approve')
ON CONFLICT DO NOTHING;
