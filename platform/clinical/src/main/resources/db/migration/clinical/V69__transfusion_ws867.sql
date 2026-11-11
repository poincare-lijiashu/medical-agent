-- ============================================================================
-- V69：输血闭环补齐 WS/T 867—2025（第 264 批）
--
-- 既有 V55 已覆盖 申请→配血→发血→输注→不良反应 的状态机；本迁移补齐标准要求
-- 但此前没有证据链的三段：
--   transfusion_precheck       输血前评估：指征、血常规/凝血、输血前免疫筛查、
--                              知情同意签字（发血前必须具备）
--   transfusion_issue          发血核对：发血者/核对者不同人、取血人、血液外观与血袋完整性
--   transfusion_observation    输注观察：输注中生命体征/滴速/反应标记（15 分钟观察）
-- 另外把配血细化为主侧/次侧、抗体筛查、患者 ABO/RhD 复核；
-- 不良反应补类型、上报对象与闭环状态。
--
-- 证据表全部追加式：库级触发器拒绝 UPDATE/DELETE（复用 V22 的
-- reject_audit_mutation()），只能新增，不能改历史。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_precheck (
    request_id          uuid PRIMARY KEY
                        REFERENCES clinical_svc.transfusion_request (id),
    assessed_by         text NOT NULL,
    assessed_at         timestamptz NOT NULL,
    indication          text NOT NULL,
    hemoglobin          numeric(5, 1)
                        CHECK (hemoglobin IS NULL OR hemoglobin BETWEEN 20 AND 250),
    hematocrit          numeric(4, 1)
                        CHECK (hematocrit IS NULL OR hematocrit BETWEEN 5 AND 80),
    platelet            integer CHECK (platelet IS NULL OR platelet BETWEEN 0 AND 1000),
    coagulation         text,
    patient_abo         text CHECK (patient_abo IS NULL OR patient_abo IN ('A', 'B', 'AB', 'O')),
    patient_rhd         text CHECK (patient_rhd IS NULL OR patient_rhd IN ('POSITIVE', 'NEGATIVE')),
    antibody_screen     text CHECK (antibody_screen IS NULL
                                    OR antibody_screen IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN')),
    hbsag               text CHECK (hbsag IS NULL OR hbsag IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN')),
    hcv                 text CHECK (hcv IS NULL OR hcv IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN')),
    hiv                 text CHECK (hiv IS NULL OR hiv IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN')),
    syphilis            text CHECK (syphilis IS NULL
                                    OR syphilis IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN')),
    consent_signed_by   text NOT NULL,
    consent_signed_at   timestamptz NOT NULL,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.transfusion_precheck IS
    '输血前评估与知情同意（WS/T 867）：发血前必须具备，证据只增不改';

DROP TRIGGER IF EXISTS trg_transfusion_precheck_append_only
    ON clinical_svc.transfusion_precheck;
CREATE TRIGGER trg_transfusion_precheck_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.transfusion_precheck
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_issue (
    request_id   uuid PRIMARY KEY REFERENCES clinical_svc.transfusion_request (id),
    issued_by    text NOT NULL,
    checked_by   text NOT NULL,
    fetched_by   text NOT NULL,
    appearance   text NOT NULL CHECK (appearance IN ('NORMAL', 'ABNORMAL')),
    bag_intact   boolean NOT NULL,
    issued_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfusion_issue_double_check CHECK (issued_by <> checked_by),
    CONSTRAINT ck_transfusion_issue_quality
        CHECK (appearance = 'NORMAL' AND bag_intact)
);

COMMENT ON TABLE clinical_svc.transfusion_issue IS
    '发血核对：发血/核对双人、取血人、血液外观与血袋完整性；异常血袋不允许发血';

CREATE OR REPLACE FUNCTION clinical_svc.guard_transfusion_issue_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是发血核对证据表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_transfusion_issue_append_only ON clinical_svc.transfusion_issue;
CREATE TRIGGER trg_transfusion_issue_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.transfusion_issue
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_transfusion_issue_append_only();

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_observation (
    id             uuid PRIMARY KEY,
    record_id      uuid NOT NULL REFERENCES clinical_svc.transfusion_record (id),
    observed_at    timestamptz NOT NULL,
    temperature    numeric(4, 1)
                   CHECK (temperature IS NULL OR temperature BETWEEN 30 AND 45),
    pulse          integer CHECK (pulse IS NULL OR pulse BETWEEN 20 AND 250),
    systolic       integer CHECK (systolic IS NULL OR systolic BETWEEN 40 AND 300),
    diastolic      integer CHECK (diastolic IS NULL OR diastolic BETWEEN 20 AND 200),
    drip_rate      integer CHECK (drip_rate IS NULL OR drip_rate BETWEEN 1 AND 300),
    reaction_flag  boolean NOT NULL DEFAULT false,
    note           text,
    observed_by    text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfusion_observation_bp
        CHECK ((systolic IS NULL) = (diastolic IS NULL))
);

CREATE INDEX IF NOT EXISTS ix_transfusion_observation_record
    ON clinical_svc.transfusion_observation (record_id, observed_at);

COMMENT ON TABLE clinical_svc.transfusion_observation IS
    '输注观察记录：至少一条（15 分钟观察）才允许完成输注，观察记录只增不改';

DROP TRIGGER IF EXISTS trg_transfusion_observation_append_only
    ON clinical_svc.transfusion_observation;
CREATE TRIGGER trg_transfusion_observation_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.transfusion_observation
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

-- ---------------------------------------------------------------- 配血细化

ALTER TABLE clinical_svc.blood_crossmatch
    ADD COLUMN IF NOT EXISTS patient_abo text
        CHECK (patient_abo IS NULL OR patient_abo IN ('A', 'B', 'AB', 'O')),
    ADD COLUMN IF NOT EXISTS patient_rhd text
        CHECK (patient_rhd IS NULL OR patient_rhd IN ('POSITIVE', 'NEGATIVE')),
    ADD COLUMN IF NOT EXISTS major_result text
        CHECK (major_result IS NULL OR major_result IN ('COMPATIBLE', 'INCOMPATIBLE')),
    ADD COLUMN IF NOT EXISTS minor_result text
        CHECK (minor_result IS NULL OR minor_result IN ('COMPATIBLE', 'INCOMPATIBLE')),
    ADD COLUMN IF NOT EXISTS antibody_screen text
        CHECK (antibody_screen IS NULL
               OR antibody_screen IN ('NEGATIVE', 'POSITIVE', 'UNKNOWN'));

-- ---------------------------------------------------------------- 不良反应闭环

ALTER TABLE clinical_svc.transfusion_reaction
    ADD COLUMN IF NOT EXISTS reaction_type text NOT NULL DEFAULT 'OTHER'
        CHECK (reaction_type IN ('ALLERGIC', 'FEBRILE_NON_HEMOLYTIC', 'HEMOLYTIC',
                                 'TACO', 'TRALI', 'INFECTION', 'OTHER')),
    ADD COLUMN IF NOT EXISTS reported_to text NOT NULL DEFAULT 'HOSPITAL'
        CHECK (reported_to IN ('HOSPITAL', 'BLOOD_CENTER', 'BOTH')),
    ADD COLUMN IF NOT EXISTS report_status text NOT NULL DEFAULT 'REPORTED'
        CHECK (report_status IN ('REPORTED', 'CLOSED')),
    ADD COLUMN IF NOT EXISTS closed_by text,
    ADD COLUMN IF NOT EXISTS closed_at timestamptz;

ALTER TABLE clinical_svc.transfusion_reaction
    ADD CONSTRAINT ck_transfusion_reaction_closed
        CHECK (report_status <> 'CLOSED'
               OR (closed_by IS NOT NULL AND closed_at IS NOT NULL));
