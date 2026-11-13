-- ============================================================================
-- V101：支付方分摊权限（§292 / 计划书 W2.6）
--
-- 为什么单独授权：支付方字典与分摊台账决定"这笔钱最终由谁承担"，
-- 属于财务口径维护，不能下放给挂号窗口；运营只读用于对账巡检。
--   * 医保办（INSURANCE_OFFICER）：维护支付方、首次分摊、退费；
--   * 运营管理（OPS_MANAGER）：只读（待分摊清单与台账）；
--   * 管理员（ADMIN）：全部（应急与运维）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:payer:read', '支付方分摊查询', 'insurance'),
    ('insurance:payer:manage', '支付方配置与结算分摊', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'insurance:payer:read'),
    ('ADMIN', 'insurance:payer:manage'),
    ('INSURANCE_OFFICER', 'insurance:payer:read'),
    ('INSURANCE_OFFICER', 'insurance:payer:manage'),
    ('OPS_MANAGER', 'insurance:payer:read')
ON CONFLICT DO NOTHING;
