-- ============================================================================
-- V93：授权与许可权限（第 279 批）
--
--   license:read    查看授权状态/版本矩阵/模块权益/历史授权/变更审计
--   license:manage  导入授权书、吊销授权、上报用量、签发（厂商构建）
--
-- 授权管理等于"决定这套系统还能不能用"，只给管理员：
-- 运营经理可以看状态做续期提醒，但不能自己导入或吊销授权。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('license:read', '授权状态查看', 'license'),
    ('license:manage', '授权书管理', 'license')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'license:read'),
    ('ADMIN', 'license:manage'),
    ('OPS_MANAGER', 'license:read'),
    ('QC', 'license:read')
ON CONFLICT DO NOTHING;
