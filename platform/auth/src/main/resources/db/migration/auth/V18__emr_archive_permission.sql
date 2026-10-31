-- ============================================================================
-- V18：病历归档权从医师收敛到病案质控（S-05）
--
-- 背景（第三轮评测）：DOCTOR 同时拥有书写、签名与归档权，等于"自查自归档"，
-- 三级签名若在同一个账号下完成，归档这一终态动作就失去了复核意义。
--
-- 处理：撤销 DOCTOR 的 emr:record:archive，授予 QC（病案室）与 ADMIN。
-- 医师仍可书写/提交/签名/修订，归档由病案室执行；如院内流程要求"科室归档"，
-- 应通过新建病案室角色授权，而不是把归档权退回给书写人。
-- ============================================================================

DELETE FROM auth_svc.role_permission
 WHERE role_code = 'DOCTOR'
   AND permission_code = 'emr:record:archive';

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC',    'emr:record:archive'),
    ('ADMIN', 'emr:record:archive')
ON CONFLICT DO NOTHING;
