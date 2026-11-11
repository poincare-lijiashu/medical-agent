-- ============================================================================
-- V71：护理敏感质量指标（第 266 批，G1 患者安全 / 护理质量）
--
-- 设计口径：
--   * 指标字典由服务端固定（编码/分类/计算方式/分母/单位/方向），客户端只能选择，
--     不能自造指标或改写口径，避免"换个数就变好看"；
--   * 不良事件按**发生时间**入库；班次归属由服务端按 nursing_shift 的时间窗解析：
--       - 跨午夜班次（cross_midnight=true）在午夜后发生的事件归属到班次开始日；
--       - 未指定班次时自动匹配，时间不落在任何班次窗内直接拒绝，避免随手挂班；
--       - 指定班次但发生时间不在该班次窗内同样拒绝，防止人为把事件挪到"好看的班次"；
--   * 事件表、设备日表都是追加式证据（触发器拒绝 UPDATE/DELETE）；
--     设备日如需修正只能追加新记录，按 recorded_at 取最新一条作为当前值；
--   * 上报按科室 + 周期（月/季/年）生成指标快照：DRAFT → SUBMITTED → ACCEPTED/RETURNED，
--     审核人与提交人必须不同（职责分离），退回必须写原因；
--   * 分母缺失时比值为 NULL 并标记 dataComplete=false，绝不以 0 冒充"零发生"。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS clinical_svc.nursing_indicator_event_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.nursing_indicator_report_no_seq;

-- ---- 指标字典 ----
CREATE TABLE IF NOT EXISTS clinical_svc.nursing_indicator_def (
    id               uuid PRIMARY KEY,
    code             text NOT NULL,
    name             text NOT NULL,
    category         text NOT NULL CHECK (category IN ('SAFETY', 'STRUCTURE', 'PROCESS')),
    calc_type        text NOT NULL CHECK (calc_type IN ('EVENT_RATE', 'STRUCTURE', 'TIMELINESS')),
    denominator_type text NOT NULL
                     CHECK (denominator_type IN ('PATIENT_DAYS', 'DEVICE_DAYS', 'NURSE_COUNT', 'EVENT_COUNT')),
    device_type      text CHECK (device_type IN ('VENTILATOR', 'URINARY_CATHETER', 'CENTRAL_LINE')),
    unit             text NOT NULL,
    direction        text NOT NULL CHECK (direction IN ('LOWER_BETTER', 'HIGHER_BETTER', 'NONE')),
    target_value     numeric(12, 4),
    sort_no          integer NOT NULL DEFAULT 0,
    enabled          boolean NOT NULL DEFAULT true,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_nursing_indicator_def_code UNIQUE (code),
    CONSTRAINT ck_nursing_indicator_def_device
        CHECK (denominator_type <> 'DEVICE_DAYS' OR device_type IS NOT NULL)
);

COMMENT ON TABLE clinical_svc.nursing_indicator_def IS
    '护理敏感指标字典：口径由服务端固定，客户端只可选择指标编码';

INSERT INTO clinical_svc.nursing_indicator_def
    (id, code, name, category, calc_type, denominator_type, device_type, unit, direction,
     target_value, sort_no)
VALUES
    (gen_random_uuid(), 'FALL_RATE', '跌倒/坠床发生率', 'SAFETY', 'EVENT_RATE',
     'PATIENT_DAYS', NULL, '‰', 'LOWER_BETTER', NULL, 10),
    (gen_random_uuid(), 'PRESSURE_INJURY_RATE', '院内压力性损伤发生率', 'SAFETY', 'EVENT_RATE',
     'PATIENT_DAYS', NULL, '‰', 'LOWER_BETTER', NULL, 20),
    (gen_random_uuid(), 'UNPLANNED_EXTUBATION_RATE', '非计划拔管发生率', 'SAFETY', 'EVENT_RATE',
     'DEVICE_DAYS', 'VENTILATOR', '‰', 'LOWER_BETTER', NULL, 30),
    (gen_random_uuid(), 'MEDICATION_ERROR_RATE', '给药错误发生率', 'SAFETY', 'EVENT_RATE',
     'PATIENT_DAYS', NULL, '‰', 'LOWER_BETTER', NULL, 40),
    (gen_random_uuid(), 'CATHETER_INFECTION_RATE', '导管相关尿路感染发生率', 'SAFETY',
     'EVENT_RATE', 'DEVICE_DAYS', 'URINARY_CATHETER', '‰', 'LOWER_BETTER', NULL, 50),
    (gen_random_uuid(), 'CRBSI_RATE', '中心导管相关血流感染发生率', 'SAFETY', 'EVENT_RATE',
     'DEVICE_DAYS', 'CENTRAL_LINE', '‰', 'LOWER_BETTER', NULL, 60),
    (gen_random_uuid(), 'REPORT_TIMELINESS', '护理不良事件上报及时率', 'PROCESS', 'TIMELINESS',
     'EVENT_COUNT', NULL, '%', 'HIGHER_BETTER', NULL, 70),
    (gen_random_uuid(), 'BED_NURSE_RATIO', '床护比', 'STRUCTURE', 'STRUCTURE',
     'NURSE_COUNT', NULL, '床/护', 'NONE', NULL, 80),
    (gen_random_uuid(), 'NURSE_PATIENT_RATIO', '护患比', 'STRUCTURE', 'STRUCTURE',
     'NURSE_COUNT', NULL, '患/护', 'LOWER_BETTER', NULL, 90)
ON CONFLICT (code) DO NOTHING;

-- ---- 不良事件（追加式） ----
CREATE TABLE IF NOT EXISTS clinical_svc.nursing_indicator_event (
    id             uuid PRIMARY KEY,
    event_no       text NOT NULL,
    indicator_code text NOT NULL
                   REFERENCES clinical_svc.nursing_indicator_def (code),
    dept_id        uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    ward_id        uuid REFERENCES clinical_svc.ward (id),
    admission_id   uuid REFERENCES clinical_svc.admission (id),
    patient_no     text,
    occurred_at    timestamptz NOT NULL,
    discovered_at  timestamptz,
    reported_at    timestamptz NOT NULL,
    shift_date     date NOT NULL,
    shift_code     text NOT NULL REFERENCES clinical_svc.nursing_shift (code),
    shift_source   text NOT NULL DEFAULT 'AUTO'
                   CHECK (shift_source IN ('AUTO', 'SPECIFIED')),
    severity       text NOT NULL
                   CHECK (severity IN ('NEAR_MISS', 'NO_HARM', 'MILD', 'MODERATE',
                                       'SEVERE', 'DEATH')),
    detail         jsonb NOT NULL DEFAULT '{}'::jsonb,
    note           text,
    reported_by    text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_nursing_indicator_event_no UNIQUE (event_no),
    CONSTRAINT ck_nursing_indicator_event_time
        CHECK (occurred_at <= reported_at + interval '5 minutes'),
    CONSTRAINT ck_nursing_indicator_event_discovered
        CHECK (discovered_at IS NULL OR discovered_at >= occurred_at - interval '5 minutes')
);

CREATE INDEX IF NOT EXISTS ix_nursing_indicator_event_dept_time
    ON clinical_svc.nursing_indicator_event (dept_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_nursing_indicator_event_shift
    ON clinical_svc.nursing_indicator_event (dept_id, shift_date, shift_code);
CREATE INDEX IF NOT EXISTS ix_nursing_indicator_event_code
    ON clinical_svc.nursing_indicator_event (indicator_code, occurred_at);

DROP TRIGGER IF EXISTS trg_nursing_indicator_event_append_only
    ON clinical_svc.nursing_indicator_event;
CREATE TRIGGER trg_nursing_indicator_event_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.nursing_indicator_event
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

COMMENT ON TABLE clinical_svc.nursing_indicator_event IS
    '护理不良事件证据：追加式；跨午夜班次归属班次开始日，修正只能追加新记录';

-- ---- 设备日（分母，追加式） ----
CREATE TABLE IF NOT EXISTS clinical_svc.nursing_indicator_denominator (
    id          uuid PRIMARY KEY,
    dept_id     uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    stat_date   date NOT NULL,
    device_type text NOT NULL
                CHECK (device_type IN ('VENTILATOR', 'URINARY_CATHETER', 'CENTRAL_LINE')),
    count       numeric(12, 2) NOT NULL CHECK (count >= 0 AND count <= 100000),
    note        text,
    recorded_by text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS ix_nursing_indicator_denominator_dept_date
    ON clinical_svc.nursing_indicator_denominator (dept_id, stat_date, device_type);

DROP TRIGGER IF EXISTS trg_nursing_indicator_denominator_append_only
    ON clinical_svc.nursing_indicator_denominator;
CREATE TRIGGER trg_nursing_indicator_denominator_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.nursing_indicator_denominator
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

COMMENT ON TABLE clinical_svc.nursing_indicator_denominator IS
    '护理指标设备日分母：追加式，按 recorded_at 最新一条为当前值（修正=追加新记录）';

-- ---- 上报（周期快照） ----
CREATE TABLE IF NOT EXISTS clinical_svc.nursing_indicator_report (
    id            uuid PRIMARY KEY,
    report_no     text NOT NULL,
    dept_id       uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    period_type   text NOT NULL CHECK (period_type IN ('MONTH', 'QUARTER', 'YEAR')),
    period_key    text NOT NULL,
    period_start  date NOT NULL,
    period_end    date NOT NULL,
    status        text NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT', 'SUBMITTED', 'ACCEPTED', 'RETURNED')),
    patient_days  numeric(14, 2) NOT NULL DEFAULT 0,
    event_count   integer NOT NULL DEFAULT 0,
    timely_count  integer NOT NULL DEFAULT 0,
    nurse_days    numeric(14, 2) NOT NULL DEFAULT 0,
    metrics       jsonb NOT NULL DEFAULT '[]'::jsonb,
    warnings      jsonb NOT NULL DEFAULT '[]'::jsonb,
    generated_by  text NOT NULL,
    generated_at  timestamptz NOT NULL DEFAULT now(),
    submitted_by  text,
    submitted_at  timestamptz,
    reviewed_by   text,
    reviewed_at   timestamptz,
    review_note   text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_nursing_indicator_report_no UNIQUE (report_no),
    CONSTRAINT ux_nursing_indicator_report_period UNIQUE (dept_id, period_type, period_key),
    CONSTRAINT ck_nursing_indicator_report_period
        CHECK (period_start <= period_end),
    CONSTRAINT ck_nursing_indicator_report_submitted
        CHECK (status IN ('DRAFT', 'RETURNED')
               OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_nursing_indicator_report_reviewed
        CHECK (status NOT IN ('ACCEPTED', 'RETURNED')
               OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_nursing_indicator_report_dept_period
    ON clinical_svc.nursing_indicator_report (dept_id, period_start DESC, status);

COMMENT ON TABLE clinical_svc.nursing_indicator_report IS
    '护理指标周期上报：DRAFT→SUBMITTED→ACCEPTED/RETURNED；审核人与提交人必须不同';
