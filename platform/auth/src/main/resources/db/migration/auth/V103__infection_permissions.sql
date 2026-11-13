-- ============================================================================
-- V103：传染病上报权限（§294 / 计划书 W1.9）
--
-- 法定报告是临床与防保共同动作：
--   * 医生/护士（DOCTOR/NURSE）：可看、可补录、可提交/重报；
--   * 质控（QC）：可代为受理/退回/作废与触发扫描（防保科由质控/院感账号承担）；
--   * 运营（OPS_MANAGER）：只读，用于及时率与漏报巡检；
--   * 窗口（REGISTRAR）：不可见（不参与临床诊断与上报）。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('clinical:infection:read', '传染病上报查询', 'clinical'),
    ('clinical:infection:report', '传染病报告卡补录与提交', 'clinical'),
    ('clinical:infection:manage', '传染病上报受理与扫描', 'clinical')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'clinical:infection:read'),
    ('ADMIN', 'clinical:infection:report'),
    ('ADMIN', 'clinical:infection:manage'),
    ('DOCTOR', 'clinical:infection:read'),
    ('DOCTOR', 'clinical:infection:report'),
    ('NURSE', 'clinical:infection:read'),
    ('NURSE', 'clinical:infection:report'),
    ('QC', 'clinical:infection:read'),
    ('QC', 'clinical:infection:manage'),
    ('OPS_MANAGER', 'clinical:infection:read')
ON CONFLICT DO NOTHING;
