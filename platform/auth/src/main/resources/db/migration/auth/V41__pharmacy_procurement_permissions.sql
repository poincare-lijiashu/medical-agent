-- ============================================================================
-- G2：药品采购权限点（供应商 / 采购单 / 到货验收）
--
-- 为什么要继续拆细（而不是复用 pharmacy:stock:manage）：
--   * supplier:manage 是主数据维护（首营企业/证照），通常由药库负责人而非窗口药师维护；
--   * purchase:write 只允许建草稿，purchase:approve 才能提交与取消——避免"自己建单自己审批"；
--   * receipt:write 会直接写库存（drug_batch），风险最高，单独授权便于审计到人。
--
-- 兼容策略：PHARMACIST 默认拿到全部四项（院内小药房常见一人多岗），ADMIN 始终全量；
-- 需要严格三权分立时，可在角色管理里把 approve / receipt 从 PHARMACIST 收回。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('pharmacy:supplier:manage',  '供应商台账与证照维护', 'pharmacy'),
    ('pharmacy:purchase:read',    '采购单与验收单查询',   'pharmacy'),
    ('pharmacy:purchase:write',   '创建采购单（草稿）',   'pharmacy'),
    ('pharmacy:purchase:approve', '提交/取消采购单',      'pharmacy'),
    ('pharmacy:receipt:write',    '到货验收入库',         'pharmacy')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('PHARMACIST', 'pharmacy:supplier:manage'),
    ('PHARMACIST', 'pharmacy:purchase:read'),
    ('PHARMACIST', 'pharmacy:purchase:write'),
    ('PHARMACIST', 'pharmacy:purchase:approve'),
    ('PHARMACIST', 'pharmacy:receipt:write'),
    ('ADMIN', 'pharmacy:supplier:manage'),
    ('ADMIN', 'pharmacy:purchase:read'),
    ('ADMIN', 'pharmacy:purchase:write'),
    ('ADMIN', 'pharmacy:purchase:approve'),
    ('ADMIN', 'pharmacy:receipt:write')
ON CONFLICT DO NOTHING;
