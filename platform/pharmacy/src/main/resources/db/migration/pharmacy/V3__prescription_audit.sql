-- ============================================================================
-- 药房 V3：处方前置审方（用药安全规则）
--
-- 背景：处方审核此前只有"通过/驳回"两个动作，没有规则支撑；药师要逐条人工判断
-- 儿童禁忌、日剂量超限、特殊使用级抗菌药、麻醉药双人核对等硬性要求。
--
-- 本迁移补充三类数据：
--   1) 药品安全属性：日剂量上限、儿童禁用、老年慎用（由药典/说明书维护）；
--   2) 处方明细的结构化剂量：单次剂量 + 单位 + 每日次数 → 生成列 daily_dose，
--      没有结构化剂量时规则自动跳过（不能因为"用法用量是自由文本"就误报）；
--   3) 审方记录表 prescription_audit（追加式，保留每次评估的规则、级别与文案）。
--
-- 级别约定：BLOCK=禁止通过（开方即拒绝；审核也不能通过）/ WARN=提示但可通过 / INFO=告知。
-- ============================================================================

-- ---- 1. 药品安全属性 ----
ALTER TABLE pharmacy_svc.md_drug
    ADD COLUMN IF NOT EXISTS max_daily_dose numeric(12, 3)
        CHECK (max_daily_dose IS NULL OR max_daily_dose > 0);
ALTER TABLE pharmacy_svc.md_drug
    ADD COLUMN IF NOT EXISTS pediatric_contraindicated boolean NOT NULL DEFAULT false;
ALTER TABLE pharmacy_svc.md_drug
    ADD COLUMN IF NOT EXISTS elderly_caution boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN pharmacy_svc.md_drug.max_daily_dose IS
    '日剂量上限（按最小计价单位，如 mg/g/ml）；为空表示未维护，剂量规则跳过';
COMMENT ON COLUMN pharmacy_svc.md_drug.pediatric_contraindicated IS '儿童（<14 岁）禁用';
COMMENT ON COLUMN pharmacy_svc.md_drug.elderly_caution IS '老年人（>=65 岁）慎用';

-- ---- 2. 处方明细结构化剂量 ----
ALTER TABLE pharmacy_svc.prescription_item
    ADD COLUMN IF NOT EXISTS single_dose numeric(12, 3);
ALTER TABLE pharmacy_svc.prescription_item
    ADD COLUMN IF NOT EXISTS dose_unit text;
ALTER TABLE pharmacy_svc.prescription_item
    ADD COLUMN IF NOT EXISTS times_per_day int;
-- 生成列：日剂量 = 单次剂量 × 每日次数（数据库层算，应用层不用重复实现）
ALTER TABLE pharmacy_svc.prescription_item
    ADD COLUMN IF NOT EXISTS daily_dose numeric(12, 3)
        GENERATED ALWAYS AS (single_dose * times_per_day) STORED;

ALTER TABLE pharmacy_svc.prescription_item
    DROP CONSTRAINT IF EXISTS ck_pi_dose_complete;
ALTER TABLE pharmacy_svc.prescription_item
    ADD CONSTRAINT ck_pi_dose_complete CHECK (
        (single_dose IS NULL AND dose_unit IS NULL AND times_per_day IS NULL)
        OR (single_dose > 0 AND dose_unit IS NOT NULL AND times_per_day BETWEEN 1 AND 24)
    );

COMMENT ON COLUMN pharmacy_svc.prescription_item.daily_dose IS
    '生成列：single_dose × times_per_day，供日剂量上限规则使用';

-- ---- 3. 审方记录（追加式）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.prescription_audit (
    id              uuid PRIMARY KEY,
    prescription_id uuid NOT NULL REFERENCES pharmacy_svc.prescription (id) ON DELETE CASCADE,
    phase           text NOT NULL CHECK (phase IN ('CREATE', 'REVIEW')),
    level           text NOT NULL CHECK (level IN ('BLOCK', 'WARN', 'INFO')),
    rule_code       text NOT NULL,
    message         text NOT NULL,
    detail          jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_prescription_audit_rx
    ON pharmacy_svc.prescription_audit (prescription_id, created_at);
CREATE INDEX IF NOT EXISTS ix_prescription_audit_level
    ON pharmacy_svc.prescription_audit (level, created_at DESC);

COMMENT ON TABLE pharmacy_svc.prescription_audit IS
    '处方审方记录（追加式）：每次开方/审核评估的规则命中，BLOCK 级禁止通过';
