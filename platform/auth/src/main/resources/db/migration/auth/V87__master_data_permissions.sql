-- ============================================================================
-- V87：主数据与字典映射权限（第 269 批）
--
--   mdm:read        查询域/条目/映射/版本、导出映射、查看质量看板
--   mdm:write       提交变更申请（建条目、改条目、启停、映射新增/停用）
--   mdm:approve     审批/驳回变更申请（提交人不能审批自己的申请，服务层强制）
--   mdm:import      源表同步与批量导入（预检/应用）；按批次 + 审计 + 版本治理
--   mdm:audit:read  查询主数据审计日志
--
-- 为什么拆两个角色：
--   DATA_STEWARD（主数据管理员）负责录入与导入，是"做事的人"；
--   DATA_GOVERNOR（数据治理员）负责审批与审计，是"把关的人"。
--   合并成一个角色等于自己审自己，W4.1 的"变更有审批"就落空了。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('DATA_STEWARD', '主数据管理员', '主数据录入、源表同步与批量导入', true),
    ('DATA_GOVERNOR', '数据治理员', '主数据变更审批、审计与质量复核', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('mdm:read', '主数据查询与导出', 'mdm'),
    ('mdm:write', '主数据变更申请', 'mdm'),
    ('mdm:approve', '主数据变更审批', 'mdm'),
    ('mdm:import', '主数据源同步与批量导入', 'mdm'),
    ('mdm:audit:read', '主数据审计查询', 'mdm')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'mdm:read'),
    ('ADMIN', 'mdm:write'),
    ('ADMIN', 'mdm:approve'),
    ('ADMIN', 'mdm:import'),
    ('ADMIN', 'mdm:audit:read'),
    ('DATA_STEWARD', 'mdm:read'),
    ('DATA_STEWARD', 'mdm:write'),
    ('DATA_STEWARD', 'mdm:import'),
    ('DATA_GOVERNOR', 'mdm:read'),
    ('DATA_GOVERNOR', 'mdm:approve'),
    ('DATA_GOVERNOR', 'mdm:audit:read'),
    ('QC', 'mdm:read'),
    -- 集成运维只需要读：对账时要能按外部编码反查本地编码
    ('INTEGRATION', 'mdm:read')
ON CONFLICT DO NOTHING;
