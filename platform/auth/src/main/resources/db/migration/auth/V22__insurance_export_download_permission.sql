-- ============================================================================
-- V22：报盘报文下载独立权限（第四轮 MF-02）
--
-- 背景：报盘 payload 一次最多携带 500 张清单的患者号/项目明细（批量 PHI），
-- 此前只需 insurance:settlement:read（DOCTOR/QC/REGISTRAR 均有），
-- 与"对外报盘属医保办职责"的定位不符，也与 S-07 的 PHI 最小化原则冲突。
--
-- 处理：新增 insurance:export:download，只授予医保办与管理员；
-- 批次列表/摘要（不含 PHI）仍只需 insurance:settlement:read。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:export:download', '医保报盘报文下载', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN',             'insurance:export:download'),
    ('INSURANCE_OFFICER', 'insurance:export:download')
ON CONFLICT DO NOTHING;

DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'insurance:export:download'
   AND role_code NOT IN ('ADMIN', 'INSURANCE_OFFICER');
