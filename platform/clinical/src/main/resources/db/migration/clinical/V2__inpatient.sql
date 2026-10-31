-- ============================================================================
-- 临床核心 V2：住院域（病区 / 床位 / 住院登记 / 转床记录）
--
-- 设计要点：
--   * 住院是"一次就诊（visit_type=INPATIENT）"的延伸：医嘱与费用复用 clinical_svc.visit_order /
--     charge，不另建一套账，保证"医嘱-费用"天然同源、同事务；
--   * 床位占用由「admission 部分唯一索引 + 床位行锁」双保险，禁止一张床同时住两人；
--   * 出院前必须结清（charge.status=PAID），否则出院后无法再对账；
--   * 转床保留完整轨迹（bed_transfer），不覆盖历史。
-- ============================================================================

-- ---- 病区 ----
CREATE TABLE IF NOT EXISTS clinical_svc.ward (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    name       text NOT NULL,
    dept_id    uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE clinical_svc.ward IS '病区（归属科室；床位与住院登记挂在病区下）';

-- ---- 床位 ----
CREATE TABLE IF NOT EXISTS clinical_svc.bed (
    id         uuid PRIMARY KEY,
    ward_id    uuid NOT NULL REFERENCES clinical_svc.ward (id),
    bed_no     text NOT NULL,
    status     text NOT NULL DEFAULT 'FREE'
               CHECK (status IN ('FREE', 'OCCUPIED', 'RESERVED', 'DISABLED')),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_bed_no UNIQUE (ward_id, bed_no)
);
CREATE INDEX IF NOT EXISTS ix_bed_status ON clinical_svc.bed (ward_id, status);
COMMENT ON TABLE clinical_svc.bed IS '床位（状态 FREE/OCCUPIED/RESERVED/DISABLED，占用必须走行锁+CAS）';

-- ---- 住院登记 ----
CREATE TABLE IF NOT EXISTS clinical_svc.admission (
    id               uuid PRIMARY KEY,
    admission_no     text NOT NULL UNIQUE,          -- AYYYYMMDD######
    visit_id         uuid NOT NULL UNIQUE REFERENCES clinical_svc.visit (id),
    patient_id       uuid NOT NULL,
    patient_no       text NOT NULL,
    ward_id          uuid NOT NULL REFERENCES clinical_svc.ward (id),
    bed_id           uuid REFERENCES clinical_svc.bed (id),
    attending_doctor text NOT NULL,
    status           text NOT NULL DEFAULT 'ADMITTED'
                     CHECK (status IN ('ADMITTED', 'IN_HOSPITAL', 'DISCHARGED', 'CANCELLED')),
    admitted_at      timestamptz NOT NULL DEFAULT now(),
    discharged_at    timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_discharged CHECK (
        (status = 'DISCHARGED' AND discharged_at IS NOT NULL) OR (status <> 'DISCHARGED')
    )
);
-- 一张床同时只能有一个在院患者（已出院/已取消不占床）
CREATE UNIQUE INDEX IF NOT EXISTS ux_admission_bed_active
    ON clinical_svc.admission (bed_id)
    WHERE bed_id IS NOT NULL AND status IN ('ADMITTED', 'IN_HOSPITAL');
-- 同一患者同时只能有一次在院登记（防止重复入院产生两套账）
CREATE UNIQUE INDEX IF NOT EXISTS ux_admission_patient_active
    ON clinical_svc.admission (patient_id)
    WHERE status IN ('ADMITTED', 'IN_HOSPITAL');
CREATE INDEX IF NOT EXISTS ix_admission_patient ON clinical_svc.admission (patient_id, admitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_admission_ward ON clinical_svc.admission (ward_id, status);
COMMENT ON TABLE clinical_svc.admission IS '住院登记（状态：ADMITTED→IN_HOSPITAL→DISCHARGED；ADMITTED→CANCELLED）';

-- ---- 转床/转科轨迹 ----
CREATE TABLE IF NOT EXISTS clinical_svc.bed_transfer (
    id           bigserial PRIMARY KEY,
    ts           timestamptz NOT NULL DEFAULT now(),
    admission_id uuid NOT NULL REFERENCES clinical_svc.admission (id),
    from_bed_id  uuid,
    to_bed_id    uuid,
    reason       text NOT NULL,
    operator     text NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_transfer_admission ON clinical_svc.bed_transfer (admission_id, ts);

CREATE SEQUENCE IF NOT EXISTS clinical_svc.admission_no_seq START 1;
