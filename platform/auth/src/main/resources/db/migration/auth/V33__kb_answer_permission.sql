-- ============================================================================
-- V33：知识库问答权限点
--
--   * kb:answer —— 调用抽取式带引用回答（POST /kb/spaces/{id}/answer）
--
-- 为什么不复用 kb:search：问答会放大下游成本（未来接入大模型后按 token 计费），
-- 且"能检索"与"能拿到成段答案"在合规上不是同一件事（答案会被直接抄进病历）。
-- 单独一个权限点，后续可以只对部分科室开放而不影响检索。
--
-- 授权范围：与 kb:search 完全对齐（能用检索的角色就能问答），避免上线即大面积不可用；
-- 需要收紧时只改 role_permission，不用改代码。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('kb:answer', '知识库问答（抽取式带引用）', 'kb')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT DISTINCT rp.role_code, 'kb:answer'
  FROM auth_svc.role_permission rp
 WHERE rp.permission_code = 'kb:search'
ON CONFLICT DO NOTHING;
