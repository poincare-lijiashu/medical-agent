-- ============================================================================
-- V90：值班与重大事件权限（第 272 批）
--
--   ops:incident:write  创建事件、变更状态、指定指挥、评论、复盘草稿/提交/审批
--   ops:oncall:manage   维护值班轮换、生成排班、人工覆盖
--
-- 为什么复盘审批也放在 incident:write：复盘审批是事件处置的一部分，
-- 但仍保留"作者不能审批自己的复盘"的职责分离，不靠权限拆分兜底。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:incident:write', '重大事件与复盘处置', 'ops'),
    ('ops:oncall:manage', '值班轮换与排班管理', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:incident:write'),
    ('ADMIN', 'ops:oncall:manage'),
    ('OPS_AGENT', 'ops:incident:write'),
    ('OPS_MANAGER', 'ops:incident:write'),
    ('OPS_MANAGER', 'ops:oncall:manage')
ON CONFLICT DO NOTHING;
