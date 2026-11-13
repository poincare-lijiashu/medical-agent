-- ============================================================================
-- V104：IHE XDS 文档共享权限（§295 / 计划书 W3.5）
--
-- 授权口径：
--   * 集成（INTEGRATION）：注册外部文档 + 检索/调阅，可读患者明文标识；
--   * 医生（DOCTOR）：从院内数据生成出院小结/检验报告 + 检索/调阅；
--   * 质控（QC）：检索/调阅 + 停用错误元数据（ITI-62），便于质量复核；
--   * 护士（NURSE）：只读调阅（需要患者号才能核对身份）；
--   * 运营（OPS_MANAGER）：只读，用于共享量巡检，不返回患者明文；
--   * 窗口（REGISTRAR）：不授予（不参与文档共享）。
--
-- 与 FHIR 资源权限（fhir:read / fhir:read_phi）**分开**：
-- 能读 FHIR 资源不等于能调阅共享文档，反之亦然。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('fhir:xds:read', '共享文档检索与调阅', 'integration'),
    ('fhir:xds:phi', '共享文档患者明文标识', 'integration'),
    ('fhir:xds:register', '共享文档注册（ITI-41）', 'integration'),
    ('fhir:xds:generate', '共享文档生成（出院小结/检验报告）', 'integration'),
    ('fhir:xds:manage', '共享文档元数据停用（ITI-62）', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'fhir:xds:read'),
    ('ADMIN', 'fhir:xds:phi'),
    ('ADMIN', 'fhir:xds:register'),
    ('ADMIN', 'fhir:xds:generate'),
    ('ADMIN', 'fhir:xds:manage'),
    ('INTEGRATION', 'fhir:xds:read'),
    ('INTEGRATION', 'fhir:xds:phi'),
    ('INTEGRATION', 'fhir:xds:register'),
    ('DOCTOR', 'fhir:xds:read'),
    ('DOCTOR', 'fhir:xds:phi'),
    ('DOCTOR', 'fhir:xds:generate'),
    ('QC', 'fhir:xds:read'),
    ('QC', 'fhir:xds:phi'),
    ('QC', 'fhir:xds:generate'),
    ('QC', 'fhir:xds:manage'),
    ('NURSE', 'fhir:xds:read'),
    ('NURSE', 'fhir:xds:phi'),
    ('OPS_MANAGER', 'fhir:xds:read')
ON CONFLICT DO NOTHING;
