-- ============================================================================
-- 临床核心 V5：预交金参与住院结算（抵扣）
--
-- deposit.kind 增加 SETTLE：结算时从预交金扣款（负数流水），remark 记录收费单号。
-- 结算前必须校验"预交金余额 >= 本次结算金额"，不足即拒绝（欠费拦截）。
-- ============================================================================

ALTER TABLE clinical_svc.deposit DROP CONSTRAINT IF EXISTS deposit_kind_check;
ALTER TABLE clinical_svc.deposit
    ADD CONSTRAINT deposit_kind_check CHECK (kind IN ('DEPOSIT', 'REFUND', 'SETTLE'));

COMMENT ON COLUMN clinical_svc.deposit.kind IS
    'DEPOSIT 存入(正) / REFUND 退还(负) / SETTLE 结算抵扣(负)';
