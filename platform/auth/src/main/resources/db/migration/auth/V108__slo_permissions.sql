-- ============================================================================
-- V108：SLO 与错误预算权限（§300 / 计划书 G6）
--
--   ops:slo:read    SLO 列表/详情/快照/看板查询
--   ops:slo:manage  SLO 定义、启停与快照上报
--
-- 角色：
--   OPS_MANAGER：定义与维护 SLO；
--   OPS_AGENT：只读（一线看预算状态）；
--   RELEASE_MANAGER / SRE_DEPLOYER：只读（发布前核对可靠性预算）；
--   INTEGRATION：只读。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:slo:read', 'SLO 与错误预算查询', 'ops'),
    ('ops:slo:manage', 'SLO 定义与快照上报', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:slo:read'),
    ('ADMIN', 'ops:slo:manage'),
    ('OPS_MANAGER', 'ops:slo:read'),
    ('OPS_MANAGER', 'ops:slo:manage'),
    ('OPS_AGENT', 'ops:slo:read'),
    ('RELEASE_MANAGER', 'ops:slo:read'),
    ('SRE_DEPLOYER', 'ops:slo:read'),
    ('INTEGRATION', 'ops:slo:read')
ON CONFLICT DO NOTHING;
