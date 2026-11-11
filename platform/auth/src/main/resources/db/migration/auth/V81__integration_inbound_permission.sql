-- ============================================================================
-- V81：入站集成消息权限（第 258 批）
--   integration:message:inbound  系统对系统推送的接收权限（与人工重投分离）
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('integration:message:inbound', '集成消息接收', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'integration:message:inbound'),
    ('INTEGRATION', 'integration:message:inbound')
ON CONFLICT DO NOTHING;
