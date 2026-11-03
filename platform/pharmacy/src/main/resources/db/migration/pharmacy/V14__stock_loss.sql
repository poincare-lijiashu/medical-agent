-- ============================================================================
-- V14：药品报损与销毁（破损 / 过期报废 / 召回销毁）
--
-- 为什么需要：盘点只能发现账实差异，无法解释"这些药为什么少了"。
-- 破损、过期报废、厂家召回销毁必须有独立单据与审批，才能把库存扣减
-- 归因到具体批次、具体原因与具体审批人。
--
-- 约束：
--   1. 报损单提交即锁定明细；审批人 ≠ 申请人（四眼原则）；
--   2. 审批时逐批加行锁并校验余量，扣减走原子 UPDATE（quantity >= qty），
--      任一明细不足则整单回滚；
--   3. 扣减同时写 stock_adjustment（追加式），与盘点校准共用一条库存调整流水，
--      库存变化只有一个可追溯出口。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.stock_loss_no_seq START 1;

CREATE TABLE IF NOT EXISTS pharmacy_svc.stock_loss (
    id              uuid PRIMARY KEY,
    loss_no         text NOT NULL UNIQUE,
    status          text NOT NULL DEFAULT 'SUBMITTED'
                    CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    reason_category text NOT NULL
                    CHECK (reason_category IN ('EXPIRED', 'BROKEN', 'RECALL', 'OTHER')),
    note            text,
    created_by      text NOT NULL,
    reviewed_by     text,
    reviewed_at     timestamptz,
    review_note     text,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_loss_reviewed CHECK (
        (status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR status = 'SUBMITTED'
    )
);

CREATE INDEX IF NOT EXISTS ix_stock_loss_status
    ON pharmacy_svc.stock_loss (status, created_at DESC);

COMMENT ON TABLE pharmacy_svc.stock_loss IS '药品报损单（提交→审批扣减库存 / 驳回）';

CREATE TABLE IF NOT EXISTS pharmacy_svc.stock_loss_item (
    id          uuid PRIMARY KEY,
    loss_id     uuid NOT NULL REFERENCES pharmacy_svc.stock_loss (id),
    drug_id     uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id    uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    quantity    int NOT NULL CHECK (quantity > 0),
    reason      text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_stock_loss_item_batch UNIQUE (loss_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_stock_loss_item_loss
    ON pharmacy_svc.stock_loss_item (loss_id, drug_id);

COMMENT ON TABLE pharmacy_svc.stock_loss_item IS '报损明细（同一报损单同一批次只能出现一次）';

-- 报损单审批/驳回后，明细不可再改
CREATE OR REPLACE FUNCTION pharmacy_svc.reject_locked_stock_loss_item() RETURNS trigger AS $$
DECLARE
    loss_status text;
BEGIN
    SELECT status INTO loss_status
      FROM pharmacy_svc.stock_loss
     WHERE id = COALESCE(NEW.loss_id, OLD.loss_id);
    IF loss_status IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION '报损单已关闭（%），明细不可修改', loss_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_loss_item_locked ON pharmacy_svc.stock_loss_item;
CREATE TRIGGER trg_stock_loss_item_locked
    BEFORE INSERT OR UPDATE OR DELETE ON pharmacy_svc.stock_loss_item
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_locked_stock_loss_item();

-- 库存调整流水支持"报损"来源：盘点（take_id）与报损（loss_id）二选一
ALTER TABLE pharmacy_svc.stock_adjustment ALTER COLUMN take_id DROP NOT NULL;
ALTER TABLE pharmacy_svc.stock_adjustment
    ADD COLUMN IF NOT EXISTS loss_id uuid REFERENCES pharmacy_svc.stock_loss (id);

ALTER TABLE pharmacy_svc.stock_adjustment
    DROP CONSTRAINT IF EXISTS ck_stock_adjustment_source;
ALTER TABLE pharmacy_svc.stock_adjustment
    ADD CONSTRAINT ck_stock_adjustment_source CHECK (
        (take_id IS NOT NULL AND loss_id IS NULL)
        OR (take_id IS NULL AND loss_id IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS ix_stock_adjustment_loss
    ON pharmacy_svc.stock_adjustment (loss_id, created_at);

COMMENT ON COLUMN pharmacy_svc.stock_adjustment.take_id IS '盘点来源（与 loss_id 二选一）';
COMMENT ON COLUMN pharmacy_svc.stock_adjustment.loss_id IS '报损来源（与 take_id 二选一）';
