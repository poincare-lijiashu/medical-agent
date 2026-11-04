-- ============================================================================
-- V3：耗材跨科室调拨（科室二级库 → 科室二级库，G12）
--
-- 场景：病区之间、术间与病区之间临时借用/调拨耗材，不需要回中心库房再出库。
--
-- 口径：
--   * 调出科室发起（SUBMITTED），写明原因与批次；
--   * 调入科室接收（ACCEPTED）时同一事务内扣调出科室库、加调入科室库，
--     因此"在途"期间耗材仍记在调出科室，不会凭空消失或重复计数；
--   * 拒收（REJECTED）与撤回（CANCELLED）都不动库存；
--   * 调出科室 = 调入科室直接拒绝（422）；
--   * 接收人不得是申请人（自审拦截），拒收同样适用。
--
-- 与 G9 退回的区别：退回是"科室 → 中心批次"，跨科室调拨不改变中心批次库存。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS material_svc.dept_transfer_no_seq START 1;

CREATE TABLE IF NOT EXISTS material_svc.dept_transfer (
    id            uuid PRIMARY KEY,
    transfer_no   text NOT NULL UNIQUE,                          -- DTYYYYMMDD######
    status        text NOT NULL DEFAULT 'SUBMITTED'
                  CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    from_dept_id  uuid NOT NULL,
    to_dept_id    uuid NOT NULL,
    reason        text NOT NULL,
    note          text,
    created_by    text NOT NULL,
    accepted_by   text,
    accepted_at   timestamptz,
    rejected_by   text,
    rejected_at   timestamptz,
    reject_reason text,
    cancelled_by  text,
    cancelled_at  timestamptz,
    cancel_reason text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_dept_transfer_distinct CHECK (from_dept_id <> to_dept_id),
    CONSTRAINT ck_dept_transfer_accepted CHECK (
        (status = 'ACCEPTED' AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL)
        OR status <> 'ACCEPTED'
    ),
    CONSTRAINT ck_dept_transfer_rejected CHECK (
        (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL
            AND reject_reason IS NOT NULL)
        OR status <> 'REJECTED'
    ),
    CONSTRAINT ck_dept_transfer_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

CREATE INDEX IF NOT EXISTS ix_dept_transfer_status
    ON material_svc.dept_transfer (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_dept_transfer_from
    ON material_svc.dept_transfer (from_dept_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_dept_transfer_to
    ON material_svc.dept_transfer (to_dept_id, created_at DESC);

COMMENT ON TABLE material_svc.dept_transfer IS
    '耗材跨科室调拨单（接收时同一事务扣源科室库、加目标科室库）';

CREATE TABLE IF NOT EXISTS material_svc.dept_transfer_item (
    id            uuid PRIMARY KEY,
    transfer_id   uuid NOT NULL REFERENCES material_svc.dept_transfer (id),
    consumable_id uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    batch_id      uuid NOT NULL REFERENCES material_svc.consumable_batch (id),
    quantity      int NOT NULL CHECK (quantity > 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_dept_transfer_item_batch UNIQUE (transfer_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_dept_transfer_item_transfer
    ON material_svc.dept_transfer_item (transfer_id, consumable_id);

COMMENT ON TABLE material_svc.dept_transfer_item IS
    '跨科室调拨明细（同一调拨单同一批次只能出现一次）';

CREATE OR REPLACE FUNCTION material_svc.reject_locked_dept_transfer_item()
RETURNS trigger AS $$
DECLARE
    transfer_status text;
BEGIN
    SELECT status INTO transfer_status
      FROM material_svc.dept_transfer
     WHERE id = COALESCE(NEW.transfer_id, OLD.transfer_id);
    IF transfer_status IN ('ACCEPTED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '调拨单已关闭（%），明细不可修改', transfer_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_dept_transfer_item_locked ON material_svc.dept_transfer_item;
CREATE TRIGGER trg_dept_transfer_item_locked
    BEFORE INSERT OR UPDATE OR DELETE ON material_svc.dept_transfer_item
    FOR EACH ROW EXECUTE FUNCTION material_svc.reject_locked_dept_transfer_item();
