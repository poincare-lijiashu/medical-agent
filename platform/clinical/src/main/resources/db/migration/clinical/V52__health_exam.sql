-- ============================================================================
-- V52：体检中心（G37）
--
-- health_exam_package：体检套餐，DRAFT → PUBLISHED → DISABLED；
--   发布后套餐内容不可改，已登记的体检按项目快照执行。
-- health_exam_package_item：套餐项目（收费项目编码 + 名称/类别/价格快照 + 可选检查科室）。
-- health_exam_registration：体检登记，REGISTERED → IN_PROGRESS → EXAM_DONE → REPORTED；
--   REGISTERED/IN_PROGRESS 可取消 → CANCELLED（必须写原因）。
-- health_exam_item：登记时按套餐生成的项目快照，PENDING → COMPLETED / SKIPPED；
--   跳过（未检）必须写原因；完成/跳过均记录执行人与时间。
-- health_exam_report：总检报告，一次登记一份；DRAFT → FINAL，FINAL 后不可修改。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.health_exam_package (
    id           uuid PRIMARY KEY,
    package_code text NOT NULL,
    name         text NOT NULL,
    description  text,
    total_price  numeric(14, 2) NOT NULL DEFAULT 0 CHECK (total_price >= 0),
    status       text NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    created_by   text NOT NULL,
    published_by text,
    published_at timestamptz,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_health_package_code UNIQUE (package_code),
    CONSTRAINT ck_health_package_published
        CHECK (status <> 'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_health_package_status
    ON clinical_svc.health_exam_package (status, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.health_exam_package_item (
    id            uuid PRIMARY KEY,
    package_id    uuid NOT NULL REFERENCES clinical_svc.health_exam_package (id) ON DELETE CASCADE,
    seq           integer NOT NULL CHECK (seq BETWEEN 1 AND 99),
    item_code     text NOT NULL,
    item_name     text NOT NULL,
    item_category text NOT NULL,
    dept_id       uuid REFERENCES clinical_svc.md_dept (id),
    dept_name     text,
    price         numeric(14, 2) NOT NULL DEFAULT 0 CHECK (price >= 0),
    CONSTRAINT ux_health_package_item_slot UNIQUE (package_id, seq),
    CONSTRAINT ux_health_package_item_code UNIQUE (package_id, item_code)
);

CREATE SEQUENCE IF NOT EXISTS clinical_svc.health_exam_no_seq;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.health_report_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.health_exam_registration (
    id            uuid PRIMARY KEY,
    exam_no       text NOT NULL,
    package_id    uuid NOT NULL REFERENCES clinical_svc.health_exam_package (id),
    package_name  text NOT NULL,
    package_price numeric(14, 2) NOT NULL DEFAULT 0 CHECK (package_price >= 0),
    patient_id    uuid NOT NULL,
    patient_no    text,
    patient_name  text,
    gender        text,
    birth_date    date,
    status        text NOT NULL DEFAULT 'REGISTERED'
                  CHECK (status IN ('REGISTERED', 'IN_PROGRESS', 'EXAM_DONE', 'REPORTED', 'CANCELLED')),
    note          text,
    cancel_reason text,
    cancelled_by  text,
    cancelled_at  timestamptz,
    created_by    text NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_health_registration_no UNIQUE (exam_no),
    CONSTRAINT ck_health_registration_cancelled
        CHECK (status <> 'CANCELLED'
               OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
                   AND cancel_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_health_registration_status
    ON clinical_svc.health_exam_registration (status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_health_registration_patient
    ON clinical_svc.health_exam_registration (patient_id, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.health_exam_item (
    id             uuid PRIMARY KEY,
    registration_id uuid NOT NULL
                   REFERENCES clinical_svc.health_exam_registration (id) ON DELETE CASCADE,
    seq            integer NOT NULL CHECK (seq BETWEEN 1 AND 99),
    item_code      text NOT NULL,
    item_name      text NOT NULL,
    item_category  text NOT NULL,
    dept_id        uuid REFERENCES clinical_svc.md_dept (id),
    dept_name      text,
    price          numeric(14, 2) NOT NULL DEFAULT 0 CHECK (price >= 0),
    status         text NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'COMPLETED', 'SKIPPED')),
    result_summary text,
    result_note    text,
    abnormal       boolean NOT NULL DEFAULT false,
    completed_by   text,
    completed_at   timestamptz,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_health_item_slot UNIQUE (registration_id, seq),
    CONSTRAINT ck_health_item_done
        CHECK (status = 'PENDING' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_health_item_skipped_reason
        CHECK (status <> 'SKIPPED' OR result_note IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_health_item_registration
    ON clinical_svc.health_exam_item (registration_id, seq);

CREATE TABLE IF NOT EXISTS clinical_svc.health_exam_report (
    id              uuid PRIMARY KEY,
    report_no       text NOT NULL,
    registration_id uuid NOT NULL
                    REFERENCES clinical_svc.health_exam_registration (id),
    conclusion      text NOT NULL,
    suggestion      text,
    abnormal_count  integer NOT NULL DEFAULT 0 CHECK (abnormal_count >= 0),
    status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'FINAL')),
    created_by      text NOT NULL,
    finalized_by    text,
    finalized_at    timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_health_report_no UNIQUE (report_no),
    CONSTRAINT ux_health_report_registration UNIQUE (registration_id),
    CONSTRAINT ck_health_report_final
        CHECK (status = 'DRAFT' OR (finalized_by IS NOT NULL AND finalized_at IS NOT NULL))
);

-- ---- 演示套餐：基础体检（血常规 + 血清钾 + 胸部 CT）----
INSERT INTO clinical_svc.health_exam_package
    (id, package_code, name, description, total_price, status, created_by, published_by, published_at)
VALUES
    ('77777777-7777-7777-7777-777777777777', 'PKG-BASIC', '基础体检套餐',
     '血常规、血清钾、胸部 CT 平扫', 293.00, 'PUBLISHED', 'system', 'system', now())
ON CONFLICT (package_code) DO NOTHING;

INSERT INTO clinical_svc.health_exam_package_item
    (id, package_id, seq, item_code, item_name, item_category, price)
SELECT gen_random_uuid(), '77777777-7777-7777-7777-777777777777', v.seq,
       i.item_code, i.name, i.category, i.price
  FROM (VALUES ('EXM-CBC', 1), ('LAB-K', 2), ('EXM-CT-CHEST', 3)) AS v(code, seq)
  JOIN clinical_svc.md_charge_item i ON i.item_code = v.code
 WHERE i.enabled
ON CONFLICT (package_id, item_code) DO NOTHING;

COMMENT ON TABLE clinical_svc.health_exam_package IS
    '体检套餐：发布后内容不可改，登记时按项目快照执行';
COMMENT ON TABLE clinical_svc.health_exam_registration IS
    '体检登记：REGISTERED/IN_PROGRESS/EXAM_DONE/REPORTED/CANCELLED';
COMMENT ON TABLE clinical_svc.health_exam_report IS
    '总检报告：一次登记一份，DRAFT→FINAL 后不可修改';
