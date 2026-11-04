-- ============================================================================
-- V51：急诊分诊与留观闭环（G36）
--
-- emergency_triage：预检分诊记录，WAITING → REGISTERED → OBSERVING → CLOSED；
--   WAITING 也可因患者自行离院 → LEFT（必须写原因）。
--   * triage_level 1–4：1 濒危 / 2 危重 / 3 急症 / 4 非急症；
--   * 1–2 级自动置绿色通道（green_channel），3–4 级可人工开启；
--   * 生命体征只存本次分诊时点快照，允许缺项；
--   * 挂号/留观后 visit_id 必填，由 CHECK 兜底。
-- emergency_bed：急诊留观/抢救床位，FREE ↔ OCCUPIED；current_observation_id 记录占用来源。
-- emergency_observation：留观记录，OBSERVING → CLOSED；
--   * 一个分诊记录最多一条留观记录；一个床位/一次就诊同时只允许一条 OBSERVING；
--   * 转归：DISCHARGED 离院 / ADMITTED 转住院 / TRANSFERRED 转院 / LEFT 自动离院；
--   * LEFT / TRANSFERRED 必须写转归说明。
-- ============================================================================

-- 急诊科主数据（固定 UUID 便于脚本与运营手册引用）
INSERT INTO clinical_svc.md_dept (id, dept_code, name, type)
VALUES ('55555555-5555-5555-5555-555555555555', 'DEPT-EMERGENCY', '急诊科', 'BOTH')
ON CONFLICT (dept_code) DO NOTHING;

CREATE SEQUENCE IF NOT EXISTS clinical_svc.emergency_triage_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.emergency_observation_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.emergency_triage (
    id              uuid PRIMARY KEY,
    triage_no       text NOT NULL,
    patient_id      uuid NOT NULL,
    patient_no      text,
    patient_name    text,
    dept_id         uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    triage_level    smallint NOT NULL CHECK (triage_level BETWEEN 1 AND 4),
    chief_complaint text NOT NULL,
    temperature     numeric(4, 1)
                    CHECK (temperature IS NULL OR (temperature >= 30.0 AND temperature <= 45.0)),
    pulse           integer CHECK (pulse IS NULL OR (pulse BETWEEN 20 AND 250)),
    respiration     integer CHECK (respiration IS NULL OR (respiration BETWEEN 5 AND 80)),
    systolic        integer CHECK (systolic IS NULL OR (systolic BETWEEN 40 AND 300)),
    diastolic       integer CHECK (diastolic IS NULL OR (diastolic BETWEEN 20 AND 200)),
    spo2            integer CHECK (spo2 IS NULL OR (spo2 BETWEEN 50 AND 100)),
    green_channel   boolean NOT NULL DEFAULT false,
    status          text NOT NULL DEFAULT 'WAITING'
                    CHECK (status IN ('WAITING', 'REGISTERED', 'OBSERVING', 'CLOSED', 'LEFT')),
    visit_id        uuid REFERENCES clinical_svc.visit (id),
    triaged_by      text NOT NULL,
    arrival_at      timestamptz NOT NULL DEFAULT now(),
    triaged_at      timestamptz NOT NULL DEFAULT now(),
    leave_reason    text,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_emergency_triage_no UNIQUE (triage_no),
    CONSTRAINT ck_emergency_triage_visit
        CHECK (status IN ('WAITING', 'LEFT') OR visit_id IS NOT NULL),
    CONSTRAINT ck_emergency_triage_left
        CHECK (status <> 'LEFT' OR leave_reason IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_emergency_triage_status
    ON clinical_svc.emergency_triage (dept_id, status, arrival_at DESC);

CREATE INDEX IF NOT EXISTS ix_emergency_triage_arrival
    ON clinical_svc.emergency_triage (arrival_at DESC);

CREATE INDEX IF NOT EXISTS ix_emergency_triage_patient
    ON clinical_svc.emergency_triage (patient_id, arrival_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.emergency_bed (
    id                     uuid PRIMARY KEY,
    bed_no                 text NOT NULL,
    dept_id                uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    area                   text NOT NULL DEFAULT 'OBSERVE'
                           CHECK (area IN ('RESUS', 'OBSERVE')),
    status                 text NOT NULL DEFAULT 'FREE'
                           CHECK (status IN ('FREE', 'OCCUPIED')),
    current_observation_id uuid,
    enabled                boolean NOT NULL DEFAULT true,
    version                bigint NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_emergency_bed_no UNIQUE (dept_id, bed_no),
    CONSTRAINT ck_emergency_bed_occupied
        CHECK (status = 'FREE' OR current_observation_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS clinical_svc.emergency_observation (
    id             uuid PRIMARY KEY,
    observation_no text NOT NULL,
    triage_id      uuid NOT NULL REFERENCES clinical_svc.emergency_triage (id),
    visit_id       uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id     uuid NOT NULL,
    patient_no     text,
    patient_name   text,
    dept_id        uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    bed_id         uuid NOT NULL REFERENCES clinical_svc.emergency_bed (id),
    status         text NOT NULL DEFAULT 'OBSERVING'
                   CHECK (status IN ('OBSERVING', 'CLOSED')),
    start_at       timestamptz NOT NULL DEFAULT now(),
    end_at         timestamptz,
    outcome        text CHECK (outcome IS NULL OR outcome IN
                   ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'LEFT')),
    outcome_note   text,
    created_by     text NOT NULL,
    closed_by      text,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_emergency_observation_no UNIQUE (observation_no),
    CONSTRAINT ux_emergency_observation_triage UNIQUE (triage_id),
    CONSTRAINT ck_emergency_observation_closed
        CHECK (status = 'OBSERVING'
               OR (end_at IS NOT NULL AND outcome IS NOT NULL AND closed_by IS NOT NULL)),
    CONSTRAINT ck_emergency_observation_outcome_note
        CHECK (outcome IS NULL OR outcome NOT IN ('LEFT', 'TRANSFERRED')
               OR outcome_note IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_emergency_observation_active_bed
    ON clinical_svc.emergency_observation (bed_id)
    WHERE status = 'OBSERVING';

CREATE UNIQUE INDEX IF NOT EXISTS ux_emergency_observation_active_visit
    ON clinical_svc.emergency_observation (visit_id)
    WHERE status = 'OBSERVING';

CREATE INDEX IF NOT EXISTS ix_emergency_observation_status
    ON clinical_svc.emergency_observation (dept_id, status, start_at DESC);

-- 演示床位：2 张抢救区（RESUS）+ 4 张留观区（OBSERVE）
INSERT INTO clinical_svc.emergency_bed (id, bed_no, dept_id, area)
VALUES
    ('66666666-6666-6666-6666-000000000001', 'E-01',
     '55555555-5555-5555-5555-555555555555', 'RESUS'),
    ('66666666-6666-6666-6666-000000000002', 'E-02',
     '55555555-5555-5555-5555-555555555555', 'RESUS'),
    ('66666666-6666-6666-6666-000000000003', 'E-03',
     '55555555-5555-5555-5555-555555555555', 'OBSERVE'),
    ('66666666-6666-6666-6666-000000000004', 'E-04',
     '55555555-5555-5555-5555-555555555555', 'OBSERVE'),
    ('66666666-6666-6666-6666-000000000005', 'E-05',
     '55555555-5555-5555-5555-555555555555', 'OBSERVE'),
    ('66666666-6666-6666-6666-000000000006', 'E-06',
     '55555555-5555-5555-5555-555555555555', 'OBSERVE')
ON CONFLICT (dept_id, bed_no) DO NOTHING;

COMMENT ON TABLE clinical_svc.emergency_triage IS
    '急诊预检分诊：WAITING/REGISTERED/OBSERVING/CLOSED/LEFT，1–2 级自动绿色通道';
COMMENT ON TABLE clinical_svc.emergency_bed IS
    '急诊留观/抢救床位：FREE/OCCUPIED，current_observation_id 指向占用来源';
COMMENT ON TABLE clinical_svc.emergency_observation IS
    '急诊留观记录：OBSERVING/CLOSED，转归 DISCHARGED/ADMITTED/TRANSFERRED/LEFT';
