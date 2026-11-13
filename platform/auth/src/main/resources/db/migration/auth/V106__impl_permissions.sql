-- ============================================================================
-- V106：实施交付与培训考核权限（§298 / 计划书 G7）
--
--   ops:impl:read    模板、项目、任务、就绪报告、课程与成绩查询
--   ops:impl:manage  维护模板/课程、创建项目、更新任务、开考与录入成绩
--   ops:impl:verify  上线批准、验收签署、任务豁免（四眼）
--
-- 授权口径：
--   * 一线运维（OPS_AGENT）负责推进任务与培训执行，不能自己批准上线/验收；
--   * 运营经理（OPS_MANAGER）承担上线与验收复核；
--   * 集成（INTEGRATION）只读：联调任务与问题需要看上下文；
--   * 发布经理只读：上线批次需要核对实施就绪。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:impl:read', '实施交付与培训查询', 'ops'),
    ('ops:impl:manage', '实施模板/项目/任务与培训管理', 'ops'),
    ('ops:impl:verify', '上线批准、验收签署与任务豁免', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:impl:read'),
    ('ADMIN', 'ops:impl:manage'),
    ('ADMIN', 'ops:impl:verify'),
    ('OPS_MANAGER', 'ops:impl:read'),
    ('OPS_MANAGER', 'ops:impl:manage'),
    ('OPS_MANAGER', 'ops:impl:verify'),
    ('OPS_AGENT', 'ops:impl:read'),
    ('OPS_AGENT', 'ops:impl:manage'),
    ('INTEGRATION', 'ops:impl:read'),
    ('RELEASE_MANAGER', 'ops:impl:read')
ON CONFLICT DO NOTHING;
