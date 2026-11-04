-- ============================================================================
-- V49：出院带药（G30）
--
-- discharge_medication：一次住院登记一份「进行中」的出院带药单
--   DRAFT → SUBMITTED → DISPENSED；DRAFT / SUBMITTED 可取消 → CANCELLED。
--   SUBMITTED 时调用药房内网接口生成 DISPENSE 计费处方（发药时计价），
--   发药结果由临床同步回写（prescription_id / dispensed_at）。
-- discharge_medication_item：药品明细快照（编码/名称/规格/单位/数量/用法/天数）。
--   药品主数据在药房域，本表只保存快照与编码，跨域不建外键。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS clinical_svc.discharge_medication_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.discharge_medication (
    id              uuid PRIMARY KEY,
    medication_no   text NOT NULL,
    admission_id    uuid NOT NULL REFERENCES clinical_svc.admission (id),
    visit_id        uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id      uuid NOT NULL,
    patient_no      text,
    dept_id         uuid REFERENCES clinical_svc.md_dept (id),
    status          text NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'SUBMITTED', 'DISPENSED', 'CANCELLED')),
    note            text,
    prescription_id uuid,
    prescription_no text,
    total_amount    numeric(14, 2) NOT NULL DEFAULT 0,
    submitted_at    timestamptz,
    dispensed_at    timestamptz,
    cancelled_by    text,
    cancelled_at    timestamptz,
    cancel_reason   text,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_discharge_medication_no UNIQUE (medication_no),
    CONSTRAINT ck_discharge_med_submitted
        CHECK (status = 'DRAFT' OR submitted_at IS NOT NULL),
    CONSTRAINT ck_discharge_med_cancelled
        CHECK (status <> 'CANCELLED' OR (cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL))
);

-- 同一住院登记只允许一份进行中的出院带药（草稿或已提交未发药）
CREATE UNIQUE INDEX IF NOT EXISTS ux_discharge_med_active_admission
    ON clinical_svc.discharge_medication (admission_id)
    WHERE status IN ('DRAFT', 'SUBMITTED');

CREATE INDEX IF NOT EXISTS ix_discharge_med_status
    ON clinical_svc.discharge_medication (status, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.discharge_medication_item (
    id            uuid PRIMARY KEY,
    medication_id uuid NOT NULL
                  REFERENCES clinical_svc.discharge_medication (id) ON DELETE CASCADE,
    seq           integer NOT NULL CHECK (seq BETWEEN 1 AND 99),
    drug_code     text NOT NULL,
    drug_name     text NOT NULL,
    spec          text,
    unit          text,
    quantity      integer NOT NULL CHECK (quantity BETWEEN 1 AND 100000),
    dosage        text,
    frequency     text,
    days          integer CHECK (days IS NULL OR days BETWEEN 1 AND 365),
    CONSTRAINT ux_discharge_med_item_slot UNIQUE (medication_id, seq)
);

COMMENT ON TABLE clinical_svc.discharge_medication IS
    '出院带药单：提交药房生成 DISPENSE 计费处方，发药后同步 DISPENSED';
COMMENT ON TABLE clinical_svc.discharge_medication_item IS
    '出院带药明细快照：只存药品编码与名称，主数据归药房域';
