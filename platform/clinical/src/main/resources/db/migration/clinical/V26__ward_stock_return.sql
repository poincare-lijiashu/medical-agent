-- ============================================================================
-- V26：病区二级库存 + 未执行退药（B2 后续第三步）
--
-- 背景：V25 只做了"收药确认"，没有回答两个必须回答的问题：
--   1. 患者拒绝/未执行时，病区收到的药怎么退回？（账实不符，且药房批次库存已被扣减）
--   2. 病区手里现在到底还有多少药？（二级库存没有台账，交接班与盘点无从谈起）
--
-- 本迁移新增：
--   1. ward_med_return：退药记录（按调用方传入的 return_id 幂等），只允许退"未执行"的药；
--   2. ward_stock_txn：病区二级库存流水（append-only），收药记 IN、退药记 RETURN，
--      余额 = IN - RETURN，任何调整都必须新增流水，不允许改历史行。
--
-- 回填：已存在的收药记录补一条 IN 流水，保证上线后余额口径正确。
-- 边界：退药只回冲病区二级库存，不直接回补药房批次库存（药房退药走
--       /pharmacy/dispenses/{id}/returns，两条链路各自幂等，避免重复回补）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.ward_med_return (
    id           uuid PRIMARY KEY,
    receipt_id   uuid NOT NULL REFERENCES clinical_svc.ward_med_receipt (id),
    order_id     uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    visit_id     uuid NOT NULL REFERENCES clinical_svc.visit (id),
    supply_date  date NOT NULL,
    quantity     integer NOT NULL CHECK (quantity > 0),
    reason       text,
    returned_by  text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.ward_med_return IS
    '病区退药记录：只允许退当日护理执行 REFUSED/OMITTED 的药品，按 return_id 幂等';

CREATE INDEX IF NOT EXISTS ix_ward_med_return_receipt
    ON clinical_svc.ward_med_return (receipt_id);
CREATE INDEX IF NOT EXISTS ix_ward_med_return_visit
    ON clinical_svc.ward_med_return (visit_id, supply_date);

CREATE TABLE IF NOT EXISTS clinical_svc.ward_stock_txn (
    id          uuid PRIMARY KEY,
    visit_id    uuid NOT NULL REFERENCES clinical_svc.visit (id),
    order_id    uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    receipt_id  uuid REFERENCES clinical_svc.ward_med_receipt (id),
    return_id   uuid REFERENCES clinical_svc.ward_med_return (id),
    drug_code   text NOT NULL,
    drug_name   text,
    quantity    integer NOT NULL CHECK (quantity > 0),
    txn_type    text NOT NULL CHECK (txn_type IN ('IN', 'RETURN')),
    supply_date date NOT NULL,
    operator    text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.ward_stock_txn IS
    '病区二级库存流水（append-only）：收药 IN、退药 RETURN，余额 = IN 合计 - RETURN 合计';

CREATE INDEX IF NOT EXISTS ix_ward_stock_txn_visit
    ON clinical_svc.ward_stock_txn (visit_id, drug_code);

-- 收药与退药各只能产生一条流水：唯一索引把"重试"挡在数据库层
CREATE UNIQUE INDEX IF NOT EXISTS ux_ward_stock_txn_receipt_in
    ON clinical_svc.ward_stock_txn (receipt_id) WHERE txn_type = 'IN';
CREATE UNIQUE INDEX IF NOT EXISTS ux_ward_stock_txn_return
    ON clinical_svc.ward_stock_txn (return_id) WHERE return_id IS NOT NULL;

-- 回填历史收药流水（上线前已收的药也必须出现在病区库存里）
INSERT INTO clinical_svc.ward_stock_txn
    (id, visit_id, order_id, receipt_id, drug_code, drug_name, quantity,
     txn_type, supply_date, operator)
SELECT md5(r.id::text || ':IN')::uuid, r.visit_id, r.order_id, r.id,
       o.item_code, o.item_name, r.quantity, 'IN', r.supply_date, r.received_by
  FROM clinical_svc.ward_med_receipt r
  JOIN clinical_svc.visit_order o ON o.id = r.order_id
 WHERE NOT EXISTS (
       SELECT 1 FROM clinical_svc.ward_stock_txn t
        WHERE t.receipt_id = r.id AND t.txn_type = 'IN');
