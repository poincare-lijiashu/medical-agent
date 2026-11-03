-- ============================================================================
-- P1-S1：改密拆成独立权限点 auth:user:reset_password
--
-- 背景：POST /users/{id}/reset-password 此前复用 auth:user:update，等于"能改资料 =
-- 能替任何人设置密码"。改密可以直接接管账号（登录后看到的是被改密者的权限范围），
-- 不应与"维护姓名/科室"同权。
--
-- 兼容策略：凡当前持有 auth:user:update 的角色，一并授予新权限点，
-- 保证升级后不会有人"突然不能改密"；后续按岗位收敛只需调整 role_permission。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('auth:user:reset_password', '重置账号密码', 'auth')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT rp.role_code, 'auth:user:reset_password'
  FROM auth_svc.role_permission rp
 WHERE rp.permission_code = 'auth:user:update'
ON CONFLICT DO NOTHING;
