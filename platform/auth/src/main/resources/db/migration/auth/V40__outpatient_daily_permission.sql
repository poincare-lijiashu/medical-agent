-- ============================================================================
-- G1：门诊日结权限点 clinical:charge:daily
--
-- 日结会看到全院门诊收入与退费明细（含科室/支付方式分账），
-- 比"门诊收费（clinical:charge:settle）"的可见范围大，单独建权限点，
-- 便于院内把"收费"与"出报表/对账"分给不同岗位。
--
-- 兼容策略：已具备门诊收费能力（clinical:charge:settle）的角色一并授予，
-- 避免升级后收费窗口无法日结；ADMIN 始终保有全部权限。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:charge:daily', '门诊收费日结与分账报表', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT rp.role_code, 'clinical:charge:daily'
  FROM auth_svc.role_permission rp
 WHERE rp.permission_code = 'clinical:charge:settle'
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:charge:daily')
ON CONFLICT DO NOTHING;
