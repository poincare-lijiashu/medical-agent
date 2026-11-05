-- ============================================================================
-- V76：设备维保验收权限（G82）
--
--   * tech:device:accept —— 设备维保工单验收（设备科/质控；管理员兜底）
--
-- 为什么单独一个权限点：
--   * 工单由技师报修/维修，验收必须由**另一类角色**确认修复效果与费用；
--   * 若复用 tech:device:write，报修人可自己验收自己的工单，验收门槛形同虚设
--     （服务层另有"建单人不得自验"的四眼校验，权限分离是第一道门）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:device:accept', '设备维保工单验收', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'tech:device:accept'),
    ('QC',    'tech:device:accept')
ON CONFLICT DO NOTHING;
