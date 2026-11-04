-- ============================================================================
-- V12：医技设备质控与结构化报告（G35）
--
-- 设备档案 + 质控记录：
--   * 设备编号唯一；停用（RETIRED）后不允许再登记质控；
--   * 质控记录追加式（禁止改删），按设备/时间可追溯；
--   * 质控结论 PASS/FAIL，FAIL 在设备列表与看板中提示。
--
-- 结构化报告：
--   * report_template 定义字段 schema（JSONB），发布后不可改，只能停用后新建；
--   * exam_report 增加 template_id / structured_json：报告版本保留结构化内容快照，
--     修订生成新版本时一并复制/更新，历史版本不受影响。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS tech_svc.device_no_seq START 1;

CREATE TABLE IF NOT EXISTS tech_svc.device (
    id            uuid PRIMARY KEY,
    device_no     text NOT NULL UNIQUE,
    name          text NOT NULL,
    modality      text NOT NULL
                  CHECK (modality IN ('CT', 'MR', 'US', 'DR', 'CR', 'LIS', 'ECG', 'OTHER')),
    dept_id       uuid,
    location      text,
    manufacturer  text,
    model         text,
    serial_no     text,
    status        text NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
    installed_at  date,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_device_status
    ON tech_svc.device (status, modality);
CREATE INDEX IF NOT EXISTS ix_device_dept
    ON tech_svc.device (dept_id) WHERE dept_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS tech_svc.device_qc (
    id             uuid PRIMARY KEY,
    device_id      uuid NOT NULL REFERENCES tech_svc.device (id),
    qc_type        text NOT NULL
                   CHECK (qc_type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'CALIBRATION')),
    result         text NOT NULL CHECK (result IN ('PASS', 'FAIL')),
    item_name      text NOT NULL,
    expected_value text,
    actual_value   text,
    unit           text,
    performed_by   text NOT NULL,
    performed_at   timestamptz NOT NULL DEFAULT now(),
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_device_qc_device
    ON tech_svc.device_qc (device_id, performed_at DESC);
CREATE INDEX IF NOT EXISTS ix_device_qc_result
    ON tech_svc.device_qc (result, performed_at DESC);

-- 质控记录是原始追溯凭证：只允许 INSERT
DROP TRIGGER IF EXISTS trg_device_qc_append_only ON tech_svc.device_qc;
CREATE TRIGGER trg_device_qc_append_only
    BEFORE UPDATE OR DELETE ON tech_svc.device_qc
    FOR EACH ROW EXECUTE FUNCTION tech_svc.reject_audit_mutation();

CREATE TABLE IF NOT EXISTS tech_svc.report_template (
    id            uuid PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    name          text NOT NULL,
    modality      text NOT NULL
                  CHECK (modality IN ('CT', 'MR', 'US', 'DR', 'CR', 'LIS', 'ECG', 'OTHER')),
    schema_json   jsonb NOT NULL,
    status        text NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_by  text,
    published_at  timestamptz,
    version       bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_report_template_status
    ON tech_svc.report_template (status, modality);

ALTER TABLE tech_svc.exam_report
    ADD COLUMN IF NOT EXISTS template_id uuid REFERENCES tech_svc.report_template (id);
ALTER TABLE tech_svc.exam_report
    ADD COLUMN IF NOT EXISTS structured_json jsonb;

COMMENT ON TABLE tech_svc.device IS '医技设备档案（设备编号唯一，状态 ACTIVE/MAINTENANCE/RETIRED）';
COMMENT ON TABLE tech_svc.device_qc IS '设备质控记录（追加式，按设备/时间追溯）';
COMMENT ON TABLE tech_svc.report_template IS '结构化报告模板（发布后不可改，只能停用）';
COMMENT ON COLUMN tech_svc.exam_report.structured_json IS '结构化报告内容快照（随报告版本保留）';
