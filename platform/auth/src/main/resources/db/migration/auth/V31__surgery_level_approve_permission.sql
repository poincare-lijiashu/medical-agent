-- ============================================================================
-- V31：手术分级审批权限
--
--   * clinical:surgery:approve —— 三、四级手术分级的审批/驳回（医务/质控；管理员兜底）
--
-- 为什么要单独一个权限点，而不是复用 clinical:surgery:write：
--   * 分级申请由术者提交，审批必须由**另一角色**完成（四眼原则）；
--   * 若复用 write，任何能录手术的医师都能自己批自己的分级，门槛形同虚设。
--
-- 申请（设置分级）仍用 clinical:surgery:write，审批用本权限点，职责分离。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:surgery:approve', '手术分级审批（三、四级手术）', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:surgery:approve'),
    ('QC',    'clinical:surgery:approve')
ON CONFLICT DO NOTHING;
