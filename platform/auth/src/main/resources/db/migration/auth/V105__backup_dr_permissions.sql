-- ============================================================================
-- V105：备份与灾备演练权限（§297）
--
--   ops:backup:read    备份策略与备份运行查询
--   ops:backup:manage  维护备份策略、上报备份运行（通常给备份平台服务账号）
--   ops:dr:read        灾备演练与发布门禁查询
--   ops:dr:manage      创建/开始/更新步骤/判失败/中止演练
--   ops:dr:verify      判定演练通过（复核人，不能是执行人）
--
-- 授权口径：
--   * 一线运维（OPS_AGENT）可执行演练，但不能自己判定通过；
--   * 运营经理（OPS_MANAGER）承担复核职责；
--   * 发布经理（RELEASE_MANAGER）与 SRE（SRE_DEPLOYER）需要只读门禁，
--     否则生产部署被拒时看不到"缺哪次演练"；
--   * 备份策略是全院数据安全承诺，只给运营经理与管理员维护。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:backup:read', '备份策略与运行查询', 'ops'),
    ('ops:backup:manage', '备份策略维护与运行上报', 'ops'),
    ('ops:dr:read', '灾备演练与门禁查询', 'ops'),
    ('ops:dr:manage', '灾备演练执行', 'ops'),
    ('ops:dr:verify', '灾备演练复核通过', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:backup:read'),
    ('ADMIN', 'ops:backup:manage'),
    ('ADMIN', 'ops:dr:read'),
    ('ADMIN', 'ops:dr:manage'),
    ('ADMIN', 'ops:dr:verify'),
    ('OPS_MANAGER', 'ops:backup:read'),
    ('OPS_MANAGER', 'ops:backup:manage'),
    ('OPS_MANAGER', 'ops:dr:read'),
    ('OPS_MANAGER', 'ops:dr:manage'),
    ('OPS_MANAGER', 'ops:dr:verify'),
    ('OPS_AGENT', 'ops:backup:read'),
    ('OPS_AGENT', 'ops:dr:read'),
    ('OPS_AGENT', 'ops:dr:manage'),
    ('RELEASE_MANAGER', 'ops:backup:read'),
    ('RELEASE_MANAGER', 'ops:dr:read'),
    ('SRE_DEPLOYER', 'ops:dr:read'),
    ('INTEGRATION', 'ops:backup:read')
ON CONFLICT DO NOTHING;
