-- ============================================================================
-- V89：运营支撑权限（第 271 批）
--
--   ops:read           服务目录/SLA/工单/看板查询
--   ops:ticket:create  创建工单
--   ops:ticket:write   派单/接单/处理/挂起/解决/关闭/重开/取消/评论
--   ops:sla:manage     维护服务目录、SLA 策略与营业日历
--   ops:audit:read     运营审计查询
--
-- 角色：
--   OPS_AGENT（一线运维）：建单、处理、查询；
--   OPS_MANAGER（运营经理）：在 agent 基础上维护 SLA/目录并查审计。
--   SLA 策略是"对外承诺"，不能让一线随手改，因此单独一个管理权限。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('OPS_AGENT', '一线运维', '创建与处理工单、查看运营看板', true),
    ('OPS_MANAGER', '运营经理', '维护服务目录与 SLA、处理工单、审计与复盘', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('ops:read', '运营查询与看板', 'ops'),
    ('ops:ticket:create', '创建工单', 'ops'),
    ('ops:ticket:write', '处理工单', 'ops'),
    ('ops:sla:manage', '服务目录与 SLA 管理', 'ops'),
    ('ops:audit:read', '运营审计查询', 'ops')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'ops:read'),
    ('ADMIN', 'ops:ticket:create'),
    ('ADMIN', 'ops:ticket:write'),
    ('ADMIN', 'ops:sla:manage'),
    ('ADMIN', 'ops:audit:read'),
    ('OPS_AGENT', 'ops:read'),
    ('OPS_AGENT', 'ops:ticket:create'),
    ('OPS_AGENT', 'ops:ticket:write'),
    ('OPS_MANAGER', 'ops:read'),
    ('OPS_MANAGER', 'ops:ticket:create'),
    ('OPS_MANAGER', 'ops:ticket:write'),
    ('OPS_MANAGER', 'ops:sla:manage'),
    ('OPS_MANAGER', 'ops:audit:read'),
    -- 集成运维只读：排障时需要看工单与 SLA 状态
    ('INTEGRATION', 'ops:read')
ON CONFLICT DO NOTHING;
