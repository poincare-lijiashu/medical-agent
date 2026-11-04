-- ============================================================================
-- V54：移动护理与条码闭环（G39）
--
-- nursing_mobile_task：移动护理核对任务
--   * task_type：MEDICATION 给药核对 / SPECIMEN 标本采集核对；
--   * source_type：ORDER（从长期药品医嘱生成）/ MANUAL（手工建单）；
--   * status：PENDING → VERIFIED / EXCEPTION；
--   * expected_patient_barcode 为腕带条码（患者号），expected_item_barcode 为药品/标本条码；
--   * 同一医嘱同一天只允许一条任务（部分唯一索引兜底）。
-- nursing_scan_event：扫码事件（追加式，UPDATE/DELETE 由触发器拒绝）。
--   每次核对都会落 PATIENT / ITEM 两条事件，匹配与否均保留，用于事后追溯。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS clinical_svc.mobile_task_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.nursing_mobile_task (
    id                      uuid PRIMARY KEY,
    task_no                 text NOT NULL,
    task_date               date NOT NULL,
    task_type               text NOT NULL CHECK (task_type IN ('MEDICATION', 'SPECIMEN')),
    admission_id            uuid NOT NULL REFERENCES clinical_svc.admission (id),
    visit_id                uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id              uuid NOT NULL,
    patient_no              text,
    patient_name            text,
    ward_id                 uuid REFERENCES clinical_svc.ward (id),
    ward_name               text,
    bed_no                  text,
    source_type             text NOT NULL DEFAULT 'MANUAL'
                            CHECK (source_type IN ('ORDER', 'MANUAL')),
    source_ref              text,
    order_id                uuid,
    item_code               text NOT NULL,
    item_name               text NOT NULL,
    expected_patient_barcode text NOT NULL,
    expected_item_barcode   text NOT NULL,
    status                  text NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'VERIFIED', 'EXCEPTION', 'CANCELLED')),
    attempts                integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_scanned_at         timestamptz,
    verified_by             text,
    verified_at             timestamptz,
    exception_reason        text,
    exception_by            text,
    exception_at            timestamptz,
    note                    text,
    created_by              text NOT NULL,
    version                 bigint NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_mobile_task_no UNIQUE (task_no),
    CONSTRAINT ck_mobile_task_verified
        CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CONSTRAINT ck_mobile_task_exception
        CHECK (status <> 'EXCEPTION'
               OR (exception_reason IS NOT NULL AND exception_by IS NOT NULL
                   AND exception_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_mobile_task_order_day
    ON clinical_svc.nursing_mobile_task (order_id, task_date)
    WHERE order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_mobile_task_day
    ON clinical_svc.nursing_mobile_task (task_date, status, task_type);

CREATE INDEX IF NOT EXISTS ix_mobile_task_ward
    ON clinical_svc.nursing_mobile_task (ward_id, task_date, status);

CREATE TABLE IF NOT EXISTS clinical_svc.nursing_scan_event (
    id           uuid PRIMARY KEY,
    task_id      uuid NOT NULL
                 REFERENCES clinical_svc.nursing_mobile_task (id) ON DELETE CASCADE,
    scan_type    text NOT NULL CHECK (scan_type IN ('PATIENT', 'ITEM')),
    scanned_value text NOT NULL,
    matched      boolean NOT NULL,
    scanned_by   text NOT NULL,
    scanned_at   timestamptz NOT NULL DEFAULT now(),
    note         text
);

CREATE INDEX IF NOT EXISTS ix_nursing_scan_task
    ON clinical_svc.nursing_scan_event (task_id, scanned_at DESC);

DROP TRIGGER IF EXISTS trg_nursing_scan_append_only ON clinical_svc.nursing_scan_event;
CREATE TRIGGER trg_nursing_scan_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.nursing_scan_event
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

COMMENT ON TABLE clinical_svc.nursing_mobile_task IS
    '移动护理核对任务：MEDICATION 给药 / SPECIMEN 标本，扫码后 VERIFIED 或异常关闭';
COMMENT ON TABLE clinical_svc.nursing_scan_event IS
    '扫码事件：追加式，每次核对保留患者/项目两条记录，禁止改删';
