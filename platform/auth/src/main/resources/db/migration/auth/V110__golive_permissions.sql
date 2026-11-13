-- ============================================================================
-- V110：上线指挥台权限（§302）
--
--   ops:golive:read  聚合实施就绪/灾备/SLO/事件/质量/冻结并导出证据包
--
-- 角色：管理员、运营经理、发布经理、SRE、质控与一线运维均可只读；
-- 该权限不授予任何写操作，报告本身不替代各域审批。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:golive:read', '上线指挥台与证据包查询', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:golive:read'),
    ('OPS_MANAGER', 'ops:golive:read'),
    ('OPS_AGENT', 'ops:golive:read'),
    ('RELEASE_MANAGER', 'ops:golive:read'),
    ('SRE_DEPLOYER', 'ops:golive:read'),
    ('QC', 'ops:golive:read')
ON CONFLICT DO NOTHING;
