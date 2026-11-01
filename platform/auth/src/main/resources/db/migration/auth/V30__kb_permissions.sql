-- ============================================================================
-- V30：知识库权限点
--
-- 两级授权中的第一级（第二级是知识空间成员角色，由 kb 服务校验）：
--   * kb:space:read/write      创建、查看、归档空间与管理成员
--   * kb:document:read/write   文档列表/详情、写入、归档、重建索引
--   * kb:search                检索（PUBLIC 空间的只读发现也依赖该权限点）
-- 医师可维护知识；其余临床角色只读与检索；管理员全量。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('kb:space:read',     '知识空间查看',   'kb'),
    ('kb:space:write',    '知识空间管理',   'kb'),
    ('kb:document:read',  '知识文档查看',   'kb'),
    ('kb:document:write', '知识文档维护',   'kb'),
    ('kb:search',         '知识检索',       'kb')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', code FROM auth_svc.permission WHERE domain = 'kb'
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'DOCTOR', code FROM auth_svc.permission WHERE domain = 'kb'
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE',              'kb:space:read'),
    ('NURSE',              'kb:document:read'),
    ('NURSE',              'kb:search'),
    ('PHARMACIST',         'kb:space:read'),
    ('PHARMACIST',         'kb:document:read'),
    ('PHARMACIST',         'kb:search'),
    ('QC',                 'kb:space:read'),
    ('QC',                 'kb:document:read'),
    ('QC',                 'kb:search'),
    ('TECHNICIAN',         'kb:space:read'),
    ('TECHNICIAN',         'kb:document:read'),
    ('TECHNICIAN',         'kb:search'),
    ('REGISTRAR',          'kb:space:read'),
    ('REGISTRAR',          'kb:document:read'),
    ('REGISTRAR',          'kb:search'),
    ('INSURANCE_OFFICER',  'kb:space:read'),
    ('INSURANCE_OFFICER',  'kb:document:read'),
    ('INSURANCE_OFFICER',  'kb:search')
ON CONFLICT DO NOTHING;
