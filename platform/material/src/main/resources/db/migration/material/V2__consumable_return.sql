-- ============================================================================
-- V2：耗材科室二级库 + 未使用退回（科室 → 中心库房）
--
-- 为什么需要二级库：
--   出库单只说明"库房把这批耗材发给了某科室"，并不代表耗材已经用在患者身上；
--   病区/手术间会留存未用完的耗材。没有科室余额，就无法回答两件事：
--     1) 科室现在还剩多少、能不能退；
--     2) 退回来的批次是不是当初发出去的那一批（召回口径）。
--
-- 库存口径：
--   * 出库成功 → 科室二级库按 (dept, consumable, batch) 累加；
--   * 退回审批通过 → 科室二级库扣减（quantity >= qty 守卫）、中心批次库存回冲；
--   * 退回提交/驳回/撤回都不动库存，避免"审批未通过库先变"。
--
-- 关键约束：
--   1. 状态机 SUBMITTED → APPROVED / REJECTED；SUBMITTED 可撤回；
--   2. 审批人不得是申请人（四眼），由服务层强制；驳回/撤回必须写原因；
--   3. 同一退回单同一批次只能出现一次；
--   4. 明细在单子关闭（APPROVED/REJECTED/CANCELLED）后库级禁止修改。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS material_svc.consumable_return_no_seq START 1;

-- ---- 科室二级库余额（按 科室 + 耗材 + 批次 唯一）----
CREATE TABLE IF NOT EXISTS material_svc.dept_consumable_stock (
    id            uuid PRIMARY KEY,
    dept_id       uuid NOT NULL,
    consumable_id uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    batch_id      uuid NOT NULL REFERENCES material_svc.consumable_batch (id),
    quantity      int NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_dept_consumable_stock UNIQUE (dept_id, consumable_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_dept_consumable_stock_dept
    ON material_svc.dept_consumable_stock (dept_id, consumable_id);

COMMENT ON TABLE material_svc.dept_consumable_stock IS
    '科室二级库耗材余额（按科室+耗材+批次；出库累加、退回审批通过扣减）';

-- 历史回填：按已出库明细的科室汇总生成初始余额。
-- 出库明细本身没有 dept_id，必须 join 出库单取科室。
INSERT INTO material_svc.dept_consumable_stock
    (id, dept_id, consumable_id, batch_id, quantity)
SELECT gen_random_uuid(), i.dept_id, ii.consumable_id, ii.batch_id, sum(ii.quantity)
  FROM material_svc.consumable_issue_item ii
  JOIN material_svc.consumable_issue i ON i.id = ii.issue_id
 GROUP BY i.dept_id, ii.consumable_id, ii.batch_id
HAVING sum(ii.quantity) > 0
ON CONFLICT (dept_id, consumable_id, batch_id) DO NOTHING;

-- ---- 未使用退回单 ----
CREATE TABLE IF NOT EXISTS material_svc.consumable_return (
    id            uuid PRIMARY KEY,
    return_no     text NOT NULL UNIQUE,                           -- MRYYYYMMDD######
    status        text NOT NULL DEFAULT 'SUBMITTED'
                  CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    dept_id       uuid NOT NULL,
    reason        text NOT NULL,                                  -- 退回原因（未使用/备货过多等）
    created_by    text NOT NULL,
    reviewed_by   text,
    reviewed_at   timestamptz,
    review_note   text,
    cancelled_by  text,
    cancelled_at  timestamptz,
    cancel_reason text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_consumable_return_reviewed CHECK (
        (status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR status IN ('SUBMITTED', 'CANCELLED')
    ),
    CONSTRAINT ck_consumable_return_reject_note CHECK (
        (status = 'REJECTED' AND review_note IS NOT NULL)
        OR status <> 'REJECTED'
    ),
    CONSTRAINT ck_consumable_return_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

CREATE INDEX IF NOT EXISTS ix_consumable_return_status
    ON material_svc.consumable_return (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_consumable_return_dept
    ON material_svc.consumable_return (dept_id, created_at DESC);

COMMENT ON TABLE material_svc.consumable_return IS
    '耗材未使用退回单（科室→中心库房；审批通过才扣科室库并回冲中心批次）';

CREATE TABLE IF NOT EXISTS material_svc.consumable_return_item (
    id            uuid PRIMARY KEY,
    return_id     uuid NOT NULL REFERENCES material_svc.consumable_return (id),
    consumable_id uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    batch_id      uuid NOT NULL REFERENCES material_svc.consumable_batch (id),
    quantity      int NOT NULL CHECK (quantity > 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_consumable_return_item_batch UNIQUE (return_id, batch_id)
);

CREATE INDEX IF NOT EXISTS ix_consumable_return_item_return
    ON material_svc.consumable_return_item (return_id, consumable_id);

COMMENT ON TABLE material_svc.consumable_return_item IS
    '退回明细（同一退回单同一批次只能出现一次）';

CREATE OR REPLACE FUNCTION material_svc.reject_locked_consumable_return_item()
RETURNS trigger AS $$
DECLARE
    return_status text;
BEGIN
    SELECT status INTO return_status
      FROM material_svc.consumable_return
     WHERE id = COALESCE(NEW.return_id, OLD.return_id);
    IF return_status IN ('APPROVED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '退回单已关闭（%），明细不可修改', return_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_consumable_return_item_locked
    ON material_svc.consumable_return_item;
CREATE TRIGGER trg_consumable_return_item_locked
    BEFORE INSERT OR UPDATE OR DELETE ON material_svc.consumable_return_item
    FOR EACH ROW EXECUTE FUNCTION material_svc.reject_locked_consumable_return_item();
