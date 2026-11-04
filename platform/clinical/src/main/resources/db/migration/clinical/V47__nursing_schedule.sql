-- ============================================================================
-- V47：护理排班（G26）
--
-- nursing_shift：班次字典（白/夜/中），可维护起止时间与是否跨午夜。
-- nursing_schedule：科室 + 日期 + 班次 + 护士的排班明细。
--   * 同一科室同一天同一班次同一护士唯一（防重复录入）；
--   * 同一护士同一天只能排一个班（nurse_no 非空时由唯一索引兜底，避免跨科重复排班）；
--   * 状态 DRAFT → PUBLISHED → CANCELLED，取消必须写原因并留痕。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.nursing_shift (
    id             uuid PRIMARY KEY,
    code           text NOT NULL,
    name           text NOT NULL,
    start_time     time NOT NULL,
    end_time       time NOT NULL,
    cross_midnight boolean NOT NULL DEFAULT false,
    enabled        boolean NOT NULL DEFAULT true,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_nursing_shift_code UNIQUE (code)
);

INSERT INTO clinical_svc.nursing_shift (id, code, name, start_time, end_time, cross_midnight)
VALUES
    (gen_random_uuid(), 'NIGHT', '夜班', '00:00', '08:00', false),
    (gen_random_uuid(), 'DAY',   '白班', '08:00', '16:00', false),
    (gen_random_uuid(), 'EVENING', '中班', '16:00', '23:59', false)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS clinical_svc.nursing_schedule (
    id            uuid PRIMARY KEY,
    dept_id       uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    shift_code    text NOT NULL REFERENCES clinical_svc.nursing_shift (code),
    work_date     date NOT NULL,
    nurse_name    text NOT NULL,
    nurse_no      text,
    status        text NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    note          text,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    cancelled_by  text,
    cancelled_at  timestamptz,
    cancel_reason text,
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_nursing_schedule_slot UNIQUE (dept_id, work_date, shift_code, nurse_name),
    CONSTRAINT ck_nursing_schedule_nurse_no
        CHECK (nurse_no IS NULL OR btrim(nurse_no) <> '')
);

-- 同一护士同一天只能排一个班（NULL 不参与唯一约束，未填工号时不做跨科拦截）
CREATE UNIQUE INDEX IF NOT EXISTS ux_nursing_schedule_nurse_day
    ON clinical_svc.nursing_schedule (work_date, nurse_no)
    WHERE nurse_no IS NOT NULL AND status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS ix_nursing_schedule_dept_date
    ON clinical_svc.nursing_schedule (dept_id, work_date, shift_code);

COMMENT ON TABLE clinical_svc.nursing_schedule IS
    '护理排班：同科室同班次同护士唯一；护士工号非空时同一天只能排一个班';
