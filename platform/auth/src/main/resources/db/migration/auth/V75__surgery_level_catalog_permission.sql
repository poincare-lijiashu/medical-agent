-- ============================================================================
-- V75：手术分级目录维护权限（G81）
--
--   * clinical:surgery:catalog:write —— 维护"术种 → 默认分级"目录（医务/管理员）
--
-- 为什么单独一个权限点：
--   * 目录是**全院口径**，改动会影响之后每一台同类手术的自动定级；
--   * 若复用 clinical:surgery:write，任何能录手术的医师都能改全院默认分级，
--     等于把医疗质量制度的基线交给个人；
--   * 与 clinical:surgery:approve（审批单台分级）职责分离：可以审批一台手术，
--     不代表可以改目录基线。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:surgery:catalog:write', '手术分级目录维护（术种默认分级）', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:surgery:catalog:write')
ON CONFLICT DO NOTHING;
