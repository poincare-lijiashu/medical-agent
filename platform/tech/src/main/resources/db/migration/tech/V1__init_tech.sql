-- ============================================================================
-- 医技域（tech_svc）初始表结构：申请 / 标本 / 报告 / 危急值
--
-- 设计约束：
--   * 申请号、标本条码、报告号全部唯一；
--   * 一张申请最多一个标本（部分唯一索引不适用，直接 UNIQUE(application_id)）；
--   * 报告采用"追加版本"：同一申请可有多条报告记录，is_current 只允许一条为真；
--   * 危急值必须经临床确认（ACKNOWLEDGED）才能关闭；关闭申请前不允许存在未关闭危急值；
--   * 所有写操作落 tech_change_log（追加式，不提供删除接口）。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS tech_svc;

-- ---- 检查/检验申请 ----
CREATE TABLE IF NOT EXISTS tech_svc.exam_application (
    id            uuid PRIMARY KEY,
    application_no text NOT NULL UNIQUE,                          -- APYYYYMMDD######
    patient_id    uuid NOT NULL,
    patient_no    text NOT NULL,
    visit_id      uuid,
    visit_no      text,
    dept_id       uuid,
    applied_by    text NOT NULL,
    category      text NOT NULL CHECK (category IN ('EXAM', 'LAB')),
    item_code     text NOT NULL,
    item_name     text NOT NULL,
    body_part     text,                                           -- 检查部位（检验可空）
    priority      text NOT NULL DEFAULT 'ROUTINE'
                  CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    clinical_diag text,
    specimen_type text,                                           -- 标本类型（检验必填）
    status        text NOT NULL DEFAULT 'APPLIED'
                  CHECK (status IN ('APPLIED', 'SPECIMEN_COLLECTED', 'RECEIVED',
                                    'IN_PROGRESS', 'REPORTED', 'CLOSED', 'CANCELLED')),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_application_lab_specimen CHECK (
        category <> 'LAB' OR (specimen_type IS NOT NULL AND specimen_type <> '')
    )
);
CREATE INDEX IF NOT EXISTS ix_application_status
    ON tech_svc.exam_application (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_application_patient
    ON tech_svc.exam_application (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_application_visit
    ON tech_svc.exam_application (visit_id) WHERE visit_id IS NOT NULL;

-- ---- 标本 ----
CREATE TABLE IF NOT EXISTS tech_svc.specimen (
    id             uuid PRIMARY KEY,
    application_id uuid NOT NULL UNIQUE REFERENCES tech_svc.exam_application (id),
    barcode        text NOT NULL UNIQUE,
    specimen_type  text NOT NULL,
    status         text NOT NULL DEFAULT 'COLLECTED'
                   CHECK (status IN ('COLLECTED', 'RECEIVED', 'REJECTED')),
    collected_by   text NOT NULL,
    collected_at   timestamptz NOT NULL DEFAULT now(),
    received_by    text,
    received_at    timestamptz,
    reject_reason  text,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_specimen_reject CHECK (
        (status = 'REJECTED' AND reject_reason IS NOT NULL) OR status <> 'REJECTED'
    )
);
CREATE INDEX IF NOT EXISTS ix_specimen_status ON tech_svc.specimen (status, collected_at DESC);

-- ---- 报告（追加版本） ----
CREATE TABLE IF NOT EXISTS tech_svc.exam_report (
    id             uuid PRIMARY KEY,
    report_no      text NOT NULL,                                  -- RPYYYYMMDD######（同号多版本）
    application_id uuid NOT NULL REFERENCES tech_svc.exam_application (id),
    revision       int NOT NULL CHECK (revision > 0),
    is_current     boolean NOT NULL DEFAULT true,
    findings       text NOT NULL,                                  -- 所见
    conclusion     text NOT NULL,                                  -- 结论/印象
    reporter       text NOT NULL,
    reviewer       text,
    reviewed_at    timestamptz,
    status         text NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT', 'FINAL', 'AMENDED')),
    critical_flag  boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_report_no_revision UNIQUE (report_no, revision),
    CONSTRAINT ux_report_application_revision UNIQUE (application_id, revision)
);
-- 同一申请只允许一条"当前"报告
CREATE UNIQUE INDEX IF NOT EXISTS ux_report_current
    ON tech_svc.exam_report (application_id) WHERE is_current;
CREATE INDEX IF NOT EXISTS ix_report_created ON tech_svc.exam_report (created_at DESC);

-- ---- 危急值 ----
CREATE TABLE IF NOT EXISTS tech_svc.critical_value (
    id             uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES tech_svc.exam_application (id),
    report_id      uuid NOT NULL REFERENCES tech_svc.exam_report (id),
    item_name      text NOT NULL,                                  -- 指标名（如 血钾）
    result_value   text NOT NULL,                                  -- 结果值（含单位）
    reference_range text,                                          -- 参考范围
    severity       text NOT NULL DEFAULT 'HIGH'
                   CHECK (severity IN ('LOW', 'HIGH', 'CRITICAL')),
    status         text NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'ACKNOWLEDGED', 'CLOSED')),
    notified_to    text,                                           -- 被告知人（技师填写）
    notified_at    timestamptz,
    acknowledged_by text,                                          -- 临床确认人
    acknowledged_at timestamptz,
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_critical_ack CHECK (
        (status = 'PENDING'
            AND acknowledged_by IS NULL AND acknowledged_at IS NULL)
        OR (status IN ('ACKNOWLEDGED', 'CLOSED')
            AND acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_critical_status
    ON tech_svc.critical_value (status, created_at);
CREATE INDEX IF NOT EXISTS ix_critical_application
    ON tech_svc.critical_value (application_id);

-- ---- 审计（追加式） ----
CREATE TABLE IF NOT EXISTS tech_svc.tech_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_tech_change_target
    ON tech_svc.tech_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_tech_change_ts ON tech_svc.tech_change_log (ts DESC);

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS tech_svc.application_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS tech_svc.report_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS tech_svc.barcode_seq START 1;

-- ============================================================================
-- 初始主数据（示例，上线前必须由院内医技项目字典（物价/LIS/PACS）同步覆盖）
-- ============================================================================
CREATE TABLE IF NOT EXISTS tech_svc.md_exam_item (
    id          uuid PRIMARY KEY,
    item_code   text NOT NULL UNIQUE,
    name        text NOT NULL,
    category    text NOT NULL CHECK (category IN ('EXAM', 'LAB')),
    specimen_type text,
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO tech_svc.md_exam_item (id, item_code, name, category, specimen_type)
VALUES
    ('b1111111-1111-1111-1111-111111111111', 'EXM-CBC', '血常规', 'LAB', '全血'),
    ('b2222222-2222-2222-2222-222222222222', 'LAB-K', '血清钾测定', 'LAB', '血清'),
    ('b3333333-3333-3333-3333-333333333333', 'EXM-CT-CHEST', '胸部CT平扫', 'EXAM', NULL)
ON CONFLICT (item_code) DO NOTHING;
