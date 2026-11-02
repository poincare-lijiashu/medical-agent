-- ============================================================================
-- V23：医嘱计费模式（billing_mode=ORDER）下的退药冲减
--
-- 背景（多智能体评审 B3，资金红线）：
--   药房发药计费（DISPENSE）已有负向明细/待退费双路径；但"医嘱计费"（ORDER）模式下，
--   钱是在临床结算时按医嘱一次性收的，退药只回补库存、**不退钱也没有账务痕迹**。
--
-- 设计：
--   1. order_refund 以 dispense_item_id 为幂等键，记录每个发药明细的
--      amount（可退上限）/ applied（未结算前已直接冲减）/ pending（已结算待财务退费）/ done（已退费）；
--   2. 未结算：结算时按 applied 从医嘱金额扣减（BillingService.settle）；
--   3. 已结算：登记 pending，由财务工作台确认后退费（done）；
--   4. 负向明细白名单增加 ORDER_RETURN:，仍禁止其它任何负金额来源。
--
-- 为什么新增表而不是改 visit_order：一份医嘱可能拆成多个发药明细（跨批次），
-- 冲减必须按明细幂等，否则重试会重复冲账。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.order_refund (
    id                uuid PRIMARY KEY,
    order_id          uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    dispense_item_id  uuid NOT NULL UNIQUE,
    visit_id          uuid NOT NULL REFERENCES clinical_svc.visit (id),
    amount            numeric(12, 2) NOT NULL CHECK (amount > 0),
    applied_amount    numeric(12, 2) NOT NULL DEFAULT 0 CHECK (applied_amount >= 0),
    pending_amount    numeric(12, 2) NOT NULL DEFAULT 0 CHECK (pending_amount >= 0),
    done_amount       numeric(12, 2) NOT NULL DEFAULT 0 CHECK (done_amount >= 0),
    reason            text,
    created_by        text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_refund_cap
        CHECK (applied_amount + pending_amount + done_amount <= amount)
);

COMMENT ON TABLE clinical_svc.order_refund IS
    '医嘱计费模式的退药冲减：applied=未结算直接冲减；pending=已结算待财务退费；done=已退费';
COMMENT ON COLUMN clinical_svc.order_refund.dispense_item_id IS '幂等键：同一个发药明细的累计冲减只记一次';

CREATE INDEX IF NOT EXISTS ix_order_refund_order ON clinical_svc.order_refund (order_id);
CREATE INDEX IF NOT EXISTS ix_order_refund_pending ON clinical_svc.order_refund (visit_id)
    WHERE pending_amount > 0;

CREATE OR REPLACE FUNCTION clinical_svc.touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_order_refund_touch ON clinical_svc.order_refund;
CREATE TRIGGER trg_order_refund_touch
    BEFORE UPDATE ON clinical_svc.order_refund
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.touch_updated_at();

-- ---- 负向明细白名单：允许 ORDER_RETURN:（医嘱退药），其余规则不变 ----
CREATE OR REPLACE FUNCTION clinical_svc.check_charge_item_source() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    -- 负金额只允许退药冲减（红字）：发药计费 DISPENSE_RETURN / 医嘱计费 ORDER_RETURN
    IF NEW.amount < 0 THEN
        IF NEW.source_key IS NULL
           OR (NEW.source_key NOT LIKE 'DISPENSE_RETURN:%'
               AND NEW.source_key NOT LIKE 'ORDER_RETURN:%') THEN
            RAISE EXCEPTION '负向费用明细只能由退药冲减产生（source_key=%）', NEW.source_key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.order_id IS NULL THEN
        IF NEW.source_key IS NULL
           OR (NEW.source_key NOT LIKE 'REG:%'
               AND NEW.source_key NOT LIKE 'DISPENSE:%'
               AND NEW.source_key NOT LIKE 'SURGERY:%') THEN
            RAISE EXCEPTION '无医嘱的费用明细必须使用 REG: / DISPENSE: / SURGERY: 前缀（source_key=%）', NEW.source_key
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
    '费用明细 source_key 兜底：REG/DISPENSE/SURGERY 无医嘱；DAILY/ORDER 与医嘱模式一致；负金额仅限退药冲减（DISPENSE_RETURN/ORDER_RETURN）';
