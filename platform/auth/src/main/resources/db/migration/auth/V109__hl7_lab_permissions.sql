-- ============================================================================
-- V109：HL7 v2 联调测试台权限（§301 / 计划书 G2）
--
--   integration:hl7:read   端点/用例/执行记录/看板查询
--   integration:hl7:manage 端点与用例维护、启停
--   integration:hl7:run    执行联调用例（会向院内/测试端点发送报文）
--
-- 角色：INTEGRATION 全量；OPS_AGENT 只读+执行；RELEASE_MANAGER/QC 只读。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('integration:hl7:read', 'HL7 联调查询', 'integration'),
    ('integration:hl7:manage', 'HL7 端点与用例维护', 'integration'),
    ('integration:hl7:run', 'HL7 联调执行', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'integration:hl7:read'),
    ('ADMIN', 'integration:hl7:manage'),
    ('ADMIN', 'integration:hl7:run'),
    ('INTEGRATION', 'integration:hl7:read'),
    ('INTEGRATION', 'integration:hl7:manage'),
    ('INTEGRATION', 'integration:hl7:run'),
    ('OPS_AGENT', 'integration:hl7:read'),
    ('OPS_AGENT', 'integration:hl7:run'),
    ('RELEASE_MANAGER', 'integration:hl7:read'),
    ('QC', 'integration:hl7:read')
ON CONFLICT DO NOTHING;
