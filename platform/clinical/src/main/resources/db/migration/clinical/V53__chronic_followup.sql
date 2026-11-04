-- ============================================================================
-- V53：随访与慢病管理（G38）
--
-- chronic_disease：慢病病种字典（编码唯一，可停用）。
-- chronic_record：慢病档案，ACTIVE → CLOSED；
--   同一患者同一病种同时只允许一份 ACTIVE 档案（部分唯一索引兜底）。
-- chronic_followup_plan：随访计划，ACTIVE → STOPPED；
--   一份档案同时只允许一个 ACTIVE 计划，interval_days 7–365 天。
-- chronic_followup_task：随访任务，PENDING → DONE / CANCELLED；
--   一个计划同时只允许一条 PENDING 任务；完成后由同一事务生成下一周期任务。
-- chronic_followup：随访记录（追加式），任务唯一，记录血压/血糖/体重/症状/依从性。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.chronic_disease (
    id         uuid PRIMARY KEY,
    code       text NOT NULL,
    name       text NOT NULL,
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_chronic_disease_code UNIQUE (code)
);

CREATE SEQUENCE IF NOT EXISTS clinical_svc.chronic_record_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.chronic_plan_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.chronic_task_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.chronic_followup_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.chronic_record (
    id                uuid PRIMARY KEY,
    record_no         text NOT NULL,
    patient_id        uuid NOT NULL,
    patient_no        text,
    patient_name      text,
    gender            text,
    birth_date        date,
    disease_id        uuid NOT NULL REFERENCES clinical_svc.chronic_disease (id),
    disease_code      text NOT NULL,
    disease_name      text NOT NULL,
    diagnosis_date    date NOT NULL,
    risk_level        text NOT NULL DEFAULT 'MEDIUM'
                      CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    managing_dept_id  uuid REFERENCES clinical_svc.md_dept (id),
    manager           text,
    note              text,
    close_reason      text,
    closed_by         text,
    closed_at         timestamptz,
    created_by        text NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_chronic_record_no UNIQUE (record_no),
    CONSTRAINT ck_chronic_record_closed
        CHECK (status <> 'CLOSED'
               OR (close_reason IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_chronic_record_active_patient_disease
    ON clinical_svc.chronic_record (patient_id, disease_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_chronic_record_status
    ON clinical_svc.chronic_record (status, risk_level, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.chronic_followup_plan (
    id           uuid PRIMARY KEY,
    plan_no      text NOT NULL,
    chronic_id   uuid NOT NULL REFERENCES clinical_svc.chronic_record (id),
    interval_days integer NOT NULL CHECK (interval_days BETWEEN 7 AND 365),
    next_date    date NOT NULL,
    status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'STOPPED')),
    stop_reason  text,
    stopped_by   text,
    stopped_at   timestamptz,
    created_by   text NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_chronic_plan_no UNIQUE (plan_no),
    CONSTRAINT ck_chronic_plan_stopped
        CHECK (status <> 'STOPPED'
               OR (stop_reason IS NOT NULL AND stopped_by IS NOT NULL AND stopped_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_chronic_plan_active_record
    ON clinical_svc.chronic_followup_plan (chronic_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS clinical_svc.chronic_followup_task (
    id            uuid PRIMARY KEY,
    task_no       text NOT NULL,
    plan_id       uuid NOT NULL REFERENCES clinical_svc.chronic_followup_plan (id),
    chronic_id    uuid NOT NULL REFERENCES clinical_svc.chronic_record (id),
    patient_id    uuid NOT NULL,
    patient_no    text,
    patient_name  text,
    disease_name  text NOT NULL,
    due_date      date NOT NULL,
    status        text NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'DONE', 'CANCELLED')),
    completed_at  timestamptz,
    followup_id   uuid,
    cancel_reason text,
    cancelled_by  text,
    cancelled_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_chronic_task_no UNIQUE (task_no),
    CONSTRAINT ck_chronic_task_done
        CHECK (status <> 'DONE' OR (completed_at IS NOT NULL AND followup_id IS NOT NULL)),
    CONSTRAINT ck_chronic_task_cancelled
        CHECK (status <> 'CANCELLED'
               OR (cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_chronic_task_pending_plan
    ON clinical_svc.chronic_followup_task (plan_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS ix_chronic_task_due
    ON clinical_svc.chronic_followup_task (status, due_date);

CREATE TABLE IF NOT EXISTS clinical_svc.chronic_followup (
    id                   uuid PRIMARY KEY,
    followup_no          text NOT NULL,
    task_id              uuid NOT NULL REFERENCES clinical_svc.chronic_followup_task (id),
    chronic_id           uuid NOT NULL REFERENCES clinical_svc.chronic_record (id),
    patient_id           uuid NOT NULL,
    patient_no           text,
    patient_name         text,
    followup_date        date NOT NULL,
    method               text NOT NULL CHECK (method IN ('PHONE', 'OUTPATIENT', 'HOME', 'ONLINE')),
    systolic             integer CHECK (systolic IS NULL OR (systolic BETWEEN 40 AND 300)),
    diastolic            integer CHECK (diastolic IS NULL OR (diastolic BETWEEN 20 AND 200)),
    fasting_glucose      numeric(5, 2) CHECK (fasting_glucose IS NULL OR fasting_glucose BETWEEN 0.5 AND 50),
    weight               numeric(5, 2) CHECK (weight IS NULL OR weight BETWEEN 1 AND 400),
    symptoms             text,
    medication_adherence text CHECK (medication_adherence IS NULL
                                     OR medication_adherence IN ('GOOD', 'PARTIAL', 'POOR')),
    advice               text,
    created_by           text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_chronic_followup_no UNIQUE (followup_no),
    CONSTRAINT ux_chronic_followup_task UNIQUE (task_id),
    CONSTRAINT ck_chronic_followup_bp
        CHECK ((systolic IS NULL AND diastolic IS NULL)
               OR (systolic IS NOT NULL AND diastolic IS NOT NULL AND systolic > diastolic))
);

CREATE INDEX IF NOT EXISTS ix_chronic_followup_record
    ON clinical_svc.chronic_followup (chronic_id, followup_date DESC);

-- 慢病病种种子
INSERT INTO clinical_svc.chronic_disease (id, code, name) VALUES
    (gen_random_uuid(), 'CD-HYP',  '高血压'),
    (gen_random_uuid(), 'CD-DM2',  '2型糖尿病'),
    (gen_random_uuid(), 'CD-CHD',  '冠心病'),
    (gen_random_uuid(), 'CD-COPD', '慢性阻塞性肺疾病'),
    (gen_random_uuid(), 'CD-CKD',  '慢性肾脏病')
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE clinical_svc.chronic_record IS
    '慢病档案：同一患者同一病种同时只允许一份 ACTIVE 档案';
COMMENT ON TABLE clinical_svc.chronic_followup_task IS
    '随访任务：一个计划同时只允许一条 PENDING，完成后生成下一周期任务';
COMMENT ON TABLE clinical_svc.chronic_followup IS
    '随访记录：追加式，记录血压/血糖/体重/症状/用药依从性';
