-- ============================================================================
-- V32：麻醉药品管理权限
--
--   * pharmacy:narcotic:manage —— 麻醉药品处方权授予/撤销、专册查询维护、空安瓿回收登记
--
-- 为什么不复用 pharmacy:prescription:create：处方权是"医务科授予医师"的资格，
-- 和"能不能开方"是两个不同的权限面；用同一个权限点会让所有能开方的账号自动获得处方权。
--
-- 授予 ADMIN 与 PHARMACIST（药房主任/麻精药品管理员）；医师端只有开方权，
-- 是否具备麻醉药品处方权由 narcotic_prescriber 名单决定（按人事/医务授权维护）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:narcotic:manage', '麻醉药品管理（处方权/专册/空安瓿回收）', 'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'pharmacy:narcotic:manage'),
    ('PHARMACIST', 'pharmacy:narcotic:manage')
ON CONFLICT DO NOTHING;
