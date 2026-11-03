-- ============================================================================
-- V13：药品盘点（账面快照 → 实盘录入 → 提交 → 审批校准）
--
-- 为什么需要：采购/发药/退药只能解释"有据可查"的库存变化，盘盈盘亏（破损、
-- 拆零误差、效期报废、记录错误）此前没有任何入口，库存与实物只能越走越偏。
--
-- 三条硬约束：
--   1. 同一时刻只允许一张进行中的盘点单（COUNTING/SUBMITTED），避免两次盘点
--      叠加调整，把差异算两遍；
--   2. 创建盘点单时快照每个批次的账面数量（book_quantity），审批时若批次当前
--      数量与快照不一致（盘点期间又发了药/入了库），整单拒绝并要求重新盘点；
--   3. 审批产生的每一条差异都写入 stock_adjustment（追加式），库存校准不可抹痕。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.stock_take_no_seq START 1;

CREATE TABLE IF NOT EXISTS pharmacy_svc.stock_take (
    id            uuid PRIMARY KEY,
    take_no       text NOT NULL UNIQUE,
    status        text NOT NULL DEFAULT 'COUNTING'
                  CHECK (status IN ('COUNTING', 'SUBMITTED', 'APPROVED', 'CANCELLED')),
    scope_label   text,
    note          text,
    created_by    text NOT NULL,
    submitted_by  text,
    submitted_at  timestamptz,
    approved_by   text,
    approved_at   timestamptz,
    cancelled_by  text,
    cancelled_at  timestamptz,
    cancel_reason text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_take_submitted CHECK (
        (status IN ('SUBMITTED', 'APPROVED') AND submitted_by IS NOT NULL
            AND submitted_at IS NOT NULL)
        OR status IN ('COUNTING', 'CANCELLED')
    ),
    CONSTRAINT ck_stock_take_approved CHECK (
        (status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR status <> 'APPROVED'
    ),
    CONSTRAINT ck_stock_take_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

-- 常量表达式唯一索引：全库同时最多一张 COUNTING 或 SUBMITTED 的盘点单
CREATE UNIQUE INDEX IF NOT EXISTS ux_stock_take_single_active
    ON pharmacy_svc.stock_take ((1))
    WHERE status IN ('COUNTING', 'SUBMITTED');

CREATE INDEX IF NOT EXISTS ix_stock_take_status
    ON pharmacy_svc.stock_take (status, created_at DESC);

COMMENT ON TABLE pharmacy_svc.stock_take IS '药品盘点单（全库同一时刻仅一张进行中）';

CREATE TABLE IF NOT EXISTS pharmacy_svc.stock_take_item (
    id              uuid PRIMARY KEY,
    take_id         uuid NOT NULL REFERENCES pharmacy_svc.stock_take (id),
    drug_id         uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id        uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    book_quantity   int NOT NULL CHECK (book_quantity >= 0),
    actual_quantity int CHECK (actual_quantity IS NULL OR actual_quantity >= 0),
    reason          text,
    counted_by      text,
    counted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_stock_take_item_batch UNIQUE (take_id, batch_id),
    CONSTRAINT ck_stock_take_item_counted CHECK (
        (actual_quantity IS NULL AND counted_by IS NULL AND counted_at IS NULL)
        OR (actual_quantity IS NOT NULL AND counted_by IS NOT NULL AND counted_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_stock_take_item_take
    ON pharmacy_svc.stock_take_item (take_id, drug_id);

COMMENT ON TABLE pharmacy_svc.stock_take_item IS
    '盘点明细（账面数量为建单快照；实盘未录入时 actual_quantity 为空）';

-- 盘点单审批/取消后，明细一律不可再改（含补录、改数量、删除）
CREATE OR REPLACE FUNCTION pharmacy_svc.reject_locked_stock_take_item() RETURNS trigger AS $$
DECLARE
    take_status text;
BEGIN
    SELECT status INTO take_status
      FROM pharmacy_svc.stock_take
     WHERE id = COALESCE(NEW.take_id, OLD.take_id);
    IF take_status IN ('APPROVED', 'CANCELLED') THEN
        RAISE EXCEPTION '盘点单已关闭（%），明细不可修改', take_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_take_item_locked ON pharmacy_svc.stock_take_item;
CREATE TRIGGER trg_stock_take_item_locked
    BEFORE INSERT OR UPDATE OR DELETE ON pharmacy_svc.stock_take_item
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_locked_stock_take_item();

CREATE TABLE IF NOT EXISTS pharmacy_svc.stock_adjustment (
    id              uuid PRIMARY KEY,
    take_id         uuid NOT NULL REFERENCES pharmacy_svc.stock_take (id),
    drug_id         uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id        uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    before_quantity int NOT NULL CHECK (before_quantity >= 0),
    after_quantity  int NOT NULL CHECK (after_quantity >= 0),
    delta           int NOT NULL CHECK (delta <> 0),
    reason          text,
    actor           text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_adjustment_delta CHECK (after_quantity - before_quantity = delta)
);

CREATE INDEX IF NOT EXISTS ix_stock_adjustment_take
    ON pharmacy_svc.stock_adjustment (take_id, created_at);

DROP TRIGGER IF EXISTS trg_stock_adjustment_append_only ON pharmacy_svc.stock_adjustment;
CREATE TRIGGER trg_stock_adjustment_append_only
    BEFORE UPDATE OR DELETE ON pharmacy_svc.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

COMMENT ON TABLE pharmacy_svc.stock_adjustment IS
    '盘点审批产生的库存校准流水（追加式：库存调整不可篡改）';
