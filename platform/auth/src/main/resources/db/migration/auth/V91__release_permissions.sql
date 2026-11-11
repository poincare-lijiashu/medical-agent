-- ============================================================================
-- V91：版本与补丁权限（第 273 批）
--
--   release:read      组件/版本/列车/兼容矩阵/补丁/窗口/部署记录查询
--   release:write     注册组件与版本、编排列车、创建补丁与变更窗口
--   release:approve   版本发布、列车冻结/发布、补丁审批与发布
--   release:deploy    记录生产部署与回滚
--   release:audit:read 发布审计
--
-- 角色：
--   RELEASE_MANAGER：编排、审批、发布、审计；
--   SRE_DEPLOYER：只读 + 部署/回滚（能执行，不能自己批准自己的发布）。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('RELEASE_MANAGER', '发布经理', '版本编排、列车冻结/发布、补丁审批与发布审计', true),
    ('SRE_DEPLOYER', 'SRE 发布执行', '生产部署与回滚记录', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('release:read', '发布查询', 'release'),
    ('release:write', '版本与列车编排', 'release'),
    ('release:approve', '版本发布与补丁审批', 'release'),
    ('release:deploy', '部署与回滚记录', 'release'),
    ('release:audit:read', '发布审计查询', 'release')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'release:read'),
    ('ADMIN', 'release:write'),
    ('ADMIN', 'release:approve'),
    ('ADMIN', 'release:deploy'),
    ('ADMIN', 'release:audit:read'),
    ('RELEASE_MANAGER', 'release:read'),
    ('RELEASE_MANAGER', 'release:write'),
    ('RELEASE_MANAGER', 'release:approve'),
    ('RELEASE_MANAGER', 'release:deploy'),
    ('RELEASE_MANAGER', 'release:audit:read'),
    ('SRE_DEPLOYER', 'release:read'),
    ('SRE_DEPLOYER', 'release:deploy'),
    ('INTEGRATION', 'release:read')
ON CONFLICT DO NOTHING;
