-- ============================================================================
-- V44：影像调阅审计权限（G5）
--
-- 为什么单独建权限点：影像调阅日志包含"哪位医师在何时看了哪位患者的影像"，
-- 属于敏感行为数据。tech:pacs:read 面向所有技师/医师（日常阅片需要），
-- 不能顺带让他们看到同事的调阅轨迹；审计查询按合规岗位单独授予。
--
-- 授予口径：已持有跨域审计权限（audit:log:read）的岗位自动获得，
-- 避免与现有审计岗（ADMIN 等）分裂成两套授权。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('tech:pacs:audit', '影像调阅审计查询', 'tech')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT DISTINCT rp.role_code, 'tech:pacs:audit'
  FROM auth_svc.role_permission rp
 WHERE rp.permission_code = 'audit:log:read'
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'tech:pacs:audit')
ON CONFLICT DO NOTHING;
