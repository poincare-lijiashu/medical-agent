-- ============================================================================
-- V28：医嘱退药冲减支持"按日定位"
--
-- 背景（多智能体评审 P0-1）：长嘱按日计费的明细键是 DAILY:{orderId}:{date}，
-- 而退药冲减只按 ORDER:{orderId} 找收费单 —— 长嘱永远找不到已计费明细，
-- 只能记 applied；但 dailyBill 按全额入账、settle 又只处理临时医嘱，
-- 于是 applied 没有任何消费方，退药只回库存不退钱（资金死账）。
--
-- 修复：
--   1. order_refund 增加 supply_date：长嘱退药带上"供药日期"，据此定位当日 DAILY 明细；
--      临时医嘱为 NULL，语义不变。
--   2. 该日未计费时先记 applied，dailyBill 按 (order_id, supply_date) 扣减后再入账。
--
-- 兼容：老数据 supply_date 为空，按原有 ORDER: 口径处理，不回溯。
-- ============================================================================

ALTER TABLE clinical_svc.order_refund
    ADD COLUMN IF NOT EXISTS supply_date date;

COMMENT ON COLUMN clinical_svc.order_refund.supply_date IS
    '长嘱退药对应的供药日期：用于定位当日 DAILY:{orderId}:{date} 计费明细；临时医嘱为 NULL';

CREATE INDEX IF NOT EXISTS ix_order_refund_order_supply
    ON clinical_svc.order_refund (order_id, supply_date);
