-- ============================================================================
-- 临床核心 V8：收费单状态与支付时间的一致性
--
-- 原 ck_charge_paid 只要求"PAID 必须有 paid_at"，未约束"非 PAID 不得有 paid_at"。
-- 中途结算/作废流程引入后，若某条 UPDATE 漏改 paid_at，会出现
-- "UNPAID 单带着支付时间"的脏数据，财务对账时无法区分是否已收款。
-- ============================================================================

-- 历史数据先纠正（只影响状态与 paid_at 不一致的异常行）
UPDATE clinical_svc.charge
   SET paid_at = NULL
 WHERE status <> 'PAID' AND paid_at IS NOT NULL;

ALTER TABLE clinical_svc.charge DROP CONSTRAINT IF EXISTS ck_charge_paid;
ALTER TABLE clinical_svc.charge
    ADD CONSTRAINT ck_charge_paid CHECK (
        (status = 'PAID' AND paid_at IS NOT NULL)
        OR (status <> 'PAID' AND paid_at IS NULL)
    );

COMMENT ON CONSTRAINT ck_charge_paid ON clinical_svc.charge IS
    'PAID 必须有支付时间；UNPAID/REFUNDED 不得带支付时间（防"未结单带收款时间"的脏数据）';
