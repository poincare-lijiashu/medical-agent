-- ============================================================================
-- 存量数据兼容导入（迁移第 2 步：数据迁移的一次性对接）
--
-- 来源：既有 Python 服务的 public.departments / public.users（PG 真源）。
-- 策略：只读来源表、不修改；目标侧按唯一键幂等补齐；来源表不存在时静默跳过
--      （新部署环境无历史数据也能正常迁移到最新版本）。
-- 注意：角色值小写（doctor 等）→ 大写角色码（DOCTOR）；科室名 → 科室编码。
-- ============================================================================

DO $$
BEGIN
    -- ---- 科室 → 组织架构 ----
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'departments') THEN
        EXECUTE $import_org$
            INSERT INTO auth_svc.org (code, name, type, status)
            SELECT 'D' || lpad(d.id::text, 3, '0'), d.name, 'DEPT', 'ACTIVE'
            FROM public.departments d
            WHERE COALESCE(d.name, '') <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM auth_svc.org o WHERE o.name = d.name
              )
        $import_org$;
    END IF;

    -- ---- 账号 → app_user ----
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'users') THEN
        EXECUTE $import_user$
            INSERT INTO auth_svc.app_user
                (id, username, password_hash, display_name, org_code, status)
            SELECT gen_random_uuid(),
                   u.username,
                   u.password_hash,
                   u.username,
                   (SELECT o.code FROM auth_svc.org o WHERE o.name = u.dept LIMIT 1),
                   'ACTIVE'
            FROM public.users u
            WHERE COALESCE(u.username, '') <> ''
              AND COALESCE(u.password_hash, '') <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM auth_svc.app_user a
                  WHERE lower(a.username) = lower(u.username)
              )
        $import_user$;

        -- ---- 角色映射（仅映射到已存在的内置角色）----
        EXECUTE $import_role$
            INSERT INTO auth_svc.user_role (user_id, role_code)
            SELECT a.id, upper(u.role)
            FROM public.users u
            JOIN auth_svc.app_user a ON lower(a.username) = lower(u.username)
            JOIN auth_svc.role r ON r.code = upper(u.role)
            WHERE COALESCE(u.role, '') <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM auth_svc.user_role ur
                  WHERE ur.user_id = a.id AND ur.role_code = upper(u.role)
              )
        $import_role$;
    END IF;
END
$$;
