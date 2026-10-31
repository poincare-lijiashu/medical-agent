-- ============================================================================
-- V19：签名策略管理权限（N-04）
--
-- 背景：sign_policy 决定各文书归档前必须签到哪一级（电子病历分级评审硬指标），
-- 此前只能改库。N-04 开放管理接口后，必须把"能改审签要求"的人限定为病案质控
-- 与管理员——若授予医师，等于书写人可自行降低归档门槛（与 S-05 同一类风险）。
--
-- 处理：新增 emr:policy:manage，只授予 QC（病案室）与 ADMIN。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:policy:manage', '归档签名策略配置', 'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:policy:manage'),
    ('QC',    'emr:policy:manage')
ON CONFLICT DO NOTHING;

-- 兜底：除病案质控与管理员外，任何岗位都不得持有
DELETE FROM auth_svc.role_permission
 WHERE permission_code = 'emr:policy:manage'
   AND role_code NOT IN ('ADMIN', 'QC');
