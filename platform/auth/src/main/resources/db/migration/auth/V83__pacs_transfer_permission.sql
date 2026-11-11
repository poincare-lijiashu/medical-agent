-- ============================================================================
-- V83：PACS DIMSE 传输权限（第 260 批）
--
--   tech:pacs:transfer  C-STORE（推送影像）与 C-MOVE（让 PACS 推送到指定 AE）
--
-- 为什么与 tech:pacs:read 分开：
--   只读调阅不会改动 PACS；传输会新增/复制影像、占用存储与网络，属于运维动作，
--   必须单独授权并留审计。默认只授予 ADMIN / TECHNICIAN / INTEGRATION。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:pacs:transfer', 'PACS 影像传输', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'tech:pacs:transfer'),
    ('TECHNICIAN', 'tech:pacs:transfer'),
    ('INTEGRATION', 'tech:pacs:transfer')
ON CONFLICT DO NOTHING;
