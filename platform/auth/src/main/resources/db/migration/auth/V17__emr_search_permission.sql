-- ============================================================================
-- V17：病历全文检索独立权限（S-07）
--
-- 背景（第三轮评测 S-07）：CDR 检索此前只要 emr:record:read 即可，
-- 护士（NURSE）与窗口（REGISTRAR）能对全院任意患者做正文检索，属 PHI 越权访问面。
--
-- 处理：新增 emr:record:search，只授予医师、质控与管理员；
-- 查阅单份病历（emr:record:read）不受影响，护士/窗口仍可按病历号查自己护理/打印所需文书。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:record:search', '病历全文检索（CDR）', 'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',  'emr:record:search'),
    ('DOCTOR', 'emr:record:search'),
    ('QC',     'emr:record:search')
ON CONFLICT DO NOTHING;

-- 兜底：确保历史误授的岗位被收回
DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'emr:record:search'
   AND role_code NOT IN ('ADMIN', 'DOCTOR', 'QC');
