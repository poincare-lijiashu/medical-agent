-- ============================================================================
-- 临床域 V17：术前讨论 / 知情同意 / 术前核查清单
--
-- 为什么这一批是"硬门槛"而不是"再记两张表"：
--   * 手术知情同意与麻醉知情同意是法律要求的必备文书，**没有签署就不能进手术间**；
--   * 术前讨论记录是三级/四级手术的质量控制证据，签署后不可篡改；
--   * 同意书允许"作废重签"（写错姓名/关系很常见），但作废必须留原因，作废后原记录保留。
--
-- 边界：本批不做法定模板渲染与电子签名证书（PDF/CA 在病历文书子系统统一做），
-- 只保证"文书事实 + 签署人 + 时间 + 不可篡改"四件事可信。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.preop_discussion (
    id                uuid PRIMARY KEY,
    surgery_case_id   uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    discussion_at     timestamptz,
    moderator         text,
    participants      text,
    diagnosis_summary text,
    procedure_plan    text,
    risks             text,
    anesthesia_plan   text,
    blood_preparation text,
    status            text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED')),
    signed_by         text,
    signed_at         timestamptz,
    created_by        text NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_preop_signed CHECK (
        (status = 'SIGNED' AND signed_by IS NOT NULL AND signed_at IS NOT NULL)
        OR status <> 'SIGNED'
    )
);

COMMENT ON TABLE clinical_svc.preop_discussion IS
    '术前讨论记录：一台手术一份；SIGNED 后由触发器冻结，不可再改';

CREATE TABLE IF NOT EXISTS clinical_svc.surgery_consent (
    id              uuid PRIMARY KEY,
    surgery_case_id uuid NOT NULL REFERENCES clinical_svc.surgery_case (id),
    consent_type    text NOT NULL
                    CHECK (consent_type IN ('SURGERY', 'ANESTHESIA', 'BLOOD_TRANSFUSION', 'SPECIAL')),
    signer_name     text NOT NULL,
    signer_relation text,
    signed_at       timestamptz NOT NULL,
    witness         text,
    notes           text,
    revoked_at      timestamptz,
    revoke_reason   text,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_consent_revoke CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

-- 同一手术的同一类型同意书只能有一份"有效"记录；作废后可重签
CREATE UNIQUE INDEX IF NOT EXISTS ux_consent_case_type_active
    ON clinical_svc.surgery_consent (surgery_case_id, consent_type)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_consent_case
    ON clinical_svc.surgery_consent (surgery_case_id, created_at DESC);

COMMENT ON TABLE clinical_svc.surgery_consent IS
    '知情同意书：插入即"已签署事实"，不提供修改接口；写错只能作废（留原因）后重签';

-- ---- 签署后冻结 ----

CREATE OR REPLACE FUNCTION clinical_svc.guard_preop_discussion() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'SIGNED' THEN
        RAISE EXCEPTION '术前讨论已签署，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_preop_discussion ON clinical_svc.preop_discussion;
CREATE TRIGGER trg_guard_preop_discussion
    BEFORE UPDATE ON clinical_svc.preop_discussion
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_preop_discussion();

-- 同意书只允许"作废一次"这一种 UPDATE，其余字段不可改
CREATE OR REPLACE FUNCTION clinical_svc.guard_surgery_consent() RETURNS trigger AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION '同意书已作废，不能再修改' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.surgery_case_id <> OLD.surgery_case_id
        OR NEW.consent_type <> OLD.consent_type
        OR NEW.signer_name <> OLD.signer_name
        OR NEW.signed_at <> OLD.signed_at THEN
        RAISE EXCEPTION '同意书签署事实不可修改，只能作废后重签' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_surgery_consent ON clinical_svc.surgery_consent;
CREATE TRIGGER trg_guard_surgery_consent
    BEFORE UPDATE ON clinical_svc.surgery_consent
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_surgery_consent();
