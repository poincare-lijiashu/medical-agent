-- ============================================================================
-- 认证域 V37：多科室执业绑定
--
-- 为什么需要这张表：
--   三甲医院的主任/会诊专家常在多个科室执业，单值 org_code 只能表达"主科室"。
--   行级权限按科室集合判定，因此账号 → 科室必须是多对多。
--
-- 约定：
--   * dept_id 是临床科室 UUID（clinical_svc.md_dept.id）。auth 不跨域建外键，
--     由组织架构同步/账号管理接口保证取值正确；签发的 JWT 只携带 depts 列表。
--   * org_code 仍保留为主科室编码（展示与历史兼容），不参与数据范围判定。
--   * 签发令牌时把 dept_id 列表写入 depts 声明，网关拼成 X-Org（英文逗号分隔）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.user_dept (
    user_id    uuid NOT NULL REFERENCES auth_svc.app_user (id) ON DELETE CASCADE,
    dept_id    uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, dept_id)
);

COMMENT ON TABLE auth_svc.user_dept IS
    '多科室执业绑定：dept_id 为临床科室 UUID，登录时写入 JWT depts 声明';

CREATE INDEX IF NOT EXISTS ix_user_dept_dept ON auth_svc.user_dept (dept_id);

-- 历史环境兼容：org_code 本身存的就是科室 UUID 时，直接回填为绑定关系。
-- 存的是 D001 这类编码的环境不回填（无法可靠映射到临床科室 UUID）。
INSERT INTO auth_svc.user_dept (user_id, dept_id)
SELECT id, org_code::uuid
  FROM auth_svc.app_user
 WHERE org_code ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
ON CONFLICT DO NOTHING;
