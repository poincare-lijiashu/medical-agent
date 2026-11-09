-- ============================================================================
-- V78：医生可查看本人接诊就诊的费用构成（只读）
--
-- 背景（第 132 批真机验证发现）：医嘱工作台的「费用构成」调用
-- GET /clinical/visits/{id}/charge，所需权限 clinical:charge:read 此前只授予
-- ADMIN / REGISTRAR，DOCTOR 打开工作台会看到"收费单读取失败"。
-- 医生在开立医嘱时需要看到本次就诊已产生的费用（计价是否生效、是否重复计费），
-- 这是工作台设计的既有能力；结算（settle）仍只给 REGISTRAR/ADMIN，不在本迁移范围。
--
-- 幂等：重复执行不产生重复授权。
-- ============================================================================

INSERT INTO auth_svc.role_permission (role_code, permission_code)
SELECT 'DOCTOR', 'clinical:charge:read'
WHERE EXISTS (SELECT 1 FROM auth_svc.permission WHERE code = 'clinical:charge:read')
ON CONFLICT DO NOTHING;
