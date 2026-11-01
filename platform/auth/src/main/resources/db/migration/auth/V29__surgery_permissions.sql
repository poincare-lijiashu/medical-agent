-- ============================================================================
-- V29：手术麻醉域权限点（ICD-9-CM-3 字典 + 手术记录）
--
--   * clinical:surgery:read  —— 手术记录与手术编码查询（医师/护士/病案/管理员）
--   * clinical:surgery:write —— 计划/开始/完成/取消手术（医师；管理员兜底）
--
-- 病案室不直接改手术记录：首页里的手术操作是"已完成手术"的快照副本，
-- 病案室只能在草稿态修正切口等级等首页专有字段。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:surgery:read',  '手术记录查询', 'clinical'),
    ('clinical:surgery:write', '手术记录维护（计划/开始/完成/取消）', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
 WHERE p.code IN ('clinical:surgery:read', 'clinical:surgery:write')
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'clinical:surgery:read'),
    ('DOCTOR', 'clinical:surgery:write'),
    ('NURSE',  'clinical:surgery:read'),
    ('QC',     'clinical:surgery:read')
ON CONFLICT DO NOTHING;
