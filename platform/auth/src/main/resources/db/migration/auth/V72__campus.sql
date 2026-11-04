-- G45 多院区基础：院区字典 + 用户院区绑定
--
-- 院区是数据归属的第一层边界；账号可跨院区执业（多对多）。
-- 说明：现有科室（user_dept）保持原语义，院区隔离在业务域按 campus_id 落地。
CREATE TABLE IF NOT EXISTS auth_svc.campus (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    name       text NOT NULL,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    sort_no    int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE auth_svc.campus IS '院区字典（多院区数据归属与隔离边界）';

INSERT INTO auth_svc.campus (id, code, name, sort_no) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'MAIN', '主院区', 10),
    ('a0000000-0000-0000-0000-000000000002', 'EAST', '东院区', 20)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS auth_svc.user_campus (
    user_id    uuid NOT NULL REFERENCES auth_svc.app_user (id) ON DELETE CASCADE,
    campus_id  uuid NOT NULL REFERENCES auth_svc.campus (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, campus_id)
);
CREATE INDEX IF NOT EXISTS ix_user_campus_campus ON auth_svc.user_campus (campus_id);
COMMENT ON TABLE auth_svc.user_campus IS '用户可访问院区（多院区执业；登录令牌下发 campuses 声明）';

-- 存量账号默认归主院区；管理员与质控跨院区
INSERT INTO auth_svc.user_campus (user_id, campus_id)
SELECT u.id, 'a0000000-0000-0000-0000-000000000001'
  FROM auth_svc.app_user u
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.user_campus (user_id, campus_id)
SELECT u.id, c.id
  FROM auth_svc.app_user u
  CROSS JOIN auth_svc.campus c
 WHERE u.username IN ('admin01', 'qc01')
ON CONFLICT DO NOTHING;

-- 演示医师 doctor01 需要至少一个科室绑定，否则非全院角色会被 DataScope fail-closed 拒绝。
INSERT INTO auth_svc.user_dept (user_id, dept_id)
SELECT u.id, '33333333-3333-3333-3333-333333333333'::uuid
  FROM auth_svc.app_user u
 WHERE u.username = 'doctor01'
   AND NOT EXISTS (
       SELECT 1 FROM auth_svc.user_dept d
        WHERE d.user_id = u.id
          AND d.dept_id = '33333333-3333-3333-3333-333333333333'::uuid
   );
