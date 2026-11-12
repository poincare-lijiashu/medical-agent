-- ============================================================================
-- V92：外部接入源管理权限（第 276 批）
--
--   ops:integration:manage      创建/轮换/停用告警接入源（Alertmanager 等）
--   release:integration:manage  创建/轮换/停用 CI 接入源（GitHub/GitLab/Jenkins）
--
-- 只授予经理角色：接入令牌等于"机器身份"，能改它就能伪造工单/部署记录。
-- 一线与集成运维仍可用 ops:read / release:read 查看接入源状态与事件日志。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:integration:manage', '告警接入源管理', 'ops'),
    ('release:integration:manage', 'CI 接入源管理', 'release')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:integration:manage'),
    ('ADMIN', 'release:integration:manage'),
    ('OPS_MANAGER', 'ops:integration:manage'),
    ('RELEASE_MANAGER', 'release:integration:manage')
ON CONFLICT DO NOTHING;
