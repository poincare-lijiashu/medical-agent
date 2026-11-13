-- ============================================================================
-- V100：医保结算对账权限（§291 / 计划书 W2.5）
--
-- 背景：日结/月结对账是**财务结论**，不是窗口日常操作。权限必须与出单/确认分开：
--   * 窗口（REGISTRAR）：不可见全量对账（避免把部分院区数据当全院结论）；
--   * 医保办（INSURANCE_OFFICER）：可生成、处理差异、关闭批次（与出单岗分离）；
--   * 运营管理（OPS_MANAGER）：只读，用于运营巡检与月报；
--   * 管理员（ADMIN）：全部（应急与运维）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:recon:read', '医保结算对账查询', 'insurance'),
    ('insurance:recon:manage', '医保结算对账生成与处理', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'insurance:recon:read'),
    ('ADMIN', 'insurance:recon:manage'),
    ('INSURANCE_OFFICER', 'insurance:recon:read'),
    ('INSURANCE_OFFICER', 'insurance:recon:manage'),
    ('OPS_MANAGER', 'insurance:recon:read')
ON CONFLICT DO NOTHING;
