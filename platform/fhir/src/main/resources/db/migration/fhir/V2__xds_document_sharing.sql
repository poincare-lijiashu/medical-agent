-- ============================================================================
-- V2：IHE XDS.b 文档共享（§295 / 计划书 W3.5）
--
-- 解决什么问题
--   互联互通四级甲等要求"共享文档"能被区域平台注册、检索、调阅：
--   出院小结、检验报告等文档必须有稳定的唯一标识（uniqueId）、类型/格式/
--   保密级别等元数据、内容哈希与提交集，且每次注册/检索/调阅都要可追溯。
--   此前系统只有 FHIR 资源投影，没有"文档"这一层。
--
-- 设计要点
--   * xds_submission_set：一次提交（ITI-41 Provide and Register）对应一个提交集；
--   * xds_document：注册表元数据，uniqueId 全局唯一；同一 logicalId 允许多版本；
--   * xds_document_content：内容独立成表，size_bytes 与 octet_length 强校验，
--     哈希与元数据分别保存，调阅时两边比对，任何一边被改都会暴露；
--   * xds_access_log：注册/检索/调阅/生成/停用的追加式审计，禁止改删；
--   * 文档含 PHI，生产需依赖磁盘/TDE 加密；本表不做应用层二次加密，
--     因为密钥轮换会让历史文档永久不可读（边界见 RUNBOOK §17）。
--
-- 实现边界（必须知道）
--   本批实现 XDS.b 的 REST/JSON 子集：ITI-41 单文档提交、ITI-18 注册表检索、
--   ITI-43 文档调阅、ITI-62 元数据停用；完整 SOAP/ebXML 信封与
--   Connectathon 互操作需在院方/区域平台联调环境中完成。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS fhir_svc;

-- ---------------------------------------------------------------- 提交集
CREATE TABLE IF NOT EXISTS fhir_svc.xds_submission_set (
    id                  uuid PRIMARY KEY,
    submission_set_uid  text NOT NULL UNIQUE,
    source_id           text NOT NULL,
    patient_id          uuid NOT NULL,
    patient_no          text NOT NULL,
    submission_time     timestamptz NOT NULL,
    status              text NOT NULL DEFAULT 'APPROVED'
                        CHECK (status IN ('APPROVED', 'DEPRECATED')),
    author_person       text,
    author_institution  text,
    title               text,
    comments            text,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    deprecated_at       timestamptz,
    deprecated_by       text,
    deprecate_reason    text,
    CONSTRAINT ck_xds_submission_deprecated CHECK (
        status <> 'DEPRECATED'
        OR (deprecated_at IS NOT NULL AND deprecate_reason IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_xds_submission_patient
    ON fhir_svc.xds_submission_set (patient_id, submission_time DESC);
COMMENT ON TABLE fhir_svc.xds_submission_set IS
    'XDS 提交集：一次 Provide and Register 对应一条，停用保留原因不物理删除';

-- ---------------------------------------------------------------- 文档注册表
CREATE TABLE IF NOT EXISTS fhir_svc.xds_document (
    id                    uuid PRIMARY KEY,
    unique_id             text NOT NULL UNIQUE,
    logical_id            text NOT NULL,
    document_version      int NOT NULL DEFAULT 1 CHECK (document_version > 0),
    submission_set_id     uuid NOT NULL
                          REFERENCES fhir_svc.xds_submission_set (id),
    patient_id            uuid NOT NULL,
    patient_no            text NOT NULL,
    campus_id             uuid NOT NULL,
    class_code            text NOT NULL,
    class_display         text,
    type_code             text,
    type_display          text,
    format_code           text NOT NULL,
    format_display        text,
    event_codes           jsonb NOT NULL DEFAULT '[]'::jsonb
                          CHECK (jsonb_typeof(event_codes) = 'array'),
    creation_time         timestamptz NOT NULL,
    service_start_time    timestamptz,
    service_stop_time     timestamptz,
    facility_type_code    text,
    practice_setting_code text,
    author_person         text,
    author_institution    text,
    author_role           text,
    title                 text NOT NULL,
    comments              text,
    confidentiality_code  text NOT NULL DEFAULT 'N'
                          CHECK (confidentiality_code IN ('N', 'R', 'V', 'M')),
    language_code         text NOT NULL DEFAULT 'zh-CN',
    mime_type             text NOT NULL,
    size_bytes            int NOT NULL CHECK (size_bytes > 0),
    hash_sha256           text NOT NULL CHECK (length(hash_sha256) = 64),
    availability_status   text NOT NULL DEFAULT 'APPROVED'
                          CHECK (availability_status IN ('APPROVED', 'DEPRECATED')),
    created_by            text NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    row_version           bigint NOT NULL DEFAULT 0,
    deprecated_at         timestamptz,
    deprecated_by         text,
    deprecate_reason      text,
    CONSTRAINT ck_xds_document_service_time CHECK (
        service_start_time IS NULL
        OR service_stop_time IS NULL
        OR service_stop_time >= service_start_time
    ),
    CONSTRAINT ck_xds_document_deprecated CHECK (
        availability_status <> 'DEPRECATED'
        OR (deprecated_at IS NOT NULL AND deprecate_reason IS NOT NULL)
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_xds_document_logical_version
    ON fhir_svc.xds_document (logical_id, document_version);
CREATE INDEX IF NOT EXISTS ix_xds_document_patient
    ON fhir_svc.xds_document (patient_id, creation_time DESC);
CREATE INDEX IF NOT EXISTS ix_xds_document_patient_no
    ON fhir_svc.xds_document (patient_no, creation_time DESC);
CREATE INDEX IF NOT EXISTS ix_xds_document_class_time
    ON fhir_svc.xds_document (class_code, creation_time DESC);
CREATE INDEX IF NOT EXISTS ix_xds_document_campus_time
    ON fhir_svc.xds_document (campus_id, creation_time DESC);
CREATE INDEX IF NOT EXISTS ix_xds_document_status
    ON fhir_svc.xds_document (availability_status, creation_time DESC);
COMMENT ON TABLE fhir_svc.xds_document IS
    'XDS 文档注册表：uniqueId 唯一；logicalId+版本唯一；内容哈希用于调阅时完整性校验';
COMMENT ON COLUMN fhir_svc.xds_document.confidentiality_code IS
    '保密级别（HL7 Table 0907）：N 正常 / R 限制 / V 很敏感 / M 仅限特定人员';
COMMENT ON COLUMN fhir_svc.xds_document.hash_sha256 IS
    '内容 SHA-256（小写十六进制）；与 xds_document_content.hash_sha256 必须一致';

-- ---------------------------------------------------------------- 文档内容
CREATE TABLE IF NOT EXISTS fhir_svc.xds_document_content (
    document_id  uuid PRIMARY KEY
                 REFERENCES fhir_svc.xds_document (id),
    content      bytea NOT NULL,
    hash_sha256  text NOT NULL CHECK (length(hash_sha256) = 64),
    size_bytes   int NOT NULL CHECK (size_bytes > 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_xds_content_size CHECK (size_bytes = octet_length(content))
);
COMMENT ON TABLE fhir_svc.xds_document_content IS
    'XDS 文档内容：与注册表分离，便于只读账号只授元数据查询；size 与 content 强一致';
COMMENT ON COLUMN fhir_svc.xds_document_content.content IS
    '原始文档字节（CDA XML/PDF 等）；含 PHI，生产依赖磁盘/TDE 加密';

-- ---------------------------------------------------------------- 访问审计
CREATE TABLE IF NOT EXISTS fhir_svc.xds_access_log (
    id            bigserial PRIMARY KEY,
    ts            timestamptz NOT NULL DEFAULT now(),
    actor         text NOT NULL,
    action        text NOT NULL
                  CHECK (action IN ('PROVIDE', 'QUERY', 'RETRIEVE', 'GENERATE', 'DEPRECATE')),
    document_uid  text,
    patient_id    uuid,
    query_summary text,
    result_count  integer,
    outcome       text NOT NULL
                  CHECK (outcome IN ('OK', 'BAD_REQUEST', 'FORBIDDEN', 'NOT_FOUND',
                                     'HASH_MISMATCH', 'CONFLICT', 'ERROR')),
    client_ip     text,
    trace_id      text
);
CREATE INDEX IF NOT EXISTS ix_xds_access_ts
    ON fhir_svc.xds_access_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_xds_access_actor
    ON fhir_svc.xds_access_log (actor, ts DESC);
CREATE INDEX IF NOT EXISTS ix_xds_access_document
    ON fhir_svc.xds_access_log (document_uid, ts DESC);
COMMENT ON TABLE fhir_svc.xds_access_log IS
    'XDS 注册/检索/调阅/生成/停用审计（只允许 INSERT）：谁在何时对哪份文档做了什么';

DROP TRIGGER IF EXISTS trg_xds_access_log_append_only ON fhir_svc.xds_access_log;
CREATE TRIGGER trg_xds_access_log_append_only
    BEFORE UPDATE OR DELETE ON fhir_svc.xds_access_log
    FOR EACH ROW EXECUTE FUNCTION fhir_svc.reject_audit_mutation();
