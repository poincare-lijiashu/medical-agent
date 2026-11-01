-- ============================================================================
-- 临床域 V21：术前访视与麻醉评估单（手术麻醉域第六批）
--
-- 制度背景：麻醉医师必须在术前完成访视并按 ASA 分级、气道评估（Mallampati）、
-- 禁食时间、既往麻醉史/过敏史给出麻醉结论；"不宜手术"的结论必须能拦住开台。
--
-- 与既有文书的分工：
--   * preop_discussion（V17）：**外科**术前讨论，关注手术方案与风险；
--   * preop_visit（本表）：**麻醉**术前访视与评估，关注能不能耐受麻醉；
--   * surgery_consent（V17）：患者授权事实。
--   三者互不替代，缺哪一项都能在开台校验里精确指出。
--
-- 冻结规则：SIGNED 后由触发器冻结（与术前讨论一致），要改只能走病案修改审批。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.preop_visit (
    id                  uuid PRIMARY KEY,
    surgery_case_id     uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    visited_at          timestamptz,
    visited_by          text,
    anesthesia_plan     text CHECK (anesthesia_plan IN
                            ('GENERAL', 'REGIONAL', 'LOCAL', 'SEDATION', 'NONE')),
    asa_grade           text CHECK (asa_grade IN ('I', 'II', 'III', 'IV', 'V', 'VI')),
    mallampati          text CHECK (mallampati IN ('I', 'II', 'III', 'IV')),
    airway_note         text,
    dentition           text,
    anesthesia_history  text,
    allergy_history     text,
    fasting_solid_hours int CHECK (fasting_solid_hours IS NULL
                                   OR fasting_solid_hours BETWEEN 0 AND 72),
    fasting_clear_hours int CHECK (fasting_clear_hours IS NULL
                                   OR fasting_clear_hours BETWEEN 0 AND 24),
    conclusion          text CHECK (conclusion IN ('FIT', 'FIT_WITH_CAUTION', 'UNFIT')),
    risk_note           text,
    status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED')),
    signed_by           text,
    signed_at           timestamptz,
    created_by          text NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_preop_visit_signed CHECK (
        (status = 'SIGNED' AND signed_by IS NOT NULL AND signed_at IS NOT NULL)
        OR status <> 'SIGNED'
    )
);

COMMENT ON TABLE clinical_svc.preop_visit IS
    '术前访视与麻醉评估：一台手术一份；SIGNED 后冻结；结论 UNFIT 时禁止开台';

CREATE OR REPLACE FUNCTION clinical_svc.guard_preop_visit() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'SIGNED' THEN
        RAISE EXCEPTION '术前访视已签署，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_preop_visit ON clinical_svc.preop_visit;
CREATE TRIGGER trg_guard_preop_visit
    BEFORE UPDATE ON clinical_svc.preop_visit
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_preop_visit();
