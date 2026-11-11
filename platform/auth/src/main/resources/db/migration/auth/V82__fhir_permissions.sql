-- ============================================================================
-- V82：FHIR R4 开放层权限（第 259 批）
--
--   fhir:read       读取 FHIR 资源（Patient/Encounter/Observation/MedicationRequest）
--   fhir:read_phi   在 FHIR 响应中返回患者明文标识（姓名/出生日期/患者号/跨系统标识）
--
-- 授权口径：
--   * 只授予 ADMIN 与 INTEGRATION：FHIR 是院级对外接口，不按科室切分；
--   * 数据仍按院区收敛（网关注入 X-Campus），多院区集成账号需绑定 campuses；
--   * 普通医师/护士没有该权限——他们用控制台页面，页面侧有自己的脱敏与审计口径。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('fhir:read', 'FHIR 资源读取', 'integration'),
    ('fhir:read_phi', 'FHIR 患者明文标识', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'fhir:read'),
    ('ADMIN', 'fhir:read_phi'),
    ('INTEGRATION', 'fhir:read'),
    ('INTEGRATION', 'fhir:read_phi')
ON CONFLICT DO NOTHING;
