-- ============================================================================
-- V2：医保结算清单闭环（确认前费用校验 / 作废冲正 / 对账）
--
-- 背景：CONFIRMED 是终态，但临床域在确认后仍可能继续计费或退费；
-- 没有作废路径时错误金额会永久固化，医保经办的"更正/红冲"无从落地。
--
-- 设计：
--   * 新增 VOIDED 状态：CONFIRMED → VOIDED（单向，留作废人/时间/原因），
--     作废后同一就诊可重新 generate；
--   * 唯一约束由"列级 UNIQUE"改为"部分唯一索引（status <> 'VOIDED'）"，
--     既保证一个就诊只有一张有效清单，又允许历史作废单共存；
--   * 增加参保待遇快照列（snapshot_ratio / snapshot_cap；deductible 列本身即快照），
--     让"这张清单当时按什么口径算的"可复现，确认时的费用复核也用快照参数重算；
--   * 增加金额勾稽约束（统筹 ≤ 纳入；统筹 + 自付 = 总额）作为数据库最后防线。
-- ============================================================================

-- ---- 状态扩展 ----
ALTER TABLE insurance_svc.settlement_bill
    DROP CONSTRAINT IF EXISTS settlement_bill_status_check;
ALTER TABLE insurance_svc.settlement_bill
    ADD CONSTRAINT ck_bill_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'VOIDED'));

-- ---- 作废留痕 ----
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS void_reason text;
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS voided_by   text;
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS voided_at   timestamptz;

ALTER TABLE insurance_svc.settlement_bill
    DROP CONSTRAINT IF EXISTS ck_bill_voided;
ALTER TABLE insurance_svc.settlement_bill
    ADD CONSTRAINT ck_bill_voided CHECK (
        (status = 'VOIDED' AND voided_by IS NOT NULL AND voided_at IS NOT NULL)
        OR status <> 'VOIDED'
    );

-- ---- 参保待遇快照（生成清单时固化，确认复核与对账都按它重算）----
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS snapshot_ratio numeric(5, 4);
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS snapshot_cap   numeric(12, 2);

-- ---- 金额勾稽（calculate 语义下恒成立）----
-- 注意：不要加 deductible <= eligible —— calculate 不截断起付线，起付线大于纳入金额是合法的
ALTER TABLE insurance_svc.settlement_bill
    DROP CONSTRAINT IF EXISTS ck_bill_amounts;
ALTER TABLE insurance_svc.settlement_bill
    ADD CONSTRAINT ck_bill_amounts CHECK (
        insurance_amount <= eligible_amount
        AND insurance_amount + self_pay_amount = total_amount
    );

-- ---- 一个就诊只有一张"有效"清单；作废单可保留多张 ----
ALTER TABLE insurance_svc.settlement_bill
    DROP CONSTRAINT IF EXISTS settlement_bill_visit_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_bill_visit_active
    ON insurance_svc.settlement_bill (visit_id) WHERE status <> 'VOIDED';

CREATE INDEX IF NOT EXISTS ix_bill_visit_history
    ON insurance_svc.settlement_bill (visit_id, created_at DESC);
