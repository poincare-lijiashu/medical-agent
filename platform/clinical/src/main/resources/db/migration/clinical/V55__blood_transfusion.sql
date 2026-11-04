-- ============================================================================
-- V55：输血闭环（G40）
--
-- transfusion_request：输血申请，REQUESTED → CROSSMATCHED → ISSUED → TRANSFUSING → COMPLETED；
--   REQUESTED / CROSSMATCHED 可取消 → CANCELLED（必须写原因）。
-- blood_crossmatch：配血记录（可多次尝试，一次申请最多一条 COMPATIBLE）；
--   血袋号全局唯一，配血不合可以换袋重配。
-- transfusion_record：输注记录（一次申请一份），双人核对后开始，TRANSFUSING → COMPLETED。
-- transfusion_reaction：输血不良反应上报（一次输注一份）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.blood_product (
    id         uuid PRIMARY KEY,
    code       text NOT NULL,
    name       text NOT NULL,
    unit       text NOT NULL,
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_blood_product_code UNIQUE (code)
);

CREATE SEQUENCE IF NOT EXISTS clinical_svc.transfusion_request_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.crossmatch_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.transfusion_record_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.transfusion_reaction_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_request (
    id             uuid PRIMARY KEY,
    request_no     text NOT NULL,
    admission_id   uuid NOT NULL REFERENCES clinical_svc.admission (id),
    visit_id       uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id     uuid NOT NULL,
    patient_no     text,
    patient_name   text,
    gender         text,
    birth_date     date,
    ward_id        uuid REFERENCES clinical_svc.ward (id),
    ward_name      text,
    bed_no         text,
    component_id   uuid NOT NULL REFERENCES clinical_svc.blood_product (id),
    component_code text NOT NULL,
    component_name text NOT NULL,
    quantity       numeric(6, 2) NOT NULL CHECK (quantity >= 0.5 AND quantity <= 50),
    unit           text NOT NULL,
    blood_type     text NOT NULL CHECK (blood_type IN ('A', 'B', 'AB', 'O')),
    rh             text NOT NULL CHECK (rh IN ('POSITIVE', 'NEGATIVE')),
    hemoglobin     numeric(5, 1) CHECK (hemoglobin IS NULL OR hemoglobin BETWEEN 20 AND 250),
    platelet       integer CHECK (platelet IS NULL OR platelet BETWEEN 0 AND 1000),
    urgency        text NOT NULL DEFAULT 'ROUTINE'
                   CHECK (urgency IN ('ROUTINE', 'URGENT', 'EMERGENCY')),
    indication     text NOT NULL,
    status         text NOT NULL DEFAULT 'REQUESTED'
                   CHECK (status IN ('REQUESTED', 'CROSSMATCHED', 'ISSUED', 'TRANSFUSING',
                                     'COMPLETED', 'CANCELLED')),
    crossmatch_id  uuid,
    bag_no         text,
    bag_expire_at  date,
    requested_by   text NOT NULL,
    requested_at   timestamptz NOT NULL DEFAULT now(),
    issued_by      text,
    issued_at      timestamptz,
    started_by     text,
    started_at     timestamptz,
    completed_by   text,
    completed_at   timestamptz,
    cancel_reason  text,
    cancelled_by   text,
    cancelled_at   timestamptz,
    created_by     text NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_transfusion_request_no UNIQUE (request_no),
    CONSTRAINT ck_transfusion_request_crossmatched
        CHECK (status IN ('REQUESTED', 'CANCELLED')
               OR (crossmatch_id IS NOT NULL AND bag_no IS NOT NULL)),
    CONSTRAINT ck_transfusion_request_completed
        CHECK (status <> 'COMPLETED'
               OR (completed_by IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_transfusion_request_cancelled
        CHECK (status <> 'CANCELLED'
               OR (cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL
                   AND cancelled_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_transfusion_request_status
    ON clinical_svc.transfusion_request (status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_transfusion_request_patient
    ON clinical_svc.transfusion_request (patient_id, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.blood_crossmatch (
    id               uuid PRIMARY KEY,
    crossmatch_no    text NOT NULL,
    request_id       uuid NOT NULL REFERENCES clinical_svc.transfusion_request (id),
    bag_no           text NOT NULL,
    donor_blood_type text NOT NULL CHECK (donor_blood_type IN ('A', 'B', 'AB', 'O')),
    donor_rh         text NOT NULL CHECK (donor_rh IN ('POSITIVE', 'NEGATIVE')),
    crossmatch_result text NOT NULL CHECK (crossmatch_result IN ('COMPATIBLE', 'INCOMPATIBLE')),
    method           text NOT NULL,
    bag_expire_at    date NOT NULL,
    note             text,
    tested_by        text NOT NULL,
    tested_at        timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_crossmatch_no UNIQUE (crossmatch_no),
    CONSTRAINT ux_crossmatch_bag UNIQUE (bag_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_crossmatch_compatible_request
    ON clinical_svc.blood_crossmatch (request_id)
    WHERE crossmatch_result = 'COMPATIBLE';

CREATE INDEX IF NOT EXISTS ix_crossmatch_request
    ON clinical_svc.blood_crossmatch (request_id, tested_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_record (
    id               uuid PRIMARY KEY,
    record_no        text NOT NULL,
    request_id       uuid NOT NULL REFERENCES clinical_svc.transfusion_request (id),
    bag_no           text NOT NULL,
    status           text NOT NULL DEFAULT 'TRANSFUSING'
                     CHECK (status IN ('TRANSFUSING', 'COMPLETED')),
    start_at         timestamptz NOT NULL DEFAULT now(),
    end_at           timestamptz,
    volume_ml        numeric(8, 2) CHECK (volume_ml IS NULL OR volume_ml > 0),
    start_temperature numeric(4, 1)
                     CHECK (start_temperature IS NULL OR start_temperature BETWEEN 30 AND 45),
    start_pulse      integer CHECK (start_pulse IS NULL OR start_pulse BETWEEN 20 AND 250),
    start_systolic   integer CHECK (start_systolic IS NULL OR start_systolic BETWEEN 40 AND 300),
    start_diastolic  integer CHECK (start_diastolic IS NULL OR start_diastolic BETWEEN 20 AND 200),
    end_temperature  numeric(4, 1)
                     CHECK (end_temperature IS NULL OR end_temperature BETWEEN 30 AND 45),
    end_pulse        integer CHECK (end_pulse IS NULL OR end_pulse BETWEEN 20 AND 250),
    end_systolic     integer CHECK (end_systolic IS NULL OR end_systolic BETWEEN 40 AND 300),
    end_diastolic    integer CHECK (end_diastolic IS NULL OR end_diastolic BETWEEN 20 AND 200),
    reaction         boolean NOT NULL DEFAULT false,
    checker_a        text NOT NULL,
    checker_b        text NOT NULL,
    checked_at       timestamptz NOT NULL DEFAULT now(),
    started_by       text NOT NULL,
    completed_by     text,
    completed_at     timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_transfusion_record_no UNIQUE (record_no),
    CONSTRAINT ux_transfusion_record_request UNIQUE (request_id),
    CONSTRAINT ck_transfusion_record_double_check CHECK (checker_a <> checker_b),
    CONSTRAINT ck_transfusion_record_completed
        CHECK (status <> 'COMPLETED'
               OR (end_at IS NOT NULL AND completed_by IS NOT NULL
                   AND completed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS clinical_svc.transfusion_reaction (
    id           uuid PRIMARY KEY,
    reaction_no  text NOT NULL,
    record_id    uuid NOT NULL REFERENCES clinical_svc.transfusion_record (id),
    request_id   uuid NOT NULL REFERENCES clinical_svc.transfusion_request (id),
    severity     text NOT NULL CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE')),
    symptoms     text NOT NULL,
    action_taken text,
    reported_by  text NOT NULL,
    reported_at  timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_transfusion_reaction_no UNIQUE (reaction_no),
    CONSTRAINT ux_transfusion_reaction_record UNIQUE (record_id)
);

-- 血液成分字典
INSERT INTO clinical_svc.blood_product (id, code, name, unit) VALUES
    (gen_random_uuid(), 'BLOOD-RBC',  '去白细胞红细胞', 'U'),
    (gen_random_uuid(), 'BLOOD-PLASMA', '新鲜冰冻血浆', 'ml'),
    (gen_random_uuid(), 'BLOOD-PLT',  '单采血小板',   '治疗量'),
    (gen_random_uuid(), 'BLOOD-CRYO', '冷沉淀凝血因子', 'U')
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE clinical_svc.transfusion_request IS
    '输血申请：REQUESTED→CROSSMATCHED→ISSUED→TRANSFUSING→COMPLETED，可取消';
COMMENT ON TABLE clinical_svc.blood_crossmatch IS
    '配血记录：一次申请最多一条 COMPATIBLE，不合可换袋重配';
COMMENT ON TABLE clinical_svc.transfusion_record IS
    '输注记录：双人核对（checker_a<>checker_b）后开始，TRANSFUSING→COMPLETED';
COMMENT ON TABLE clinical_svc.transfusion_reaction IS
    '输血不良反应：一次输注一份，分级与处理留痕';
