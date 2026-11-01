-- ============================================================================
-- 病历域 V10：病案首页（HQMS / DRG 前置）
--
-- 定位：一次住院一份首页，内容是"生成时从临床域取回的确定性快照"——
--   诊断、费用汇总、出院时间一旦落库不再随源数据变化（否则事后补录费用会让
--   已归档首页与结算数据对不上）。
--
-- 边界（与临床域的分工）：
--   * 临床域是诊断与费用的唯一真源，首页只做只读快照，不提供反向写入；
--   * 病案室在首页上补录的是"首页专有"信息（离院方式、血型、手术操作、医师签名、编码员）；
--   * 费用明细与诊断的差异必须回临床域修正后重新生成首页，不做两套账。
--
-- 状态机：DRAFT → SUBMITTED → ARCHIVED（不可回退，数据库触发器兜底）。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS emr_svc.homepage_no_seq START 1;

CREATE TABLE IF NOT EXISTS emr_svc.record_homepage (
    id              uuid PRIMARY KEY,
    homepage_no     text NOT NULL UNIQUE,                   -- HPYYYYMMDD######
    visit_id        uuid NOT NULL,
    admission_id    uuid,
    patient_id      uuid NOT NULL,
    patient_no      text NOT NULL,
    patient_name    text NOT NULL,                          -- 生成时快照（患者改名不影响历史首页）
    visit_no        text,
    dept_id         uuid,
    dept_name       text,
    ward_id         uuid,
    ward_name       text,
    bed_no          text,
    admitted_at     timestamptz,
    discharged_at   timestamptz,
    stay_days       int NOT NULL DEFAULT 0 CHECK (stay_days >= 0),
    attending_doctor text,                                  -- 住院主治（临床域带入，可在首页修正）

    -- ---- 首页专有信息（病案室补录）----
    admission_way   text CHECK (admission_way IN ('OUTPATIENT', 'EMERGENCY', 'TRANSFER')),
    discharge_mode  text CHECK (discharge_mode IN (
                        'BY_DOCTOR', 'TRANSFER', 'TRANSFER_HOME', 'NON_DOCTOR', 'DEATH', 'OTHER')),
    readmit_plan    text NOT NULL DEFAULT 'NONE' CHECK (readmit_plan IN ('NONE', 'WITHIN_31_DAYS')),
    marital_status  text CHECK (marital_status IN ('MARRIED', 'SINGLE', 'DIVORCED', 'WIDOWED', 'UNKNOWN')),
    occupation      text,
    nation          text,
    blood_type      text CHECK (blood_type IN ('A', 'B', 'O', 'AB', 'UNKNOWN')),
    rh_factor       text CHECK (rh_factor IN ('POSITIVE', 'NEGATIVE', 'UNKNOWN')),
    rescue_count    int NOT NULL DEFAULT 0 CHECK (rescue_count >= 0),
    rescue_success_count int NOT NULL DEFAULT 0 CHECK (rescue_success_count >= 0),
    critical_case   boolean NOT NULL DEFAULT false,          -- 是否危重
    hospital_infection boolean NOT NULL DEFAULT false,       -- 是否院内感染
    chief_physician     text,                                -- 主任医师
    resident_physician  text,                                -- 住院医师
    coder               text,                                -- 病案编码员

    -- ---- 快照（生成时固化，提交后不可改）----
    diagnoses       jsonb NOT NULL DEFAULT '[]'::jsonb,      -- [{code,name,primary}]
    procedures      jsonb NOT NULL DEFAULT '[]'::jsonb,      -- 手术操作（当前无手术域，病案室录入）
    fee_summary     jsonb NOT NULL DEFAULT '{}'::jsonb,      -- {REGISTRATION: 10.00, DRUG: 62.5, ...}
    total_amount    numeric(14, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),

    status          text NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'SUBMITTED', 'ARCHIVED')),
    submitted_by    text,
    submitted_at    timestamptz,
    archived_by     text,
    archived_at     timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- 一次住院只有一份首页：重复生成由数据库直接拒绝（并发也不会产生两份）
    CONSTRAINT ux_homepage_visit UNIQUE (visit_id),
    CONSTRAINT ck_homepage_rescue CHECK (rescue_success_count <= rescue_count),
    CONSTRAINT ck_homepage_submitted CHECK (
        (status IN ('SUBMITTED', 'ARCHIVED') AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL)
        OR status = 'DRAFT'
    ),
    CONSTRAINT ck_homepage_archived CHECK (
        (status = 'ARCHIVED' AND archived_by IS NOT NULL AND archived_at IS NOT NULL)
        OR status <> 'ARCHIVED'
    )
);

CREATE INDEX IF NOT EXISTS ix_homepage_patient
    ON emr_svc.record_homepage (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_homepage_status
    ON emr_svc.record_homepage (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_homepage_dept
    ON emr_svc.record_homepage (dept_id, discharged_at DESC);

COMMENT ON TABLE emr_svc.record_homepage IS
    '病案首页：一次住院一份；诊断/费用/出院时间是临床域快照，首页专有信息由病案室补录';

-- ---- 状态不可回退 + 已提交快照不可修改 ----
CREATE OR REPLACE FUNCTION emr_svc.guard_homepage() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'ARCHIVED'
       AND (NEW.status <> 'ARCHIVED'
            OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
            OR NEW.total_amount <> OLD.total_amount) THEN
        RAISE EXCEPTION '病案首页已归档，不允许退回或修改快照'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'SUBMITTED'
       AND (NEW.status = 'DRAFT'
            OR NEW.visit_id <> OLD.visit_id
            OR NEW.patient_id <> OLD.patient_id) THEN
        RAISE EXCEPTION '病案首页已提交，不允许退回草稿或更换就诊'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'SUBMITTED'
       AND (NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
            OR NEW.total_amount <> OLD.total_amount
            OR NEW.discharged_at IS DISTINCT FROM OLD.discharged_at) THEN
        RAISE EXCEPTION '病案首页已提交，临床快照（诊断/费用/出院时间）不可修改'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_homepage_guard ON emr_svc.record_homepage;
CREATE TRIGGER trg_homepage_guard
    BEFORE UPDATE ON emr_svc.record_homepage
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_homepage();

COMMENT ON FUNCTION emr_svc.guard_homepage() IS
    '病案首页兜底：状态不可回退；已提交/已归档后诊断与费用快照不可修改（应用层 CAS 之外的数据库保险）';
