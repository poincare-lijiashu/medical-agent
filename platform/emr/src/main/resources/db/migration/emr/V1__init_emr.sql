-- ============================================================================
-- 病历域（emr_svc）初始表结构：模板 / 病历 / 签名 / 修订历史 / 质控
--
-- 不可篡改设计：
--   * 签名时对渲染文本计算 SHA-256（content_hash），事后可校验；
--   * 签名后只能"修订"：历史版本写入 record_amendment，正文被新版本覆盖但旧内容留档；
--   * 归档是终态（ARCHIVED），应用层拒绝任何修改；
--   * 每次状态迁移都带版本号 CAS。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS emr_svc;

-- ---- 病历模板（结构化字段定义）----
CREATE TABLE IF NOT EXISTS emr_svc.md_template (
    id          uuid PRIMARY KEY,
    template_code text NOT NULL UNIQUE,
    name        text NOT NULL,
    doc_type    text NOT NULL CHECK (doc_type IN (
                    'ADMISSION_NOTE', 'FIRST_COURSE', 'PROGRESS_NOTE', 'DISCHARGE_SUMMARY',
                    'OPERATION_NOTE', 'OUTPATIENT_NOTE', 'CONSULT_NOTE')),
    scope       text NOT NULL DEFAULT 'INPATIENT' CHECK (scope IN ('OUTPATIENT', 'INPATIENT')),
    schema_json jsonb NOT NULL DEFAULT '[]'::jsonb,          -- [{key,label,type,required,options}]
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ---- 病历文书 ----
CREATE TABLE IF NOT EXISTS emr_svc.medical_record (
    id           uuid PRIMARY KEY,
    record_no    text NOT NULL UNIQUE,                        -- MRYYYYMMDD######
    patient_id   uuid NOT NULL,
    patient_no   text NOT NULL,
    visit_id     uuid,
    visit_no     text,
    dept_id      uuid,
    doc_type     text NOT NULL,
    template_code text NOT NULL,
    title        text NOT NULL,
    content      jsonb NOT NULL DEFAULT '{}'::jsonb,          -- 结构化内容 {fieldKey: value}
    content_text text NOT NULL DEFAULT '',                    -- 渲染文本快照（签名哈希的对象）
    author       text NOT NULL,
    status       text NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT', 'SUBMITTED', 'SIGNED', 'ARCHIVED')),
    revision     int NOT NULL DEFAULT 1 CHECK (revision > 0),
    signed_by    text,
    signed_at    timestamptz,
    content_hash text,                                        -- SHA-256(content_text)
    archived_at  timestamptz,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_record_signed CHECK (
        (status IN ('SIGNED', 'ARCHIVED')
            AND signed_by IS NOT NULL AND signed_at IS NOT NULL AND content_hash IS NOT NULL)
        OR status IN ('DRAFT', 'SUBMITTED')
    ),
    CONSTRAINT ck_record_archived CHECK (
        (status = 'ARCHIVED' AND archived_at IS NOT NULL) OR status <> 'ARCHIVED'
    )
);
CREATE INDEX IF NOT EXISTS ix_record_patient ON emr_svc.medical_record (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_record_visit ON emr_svc.medical_record (visit_id) WHERE visit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_record_status ON emr_svc.medical_record (status, created_at DESC);

-- ---- 签名记录（每次签名留一条，含哈希）----
CREATE TABLE IF NOT EXISTS emr_svc.record_signature (
    id           uuid PRIMARY KEY,
    record_id    uuid NOT NULL REFERENCES emr_svc.medical_record (id),
    revision     int NOT NULL,
    signer       text NOT NULL,
    sign_type    text NOT NULL DEFAULT 'AUTHOR' CHECK (sign_type IN ('AUTHOR', 'ATTENDING', 'CHIEF')),
    signed_at    timestamptz NOT NULL DEFAULT now(),
    content_hash text NOT NULL,
    CONSTRAINT ux_signature_record_revision UNIQUE (record_id, revision, sign_type)
);
CREATE INDEX IF NOT EXISTS ix_signature_record ON emr_svc.record_signature (record_id);

-- ---- 修订历史（追加式，不随正文更新而丢失）----
CREATE TABLE IF NOT EXISTS emr_svc.record_amendment (
    id           uuid PRIMARY KEY,
    record_id    uuid NOT NULL REFERENCES emr_svc.medical_record (id),
    revision     int NOT NULL,
    content      jsonb NOT NULL,
    content_text text NOT NULL,
    content_hash text NOT NULL,
    author       text NOT NULL,
    reason       text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_amendment_record_revision UNIQUE (record_id, revision)
);

-- ---- 质控规则（内置三类，避免引入表达式引擎带来的注入面）----
CREATE TABLE IF NOT EXISTS emr_svc.qc_rule (
    id          uuid PRIMARY KEY,
    rule_code   text NOT NULL UNIQUE,
    name        text NOT NULL,
    doc_type    text NOT NULL,
    rule_type   text NOT NULL CHECK (rule_type IN ('REQUIRED_FIELD', 'MIN_LENGTH', 'SIGNATURE_REQUIRED')),
    field_key   text,                                         -- REQUIRED_FIELD / MIN_LENGTH 使用
    threshold   int,                                          -- MIN_LENGTH 的最小字符数
    severity    text NOT NULL DEFAULT 'MAJOR' CHECK (severity IN ('FATAL', 'MAJOR', 'MINOR')),
    message     text NOT NULL,
    enabled     boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---- 质控结果（提交时快照，便于事后追溯"当时为什么能提交"）----
CREATE TABLE IF NOT EXISTS emr_svc.qc_result (
    id          uuid PRIMARY KEY,
    record_id   uuid NOT NULL REFERENCES emr_svc.medical_record (id),
    revision    int NOT NULL,
    rule_code   text NOT NULL,
    severity    text NOT NULL,
    passed      boolean NOT NULL,
    message     text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_qc_result_record ON emr_svc.qc_result (record_id, revision);

-- ---- 审计（追加式）----
CREATE TABLE IF NOT EXISTS emr_svc.emr_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_emr_change_target ON emr_svc.emr_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_emr_change_ts ON emr_svc.emr_change_log (ts DESC);

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS emr_svc.record_no_seq START 1;

-- ============================================================================
-- 初始主数据：一个入院记录模板 + 三条质控规则（上线前由病案室按院内规范替换）
-- ============================================================================
INSERT INTO emr_svc.md_template (id, template_code, name, doc_type, scope, schema_json)
VALUES
    ('c1111111-1111-1111-1111-111111111111', 'TPL-ADMISSION', '入院记录（通用）', 'ADMISSION_NOTE', 'INPATIENT',
     '[{"key":"chiefComplaint","label":"主诉","type":"TEXT","required":true},
       {"key":"presentIllness","label":"现病史","type":"TEXTAREA","required":true},
       {"key":"pastHistory","label":"既往史","type":"TEXTAREA","required":false},
       {"key":"physicalExam","label":"体格检查","type":"TEXTAREA","required":true},
       {"key":"initialDiagnosis","label":"初步诊断","type":"TEXTAREA","required":true}]'::jsonb)
ON CONFLICT (template_code) DO NOTHING;

INSERT INTO emr_svc.qc_rule (id, rule_code, name, doc_type, rule_type, field_key, threshold, severity, message)
VALUES
    ('d1111111-1111-1111-1111-111111111111', 'QC-ADM-CC', '主诉不得为空', 'ADMISSION_NOTE',
     'REQUIRED_FIELD', 'chiefComplaint', NULL, 'FATAL', '主诉为必填项'),
    ('d2222222-2222-2222-2222-222222222222', 'QC-ADM-PE', '体格检查不得为空', 'ADMISSION_NOTE',
     'REQUIRED_FIELD', 'physicalExam', NULL, 'FATAL', '体格检查为必填项'),
    ('d3333333-3333-3333-3333-333333333333', 'QC-ADM-DX', '初步诊断不少于 10 字', 'ADMISSION_NOTE',
     'MIN_LENGTH', 'initialDiagnosis', 10, 'MAJOR', '初步诊断过于简略，请写明部位与性质')
ON CONFLICT (rule_code) DO NOTHING;
