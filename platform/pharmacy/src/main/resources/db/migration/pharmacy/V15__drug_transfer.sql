-- ============================================================================
-- V15：药品科室调拨（中心药房 → 科室二级库）
--
-- 业务闭环：药房提交调拨 → 药房确认发出（扣中心批次库存）→
--           科室接收（入科室二级库）或拒收（退回中心库存）。
--
-- 关键约束：
--   1. 状态机 SUBMITTED → DISPATCHED → ACCEPTED / REJECTED；SUBMITTED 可撤回；
--   2. 发出时按批次原子扣减中心库存（quantity >= qty），任一批次不足整单回滚；
--   3. 接收时按 (dept, drug, batch) 累加科室库存，同批次可多次调拨；
--   4. 拒收必须写原因，并把已扣库存原路退回中心批次；
--   5. 明细在单子关闭（ACCEPTED/REJECTED/CANCELLED）后库级禁止修改。
--
-- 说明：调拨属于"库存位置变化"而非盘盈盘亏，因此不写 stock_adjustment；
-- 盘点/报损的调整流水仍只记录账实差异。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.drug_transfer_no_seq START 1;

CREATE TABLE IF NOT EXISTS pharmacy_svc.drug_transfer (
    id             uuid PRIMARY KEY,
    transfer_no    text NOT NULL UNIQUE,
    status         text NOT NULL DEFAULT 'SUBMITTED'
                   CHECK (status IN ('SUBMITTED', 'DISPATCHED', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    to_dept_id     uuid NOT NULL,
    note           text,
    created_by     text NOT NULL,
    dispatched_by  text,
    dispatched_at  timestamptz,
    accepted_by    text,
    accepted_at    timestamptz,
    rejected_by    text,
    rejected_at    timestamptz,
    reject_reason  text,
    cancelled_by   text,
    cancelled_at   timestamptz,
    cancel_reason  text,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_drug_transfer_dispatched CHECK (
        (status IN ('DISPATCHED', 'ACCEPTED', 'REJECTED')
            AND dispatched_by IS NOT NULL AND dispatched_at IS NOT NULL)
        OR status IN ('SUBMITTED', 'CANCELLED')
    ),
    CONSTRAINT ck_drug_transfer_accepted CHECK (
        (status = 'ACCEPTED' AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL)
        OR status <> 'ACCEPTED'
    ),
    CONSTRAINT ck_drug_transfer_rejected CHECK (
        (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL
            AND reject_reason IS NOT NULL)
        OR status <> 'REJECTED'
    ),
    CONSTRAINT ck_drug_transfer_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

CREATE INDEX IF NOT EXISTS ix_drug_transfer_status
    ON pharmacy_svc.drug_transfer (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_drug_transfer_dept
    ON pharmacy_svc.drug_transfer (to_dept_id, created_at DESC);

COMMENT ON TABLE pharmacy_svc.drug_transfer IS
    '药品科室调拨单（药房→科室二级库；发出扣库、接收入库、拒收退回）';

CREATE TABLE IF NOT EXISTS pharmacy_svc.drug_transfer_item (
    id          uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES pharmacy_svc.drug_transfer (id),
    drug_id     uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id    uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    quantity    int NOT NULL CHECK (quantity > 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_drug_transfer_item_batch UNIQUE (transfer_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_drug_transfer_item_transfer
    ON pharmacy_svc.drug_transfer_item (transfer_id, drug_id);

COMMENT ON TABLE pharmacy_svc.drug_transfer_item IS
    '调拨明细（同一调拨单同一批次只能出现一次）';

CREATE OR REPLACE FUNCTION pharmacy_svc.reject_locked_drug_transfer_item() RETURNS trigger AS $$
DECLARE
    transfer_status text;
BEGIN
    SELECT status INTO transfer_status
      FROM pharmacy_svc.drug_transfer
     WHERE id = COALESCE(NEW.transfer_id, OLD.transfer_id);
    IF transfer_status IN ('ACCEPTED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '调拨单已关闭（%），明细不可修改', transfer_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_drug_transfer_item_locked ON pharmacy_svc.drug_transfer_item;
CREATE TRIGGER trg_drug_transfer_item_locked
    BEFORE INSERT OR UPDATE OR DELETE ON pharmacy_svc.drug_transfer_item
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_locked_drug_transfer_item();

-- 科室二级库余额（按 科室 + 药品 + 批次 唯一）
CREATE TABLE IF NOT EXISTS pharmacy_svc.dept_drug_stock (
    id         uuid PRIMARY KEY,
    dept_id    uuid NOT NULL,
    drug_id    uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id   uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    quantity   int NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_dept_drug_stock UNIQUE (dept_id, drug_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_dept_drug_stock_dept
    ON pharmacy_svc.dept_drug_stock (dept_id, drug_id);

COMMENT ON TABLE pharmacy_svc.dept_drug_stock IS
    '科室二级库药品余额（按科室+药品+批次；接收调拨时累加）';
