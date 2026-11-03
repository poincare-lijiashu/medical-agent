-- ============================================================================
-- V35：患者标识明文读取权限（phi:read）
--
-- 行级权限（DataScope）除了"能看哪些科室"，还决定"能看哪些字段"：
--   * 有 phi:read —— 返回完整患者号/姓名；
--   * 无 phi:read —— 服务端返回掩码（P00****23 / 张**），前端拿到的是已脱敏值。
--
-- 授予范围对齐 docs/DESIGN-DATA-SCOPE.md §3.2：
--   诊疗必需角色（医师/护士/药师）+ 管理员；质控员与挂号收费员只拿掩码。
--   ANESTHETIST / OR_NURSE 目前尚无内置角色行，后续建号时一并授予即可。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('phi:read', '查看完整患者标识（患者号与姓名明文）', 'phi')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',      'phi:read'),
    ('DOCTOR',     'phi:read'),
    ('NURSE',      'phi:read'),
    ('PHARMACIST', 'phi:read')
ON CONFLICT DO NOTHING;
