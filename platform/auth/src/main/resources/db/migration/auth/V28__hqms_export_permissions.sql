-- ============================================================================
-- V28：病案首页 HQMS 上报权限点
--
-- 上报与报文下载只授予病案质控（QC）与管理员：
--   * 生成/回执/撤销：emr:hqms:export
--   * 报文下载（整批含患者姓名与诊断，批量 PHI）：emr:hqms:download
-- 医师/护士不参与上报，故不授予。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:hqms:export',   '病案首页上报（生成/回执/撤销）', 'emr'),
    ('emr:hqms:download', '病案首页上报报文下载',           'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'ADMIN', p.code FROM auth_svc.permission p
 WHERE p.code IN ('emr:hqms:export', 'emr:hqms:download')
ON CONFLICT DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'emr:hqms:export'),
    ('QC', 'emr:hqms:download')
ON CONFLICT DO NOTHING;
