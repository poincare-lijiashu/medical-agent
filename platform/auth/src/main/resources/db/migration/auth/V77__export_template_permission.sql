-- ============================================================================
-- V77：省局报盘报文模板权限（G85）
--
--   * insurance:export:template:read   —— 查看模板（含字段映射与版本）；
--   * insurance:export:template:manage —— 新建/修改/发布/停用模板。
--
-- 为什么读写分离：
--   * 模板决定报盘字段与必填口径，误改会把整批报文改错；
--   * 只读角色的医保人员需要核对"现在生效的是哪一版、字段对不对"，
--     但不应该能直接发布一版未经审核的模板。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:export:template:read', '医保报盘报文模板查看', 'insurance'),
    ('insurance:export:template:manage', '医保报盘报文模板管理', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',             'insurance:export:template:read'),
    ('ADMIN',             'insurance:export:template:manage'),
    ('INSURANCE_OFFICER', 'insurance:export:template:read'),
    ('INSURANCE_OFFICER', 'insurance:export:template:manage')
ON CONFLICT DO NOTHING;
