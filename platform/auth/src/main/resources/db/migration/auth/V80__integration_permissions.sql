-- ============================================================================
-- V80：集成消息台账权限（第 257 批）
--   integration:message:read   查看台账/汇总（运维、信息科）
--   integration:message:retry  登记与人工重投（会影响对端数据，收紧到集成运维/管理员）
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('INTEGRATION', '集成运维', '院内系统接口台账、重投与对账', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('integration:message:read', '集成消息查看', 'integration'),
    ('integration:message:retry', '集成消息重投', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'integration:message:read'),
    ('ADMIN', 'integration:message:retry'),
    ('INTEGRATION', 'integration:message:read'),
    ('INTEGRATION', 'integration:message:retry')
ON CONFLICT DO NOTHING;
