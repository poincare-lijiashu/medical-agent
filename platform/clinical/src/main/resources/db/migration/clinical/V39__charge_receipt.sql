-- ============================================================================
-- V39：收费票据（G14）
--
-- 业务口径：
--   * 一张已结算收费单可开一张有效票据；票据金额/支付方式取收费单快照；
--   * 重打不换号，只累加 reprint_count，用于审计"这张票打了几次"；
--   * 作废必须写原因；作废后可为同一收费单重新开票（换开），
--     因此"一张收费单最多一张有效票据"用部分唯一索引保证，而不是唯一键锁死。
--
-- 为什么金额/支付方式要落快照：
--   收费单后续若有冲减/退费，历史票据必须保持开票时的金额与支付方式，
--   不能因为业务表变化而改写已交给患者的凭证。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS clinical_svc.charge_receipt_no_seq START 1;

CREATE TABLE IF NOT EXISTS clinical_svc.charge_receipt (
    id             uuid PRIMARY KEY,
    receipt_no     text NOT NULL UNIQUE,                        -- RPYYYYMMDD######
    charge_id      uuid NOT NULL REFERENCES clinical_svc.charge (id),
    visit_id       uuid NOT NULL,
    amount         numeric(14, 2) NOT NULL CHECK (amount >= 0),
    payment_method text,
    status         text NOT NULL DEFAULT 'ISSUED'
                   CHECK (status IN ('ISSUED', 'VOID')),
    issued_by      text NOT NULL,
    issued_at      timestamptz NOT NULL DEFAULT now(),
    voided_by      text,
    voided_at      timestamptz,
    void_reason    text,
    reprint_count  int NOT NULL DEFAULT 0 CHECK (reprint_count >= 0),
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_charge_receipt_void CHECK (
        (status = 'VOID' AND voided_by IS NOT NULL AND voided_at IS NOT NULL
            AND void_reason IS NOT NULL)
        OR status = 'ISSUED'
    )
);

-- 同一收费单同时只能有一张有效票据；作废后可换开新票
CREATE UNIQUE INDEX IF NOT EXISTS ux_charge_receipt_active
    ON clinical_svc.charge_receipt (charge_id) WHERE status = 'ISSUED';
CREATE INDEX IF NOT EXISTS ix_charge_receipt_visit
    ON clinical_svc.charge_receipt (visit_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS ix_charge_receipt_charge
    ON clinical_svc.charge_receipt (charge_id, issued_at DESC);

COMMENT ON TABLE clinical_svc.charge_receipt IS
    '收费票据：金额/支付方式为开票快照；重打累计次数，作废留原因并可换开';

-- 票据是凭证：禁止删除；作废后不可再改（重打只允许 ISSUED 状态）
CREATE OR REPLACE FUNCTION clinical_svc.guard_charge_receipt() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '收费票据不允许删除（票据是财务凭证）'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.status = 'VOID'
       AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.amount IS DISTINCT FROM OLD.amount
            OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
            OR NEW.charge_id IS DISTINCT FROM OLD.charge_id
            OR NEW.visit_id IS DISTINCT FROM OLD.visit_id
            OR NEW.issued_by IS DISTINCT FROM OLD.issued_by
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.voided_by IS DISTINCT FROM OLD.voided_by
            OR NEW.voided_at IS DISTINCT FROM OLD.voided_at
            OR NEW.void_reason IS DISTINCT FROM OLD.void_reason) THEN
        RAISE EXCEPTION '收费票据已作废，不允许修改'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_charge_receipt_guard ON clinical_svc.charge_receipt;
CREATE TRIGGER trg_charge_receipt_guard
    BEFORE UPDATE OR DELETE ON clinical_svc.charge_receipt
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_charge_receipt();
