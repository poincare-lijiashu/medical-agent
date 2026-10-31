-- ============================================================================
-- V6：临床核心（挂号/就诊/医嘱/收费）权限点与角色映射
--
-- 设计：新增"挂号收费员（REGISTRAR）"角色——挂号与收费是窗口岗位，不能复用医师账号；
-- 医师只负责接诊与开医嘱；护士只读就诊（执行医嘱在后续护理域实现）。
-- ============================================================================

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('REGISTRAR', '挂号收费员', '挂号、退号、门诊收费与票据打印', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:visit:read',         '查询就诊',             'clinical'),
    ('clinical:registration:create', '门诊挂号',             'clinical'),
    ('clinical:registration:cancel', '取消挂号（退号）',     'clinical'),
    ('clinical:visit:treat',        '接诊与结束就诊',       'clinical'),
    ('clinical:order:create',       '开立/撤销医嘱',         'clinical'),
    ('clinical:charge:read',        '查询收费单',           'clinical'),
    ('clinical:charge:settle',      '门诊收费结算',         'clinical'),
    ('clinical:slot:manage',        '号源（排班）维护',     'clinical')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部权限（含新增权限）
INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
ON CONFLICT DO NOTHING;

-- DOCTOR：看就诊、接诊/结束、开医嘱
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'clinical:visit:read'),
    ('DOCTOR', 'clinical:visit:treat'),
    ('DOCTOR', 'clinical:order:create')
ON CONFLICT DO NOTHING;

-- REGISTRAR：挂号/退号 + 收费 + 患者建档（窗口首次建档是常态）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('REGISTRAR', 'clinical:visit:read'),
    ('REGISTRAR', 'clinical:registration:create'),
    ('REGISTRAR', 'clinical:registration:cancel'),
    ('REGISTRAR', 'clinical:charge:read'),
    ('REGISTRAR', 'clinical:charge:settle'),
    ('REGISTRAR', 'empi:patient:read'),
    ('REGISTRAR', 'empi:patient:create')
ON CONFLICT DO NOTHING;

-- NURSE：只读就诊（执行医嘱属护理域，后续迁移）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('NURSE', 'clinical:visit:read')
ON CONFLICT DO NOTHING;

-- QC：只读就诊（病历质控需要）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'clinical:visit:read')
ON CONFLICT DO NOTHING;
