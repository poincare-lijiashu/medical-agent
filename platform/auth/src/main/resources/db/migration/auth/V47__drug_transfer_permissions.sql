-- ============================================================================
-- V47：药品科室调拨权限（G8）
--
-- write  —— 建单、发出（扣中心库存）、撤回；
-- accept —— 科室接收、拒收（入二级库 / 退回中心）。
-- 药房可同时持有两个权限；护理岗位只拿 accept，用于病区收货。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:transfer:write',  '药品调拨建单与发出', 'pharmacy'),
    ('pharmacy:transfer:accept', '药品调拨接收与拒收', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:transfer:write'),
    ('ADMIN', 'pharmacy:transfer:accept'),
    ('PHARMACIST', 'pharmacy:transfer:write'),
    ('PHARMACIST', 'pharmacy:transfer:accept'),
    ('NURSE', 'pharmacy:transfer:accept')
ON CONFLICT DO NOTHING;
