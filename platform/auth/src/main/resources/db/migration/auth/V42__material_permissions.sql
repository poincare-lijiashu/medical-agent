-- ============================================================================
-- G3：物资耗材权限点
--
-- 分权思路：
--   * catalog:manage —— 耗材目录与调价（物资科），直接决定采购/收费口径；
--   * stock:manage   —— 批次入库（库房），涉及效期与注册证；
--   * issue:write    —— 科室/手术间请领出库（护士），扣库存；
--   * trace:write    —— 高值/植入耗材用到哪位患者/哪台手术（手术室/病区护士）；
--   * trace:read     —— 追溯查询与批次召回（院感、质控、物资科）。
-- 追溯与出库分开授权：能出库不等于能改追溯，召回证据链必须可追责到人。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('material:read',            '耗材目录/库存/出库/追溯查询', 'material'),
    ('material:catalog:manage',  '耗材目录维护与调价',          'material'),
    ('material:stock:manage',    '耗材批次入库与效期管理',      'material'),
    ('material:issue:write',     '耗材出库（科室/手术间请领）',  'material'),
    ('material:trace:write',     '高值/植入耗材使用追溯登记',    'material'),
    ('material:trace:read',      '耗材追溯查询与批次召回',      'material')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'material:read'),
    ('ADMIN', 'material:catalog:manage'),
    ('ADMIN', 'material:stock:manage'),
    ('ADMIN', 'material:issue:write'),
    ('ADMIN', 'material:trace:write'),
    ('ADMIN', 'material:trace:read')
ON CONFLICT DO NOTHING;

-- NURSE：查询 + 出库 + 追溯登记 + 追溯查询（病区/手术室一线）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'material:read'),
    ('NURSE', 'material:issue:write'),
    ('NURSE', 'material:trace:write'),
    ('NURSE', 'material:trace:read')
ON CONFLICT DO NOTHING;

-- DOCTOR：查询 + 追溯查询（术前核对植入物批次）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'material:read'),
    ('DOCTOR', 'material:trace:read')
ON CONFLICT DO NOTHING;

-- PHARMACIST：查询（药械科常兼管物资库，维护权按院内岗位另行授予）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('PHARMACIST', 'material:read')
ON CONFLICT DO NOTHING;

-- QC：查询 + 追溯查询（院感/质控召回）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'material:read'),
    ('QC', 'material:trace:read')
ON CONFLICT DO NOTHING;
