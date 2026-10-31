-- ============================================================================
-- V3：修复确认约束未覆盖 VOIDED
--
-- V1 的 ck_bill_confirmed 只允许 (CONFIRMED 且已填确认人) 或 DRAFT；
-- V2 引入 VOIDED 后，作废更新会被该约束拒绝（表现为 409/500），作废链路走不通。
-- 本迁移放宽为：DRAFT 不需要确认人；CONFIRMED / VOIDED 必须留有确认人（历史可追溯）。
-- ============================================================================

ALTER TABLE insurance_svc.settlement_bill
    DROP CONSTRAINT IF EXISTS ck_bill_confirmed;

ALTER TABLE insurance_svc.settlement_bill
    ADD CONSTRAINT ck_bill_confirmed CHECK (
        (status IN ('CONFIRMED', 'VOIDED') AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
        OR status = 'DRAFT'
    );
