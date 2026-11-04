-- ============================================================================
-- G14：收费票据权限点
--
-- 分权思路：
--   * receipt:issue —— 开票与重打（收费员/住院处）；
--   * receipt:void  —— 作废（收费窗口负责人）。
-- 作废与开票分开授权：能打票不等于能抹掉已交给患者的凭证。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:receipt:issue', '收费票据开票与重打', 'clinical'),
    ('clinical:receipt:void',  '收费票据作废',       'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:receipt:issue'),
    ('ADMIN', 'clinical:receipt:void'),
    ('REGISTRAR', 'clinical:receipt:issue'),
    ('REGISTRAR', 'clinical:receipt:void')
ON CONFLICT DO NOTHING;
