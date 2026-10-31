-- ============================================================================
-- 药房 V2：处方计费模式与发药计费状态（N2 收尾）
--
-- 背景：药房此前只发药不计费，发药金额永远进不了收费单。本迁移引入：
--   * prescription.billing_mode：ORDER（默认，费用由临床医嘱计价，药房不重复计费）
--                               / DISPENSE（发药时计价，用于未关联医嘱的自费处方）
--   * dispense.billing_status：发药计费链路状态，供补偿重试与财务对账
--
-- 计费调用在本地事务提交后执行（afterCommit），失败置 CHARGE_FAILED 并可重试，
-- 避免"收费服务抖动导致库存已扣但发药回滚"的跨服务分布式事务问题。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription
    ADD COLUMN IF NOT EXISTS billing_mode text NOT NULL DEFAULT 'ORDER'
    CHECK (billing_mode IN ('ORDER', 'DISPENSE'));

COMMENT ON COLUMN pharmacy_svc.prescription.billing_mode IS
    'ORDER=费用由临床医嘱计价（默认，避免重复计费）；DISPENSE=发药时计价（必须带 visit_id）';

ALTER TABLE pharmacy_svc.dispense
    ADD COLUMN IF NOT EXISTS billing_status text NOT NULL DEFAULT 'NONE'
    CHECK (billing_status IN ('NONE', 'PENDING', 'CHARGED', 'CHARGE_FAILED', 'REFUND_PENDING', 'REFUND_FAILED'));

COMMENT ON COLUMN pharmacy_svc.dispense.billing_status IS
    'NONE=未启用发药计费；PENDING=待收费服务确认；CHARGED=已计费；CHARGE_FAILED=计费失败可重试；'
    'REFUND_PENDING=退药已登记待财务退费；REFUND_FAILED=退药冲减同步失败可重试';

-- 补偿扫描：只看异常态，索引很小
CREATE INDEX IF NOT EXISTS ix_dispense_billing_abnormal
    ON pharmacy_svc.dispense (billing_status, dispensed_at DESC)
    WHERE billing_status IN ('PENDING', 'CHARGE_FAILED', 'REFUND_FAILED');
