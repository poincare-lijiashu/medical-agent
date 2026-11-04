-- G44 电子知情同意书
--
-- consent_template：模板主数据。DRAFT -> PUBLISHED -> DISABLED；发布后才能被同意书引用。
-- consent_record：一次签署对应一份快照；模板内容/版本在创建时固化，后续模板变更不影响已签文书。
-- 签名：PAPER（纸笔签署后录入）或 CA（本地 Mock / 厂商证书签名）；CA 模式必须同时有证书序列号与签名值。
CREATE TABLE IF NOT EXISTS emr_svc.consent_template (
    id               uuid PRIMARY KEY,
    template_code    text NOT NULL UNIQUE,
    name             text NOT NULL,
    procedure_type   text NOT NULL,
    content          text NOT NULL CHECK (length(trim(content)) > 0),
    template_version int NOT NULL DEFAULT 1 CHECK (template_version > 0),
    status           text NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_consent_template_status
    ON emr_svc.consent_template (status, procedure_type);
COMMENT ON TABLE emr_svc.consent_template IS '知情同意书模板（发布后不可改，改内容须新建版本）';

CREATE SEQUENCE IF NOT EXISTS emr_svc.consent_no_seq;

CREATE TABLE IF NOT EXISTS emr_svc.consent_record (
    id                uuid PRIMARY KEY,
    consent_no        text NOT NULL UNIQUE,
    template_id       uuid NOT NULL REFERENCES emr_svc.consent_template (id),
    template_code     text NOT NULL,
    template_version  int NOT NULL,
    template_name     text NOT NULL,
    patient_id        uuid NOT NULL,
    patient_no        text NOT NULL,
    patient_name      text NOT NULL,
    visit_id          uuid NOT NULL,
    procedure_name    text NOT NULL CHECK (length(trim(procedure_name)) > 0),
    content_text      text NOT NULL,
    content_hash      text,
    status            text NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT', 'SIGNED', 'REVOKED')),
    patient_signer    text,
    patient_relation  text,
    witness_name      text,
    doctor_name       text NOT NULL,
    signed_at         timestamptz,
    sign_method       text CHECK (sign_method IS NULL OR sign_method IN ('PAPER', 'CA')),
    certificate_serial text,
    signature_value   text,
    algorithm         text,
    revoke_reason     text,
    revoked_by        text,
    revoked_at        timestamptz,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_consent_signed CHECK (
        status <> 'SIGNED' OR (
            content_hash IS NOT NULL AND signed_at IS NOT NULL
            AND patient_signer IS NOT NULL AND length(trim(patient_signer)) > 0
            AND length(trim(doctor_name)) > 0
        )
    ),
    CONSTRAINT ck_consent_revoked CHECK (
        status <> 'REVOKED' OR (
            revoke_reason IS NOT NULL AND length(trim(revoke_reason)) > 0
            AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_consent_ca CHECK (
        sign_method IS NULL OR sign_method <> 'CA' OR (
            certificate_serial IS NOT NULL AND signature_value IS NOT NULL
            AND algorithm IS NOT NULL
        )
    )
);
CREATE INDEX IF NOT EXISTS ix_consent_patient
    ON emr_svc.consent_record (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_consent_visit
    ON emr_svc.consent_record (visit_id);
CREATE INDEX IF NOT EXISTS ix_consent_status
    ON emr_svc.consent_record (status, signed_at DESC);
COMMENT ON TABLE emr_svc.consent_record IS
    '知情同意书：模板快照 + 内容哈希 + 患者/见证人/医师签署；撤销保留原因与责任人';

-- 演示模板（发布态），上线后由医务处替换为院内正式模板。
INSERT INTO emr_svc.consent_template
    (id, template_code, name, procedure_type, content, template_version, status, created_by)
VALUES
    ('c1000000-0000-0000-0000-000000000001', 'CONSENT-SURGERY', '手术知情同意书', 'SURGERY',
     '手术知情同意书\n\n患者：{{patientName}}\n拟行操作：{{procedureName}}\n\n一、操作目的与预期收益已向患者/家属说明。\n二、已告知可能的风险与并发症，包括但不限于出血、感染、麻醉意外、术后恢复不良等。\n三、已告知替代方案及不进行该操作的风险。\n四、患者/家属已充分提问，医师已作出解答。\n\n医师：{{doctorName}}\n日期：{{date}}',
     1, 'PUBLISHED', 'system'),
    ('c1000000-0000-0000-0000-000000000002', 'CONSENT-ANESTHESIA', '麻醉知情同意书', 'ANESTHESIA',
     '麻醉知情同意书\n\n患者：{{patientName}}\n拟行麻醉/操作：{{procedureName}}\n\n一、麻醉方式与目的已说明。\n二、已告知麻醉相关风险，包括过敏反应、呼吸循环意外、术后恶心呕吐等。\n三、已告知替代麻醉方案。\n四、患者/家属已充分提问，医师已作出解答。\n\n医师：{{doctorName}}\n日期：{{date}}',
     1, 'PUBLISHED', 'system');
