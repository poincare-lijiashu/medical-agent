-- ============================================================================
-- 病历域 V13：病案首页患者人口学与死亡信息（第六轮 R6-MF-06）
--
-- 背景：首页从实体到 HQMS 报文全链路没有性别/出生日期/年龄，省局前置机会整批退回；
-- 且死亡病例过去被 "requireActive" 拦住，根本生成不了首页（三甲"死亡病例"一票否决项）。
--
-- 本迁移：
--   * 首页增加患者人口学快照（性别/出生日期/入院年龄）——生成时固化，任何状态都不可改；
--   * 增加死亡时间/死亡原因——草稿可由病案室补录，提交后只读；
--   * 提交态强制"离院方式=DEATH 必须填写死亡时间"（草稿放行，避免补录过程中被卡住）。
-- ============================================================================

ALTER TABLE emr_svc.record_homepage
    ADD COLUMN IF NOT EXISTS patient_sex text
        CHECK (patient_sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    ADD COLUMN IF NOT EXISTS birth_date date,
    ADD COLUMN IF NOT EXISTS admit_age int CHECK (admit_age >= 0 AND admit_age <= 150),
    ADD COLUMN IF NOT EXISTS death_time timestamptz,
    ADD COLUMN IF NOT EXISTS death_cause text;

COMMENT ON COLUMN emr_svc.record_homepage.patient_sex IS
    '性别快照（来自 EMPI，生成时固化；HQMS 报文必填项）';
COMMENT ON COLUMN emr_svc.record_homepage.birth_date IS
    '出生日期快照（来自 EMPI，生成时固化）';
COMMENT ON COLUMN emr_svc.record_homepage.admit_age IS
    '入院年龄（岁，按入院日期与出生日期计算，生成时固化）';
COMMENT ON COLUMN emr_svc.record_homepage.death_time IS
    '死亡时间：离院方式=DEATH 时提交前必填';
COMMENT ON COLUMN emr_svc.record_homepage.death_cause IS
    '死亡原因（离院方式=DEATH 时建议填写，HQMS 报文使用）';

-- 提交/归档态强制：离院方式=DEATH 必须有死亡时间（草稿阶段允许暂缺）
ALTER TABLE emr_svc.record_homepage
    DROP CONSTRAINT IF EXISTS ck_homepage_death;
ALTER TABLE emr_svc.record_homepage
    ADD CONSTRAINT ck_homepage_death CHECK (
        status = 'DRAFT'
        OR discharge_mode IS DISTINCT FROM 'DEATH'
        OR death_time IS NOT NULL
    );

-- ---- 扩展守门函数：人口学快照任何状态都不可改；死亡信息提交后只读 ----
CREATE OR REPLACE FUNCTION emr_svc.guard_homepage() RETURNS trigger AS $$
BEGIN
    -- 状态机单向：DRAFT → SUBMITTED → ARCHIVED，任何回退都在数据库层拒绝
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'SUBMITTED') THEN
        RAISE EXCEPTION '病案首页状态不允许从草稿直接变为 %', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status NOT IN ('SUBMITTED', 'ARCHIVED') THEN
        RAISE EXCEPTION '病案首页已提交，不允许退回草稿或重复提交'
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION '病案首页已归档，不允许退回'
            USING ERRCODE = 'check_violation';
    END IF;

    -- 患者人口学快照来自 EMPI，生成后任何状态都不可改（改了 HQMS 报文就与主索引不符）
    IF NEW.patient_sex IS DISTINCT FROM OLD.patient_sex
       OR NEW.birth_date IS DISTINCT FROM OLD.birth_date
       OR NEW.admit_age IS DISTINCT FROM OLD.admit_age THEN
        RAISE EXCEPTION '病案首页的患者性别/出生日期/入院年龄为生成时快照，不可修改'
            USING ERRCODE = 'check_violation';
    END IF;

    -- 已提交/已归档：临床快照与死亡信息一律只读
    IF OLD.status IN ('SUBMITTED', 'ARCHIVED') THEN
        IF NEW.homepage_no IS DISTINCT FROM OLD.homepage_no
           OR NEW.visit_id IS DISTINCT FROM OLD.visit_id
           OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
           OR NEW.patient_no IS DISTINCT FROM OLD.patient_no
           OR NEW.patient_name IS DISTINCT FROM OLD.patient_name
           OR NEW.admitted_at IS DISTINCT FROM OLD.admitted_at
           OR NEW.discharged_at IS DISTINCT FROM OLD.discharged_at
           OR NEW.stay_days IS DISTINCT FROM OLD.stay_days
           OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
           OR NEW.procedures IS DISTINCT FROM OLD.procedures
           OR NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
           OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
           OR NEW.death_time IS DISTINCT FROM OLD.death_time
           OR NEW.death_cause IS DISTINCT FROM OLD.death_cause THEN
            RAISE EXCEPTION '病案首页已提交/已归档，临床快照（诊断/手术/费用/患者信息/出院时间/死亡信息）不可修改'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION emr_svc.guard_homepage() IS
    '病案首页兜底：状态单向；人口学快照永不可改；提交后临床快照与死亡信息只读（应用层 CAS 之外的数据库保险）';
