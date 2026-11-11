-- ============================================================================
-- FHIR R4 开放层（第 259 批）
--
-- 本服务不是"再存一份业务数据"，而是把既有域模型投影为标准 FHIR 资源：
--   Patient             ← empi_svc.patient / patient_crosswalk
--   Encounter           ← clinical_svc.visit / admission / ward / bed / md_dept
--   Observation         ← tech_svc.critical_value（结构化指标 + 参考范围）
--   MedicationRequest   ← pharmacy_svc.prescription / prescription_item
--
-- 因此这里只需要一张**访问审计表**：谁、何时、以什么查询条件、看了哪个资源。
-- 合规要求（等保三级 / ISO 27799）下，PHI 的每一次读取都必须可追溯，
-- 且审计本身不可篡改，所以：
--   * query_summary 只记录"用了哪些检索参数"，**不记录姓名/证件号等明文**；
--   * 只允许 INSERT，UPDATE/DELETE 由库级触发器拒绝。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS fhir_svc;

CREATE TABLE IF NOT EXISTS fhir_svc.access_log (
    id             bigserial PRIMARY KEY,
    ts             timestamptz NOT NULL DEFAULT now(),
    actor          text NOT NULL,
    action         text NOT NULL
                   CHECK (action IN ('METADATA', 'SEARCH', 'READ')),
    resource_type  text NOT NULL,
    resource_id    text,
    query_summary  text,
    result_count   integer,
    outcome        text NOT NULL
                   CHECK (outcome IN ('OK', 'BAD_REQUEST', 'FORBIDDEN', 'NOT_FOUND', 'ERROR')),
    client_ip      text,
    trace_id       text
);

CREATE INDEX IF NOT EXISTS ix_fhir_access_ts
    ON fhir_svc.access_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_fhir_access_actor
    ON fhir_svc.access_log (actor, ts DESC);
CREATE INDEX IF NOT EXISTS ix_fhir_access_resource
    ON fhir_svc.access_log (resource_type, resource_id);

COMMENT ON TABLE fhir_svc.access_log IS
    'FHIR 读取审计（只允许 INSERT）：谁在何时读了哪个资源、结果如何';
COMMENT ON COLUMN fhir_svc.access_log.query_summary IS
    '检索参数摘要（只记参数名与是否给出，不落姓名/证件号明文）';

CREATE OR REPLACE FUNCTION fhir_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_fhir_access_log_append_only ON fhir_svc.access_log;
CREATE TRIGGER trg_fhir_access_log_append_only
    BEFORE UPDATE OR DELETE ON fhir_svc.access_log
    FOR EACH ROW EXECUTE FUNCTION fhir_svc.reject_audit_mutation();

COMMENT ON FUNCTION fhir_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
