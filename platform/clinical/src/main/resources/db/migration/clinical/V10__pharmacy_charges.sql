-- ============================================================================
-- 临床核心 V10：药房发药费用联动（N2 收尾）
--
-- 业务背景：药房的"处方"与临床的"收费单"此前是两条链——发药金额从不进账，
-- 退药也不会冲账。本迁移让 `billing_mode=DISPENSE` 的处方在发药时写入费用明细，
-- 退药时按"累计已退金额"幂等冲减未结收费单；已结算的收费单只登记待财务退费。
--
-- 设计要点：
--   1) 幂等键：发药用 `DISPENSE:{dispenseItemId}`，退药冲减用
--      `DISPENSE_RETURN:{dispenseItemId}:{累计金额}`（同一累计值重放不会重复冲减）；
--   2) 负向明细只允许退药冲减产生（source_key 前缀约束），避免任意负金额污染收费单；
--   3) `pharmacy_charge` 是药房费用与收费单的关联表，同时承载"已冲减 / 待退费 / 已退费"金额，
--      三者和不得超过原发药金额；
--   4) 已结算（PAID）的收费单一律不改账，只登记 `refund_pending_amount`，
--      由财务/支付渠道走退费流程后调用 complete 接口闭环（不伪造"已退款"）。
-- ============================================================================

-- ---- 1. 费用明细允许负向冲减，但只能是退药产生 ----
ALTER TABLE clinical_svc.charge_item
    DROP CONSTRAINT IF EXISTS charge_item_amount_check;
ALTER TABLE clinical_svc.charge_item
    DROP CONSTRAINT IF EXISTS ck_charge_item_amount;
ALTER TABLE clinical_svc.charge_item
    ADD CONSTRAINT ck_charge_item_amount CHECK (amount <> 0);

COMMENT ON CONSTRAINT ck_charge_item_amount ON clinical_svc.charge_item IS
    '金额不得为 0；负向明细（退药冲减）由 trg_charge_item_source 限定只能使用 DISPENSE_RETURN: 前缀';

-- ---- 2. 无医嘱明细的 source_key 允许 REG: 与 DISPENSE:；负金额规则 ----
CREATE OR REPLACE FUNCTION clinical_svc.check_charge_item_source() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    -- 负金额只允许退药冲减（红字），防止绕过应用层写负数
    IF NEW.amount < 0
       AND (NEW.source_key IS NULL OR NEW.source_key NOT LIKE 'DISPENSE_RETURN:%') THEN
        RAISE EXCEPTION '负向费用明细只能由退药冲减产生（source_key=%）', NEW.source_key
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.order_id IS NULL THEN
        IF NEW.source_key IS NULL
           OR (NEW.source_key NOT LIKE 'REG:%' AND NEW.source_key NOT LIKE 'DISPENSE:%') THEN
            RAISE EXCEPTION '无医嘱的费用明细必须使用 REG: 或 DISPENSE: 前缀（source_key=%）', NEW.source_key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT order_mode INTO mode FROM clinical_svc.visit_order WHERE id = NEW.order_id;
    IF mode IS NULL THEN
        RAISE EXCEPTION '费用明细引用的医嘱不存在：%', NEW.order_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.source_key LIKE 'DAILY:%' AND mode <> 'LONG' THEN
        RAISE EXCEPTION '按日计费（DAILY:）只能对应长期医嘱（order_id=%, mode=%）', NEW.order_id, mode
            USING ERRCODE = 'check_violation';
    ELSIF NEW.source_key LIKE 'ORDER:%' AND mode <> 'TEMP' THEN
        RAISE EXCEPTION '一次性计费（ORDER:）只能对应临时医嘱（order_id=%, mode=%）', NEW.order_id, mode
            USING ERRCODE = 'check_violation';
    ELSIF NEW.source_key NOT LIKE 'DAILY:%' AND NEW.source_key NOT LIKE 'ORDER:%' THEN
        RAISE EXCEPTION '医嘱费用明细的 source_key 必须以 ORDER: 或 DAILY: 开头（source_key=%）', NEW.source_key
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION clinical_svc.check_charge_item_source() IS
    '费用明细 source_key 兜底：REG/DISPENSE 无医嘱；DAILY/ORDER 与医嘱模式一致；负金额仅限退药冲减';

DROP TRIGGER IF EXISTS trg_charge_item_source ON clinical_svc.charge_item;
CREATE TRIGGER trg_charge_item_source
    BEFORE INSERT ON clinical_svc.charge_item
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.check_charge_item_source();

-- ---- 3. 药房费用关联（发药明细 ↔ 收费明细）----
CREATE TABLE IF NOT EXISTS clinical_svc.pharmacy_charge (
    dispense_item_id      uuid PRIMARY KEY,
    visit_id              uuid NOT NULL REFERENCES clinical_svc.visit (id),
    charge_id             uuid NOT NULL REFERENCES clinical_svc.charge (id),
    charge_item_id        uuid NOT NULL REFERENCES clinical_svc.charge_item (id),
    drug_code             text NOT NULL,
    drug_name             text NOT NULL,
    amount                numeric(12, 2) NOT NULL CHECK (amount > 0),
    -- 已冲减（原收费单未结算时直接改账）
    refunded_amount       numeric(12, 2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    -- 待财务退费（原收费单已结算，不能直接改账）
    refund_pending_amount numeric(12, 2) NOT NULL DEFAULT 0 CHECK (refund_pending_amount >= 0),
    -- 财务已确认退费
    refund_done_amount    numeric(12, 2) NOT NULL DEFAULT 0 CHECK (refund_done_amount >= 0),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pharmacy_charge_refund CHECK (
        refunded_amount + refund_pending_amount + refund_done_amount <= amount
    )
);
CREATE INDEX IF NOT EXISTS ix_pharmacy_charge_visit
    ON clinical_svc.pharmacy_charge (visit_id);
CREATE INDEX IF NOT EXISTS ix_pharmacy_charge_pending
    ON clinical_svc.pharmacy_charge (updated_at DESC) WHERE refund_pending_amount > 0;

COMMENT ON TABLE clinical_svc.pharmacy_charge IS
    '药房发药费用关联：发药明细 ↔ 收费明细，并累计已冲减/待退费/已退费金额（退药幂等依据）';
COMMENT ON COLUMN clinical_svc.pharmacy_charge.refund_pending_amount IS
    '已结算收费单不能直接改账，退药金额在此登记，由财务退费后调用 complete 接口核销';
