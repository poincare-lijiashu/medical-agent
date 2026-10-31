-- ============================================================================
-- V6：医保结算清单导出批次与回执登记（N5 第二批 B）
--
-- 背景：确认后的清单需要按期间报送给省局平台，并登记省局回执（受理/退回）。
-- 没有批次留档时，"上个月报给省局的是什么金额、回执号多少"只能靠人工台账。
--
-- 设计：
--   * 一个批次固化一份 XML 报盘（payload + sha256），事后可原样复现，不受目录/参保
--     参数后续调整影响；
--   * 批次状态机 GENERATED → ACCEPTED / REJECTED（单向，带 version CAS）；
--   * settlement_export_item 记录批次包含的清单，便于按清单反查"报过哪几批"；
--   * 报盘只包含 CONFIRMED 清单（DRAFT/VOIDED 不导出），按 confirmed_at 落在期间内筛选。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.settlement_export_batch (
    id               uuid PRIMARY KEY,
    batch_no         text NOT NULL UNIQUE,
    period_from      date NOT NULL,
    period_to        date NOT NULL,
    bill_count       int NOT NULL CHECK (bill_count > 0),
    total_amount     numeric(12, 2) NOT NULL CHECK (total_amount >= 0),
    insurance_amount numeric(12, 2) NOT NULL CHECK (insurance_amount >= 0),
    self_pay_amount  numeric(12, 2) NOT NULL CHECK (self_pay_amount >= 0),
    payload          text NOT NULL,
    payload_hash     text NOT NULL,
    status           text NOT NULL DEFAULT 'GENERATED'
                     CHECK (status IN ('GENERATED', 'ACCEPTED', 'REJECTED')),
    receipt_no       text,
    receipt_at       timestamptz,
    receipt_message  text,
    created_by       text NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_export_period CHECK (period_to >= period_from),
    CONSTRAINT ck_export_receipt CHECK (
        (status = 'GENERATED' AND receipt_no IS NULL AND receipt_at IS NULL)
        OR (status IN ('ACCEPTED', 'REJECTED') AND receipt_no IS NOT NULL AND receipt_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_export_batch_created
    ON insurance_svc.settlement_export_batch (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_export_batch_period
    ON insurance_svc.settlement_export_batch (period_from, period_to);

CREATE TABLE IF NOT EXISTS insurance_svc.settlement_export_item (
    batch_id uuid NOT NULL REFERENCES insurance_svc.settlement_export_batch (id),
    bill_id  uuid NOT NULL,
    bill_no  text NOT NULL,
    PRIMARY KEY (batch_id, bill_id)
);

CREATE INDEX IF NOT EXISTS ix_export_item_bill
    ON insurance_svc.settlement_export_item (bill_id);

CREATE SEQUENCE IF NOT EXISTS insurance_svc.export_batch_no_seq START 1;
