-- ============================================================================
-- V25：病区备药与执行对账（B2 后续第二步）
--
-- 背景：长嘱药品已能"按日请领 → 药房按日发药"，但病区侧没有落任何记录：
--   药房说发了、病区说没收到时无法追溯；护理执行与请领/发药也无法对照。
--
-- 本迁移新增两张表：
--   1. daily_dispense_request：临床侧的请领台账（每次请领一行，按 order_id+supply_date 幂等），
--      记录药房产出的处方与失败原因，供病区看板与对账使用；
--   2. ward_med_receipt：病区收药确认（按 dispense_id 幂等），记录实际收到数量与操作人。
--
-- 边界：这里只做"请领/收药/执行"的可追溯对账，不与药房库存做双向同步
-- （药房发药已扣批次库存；病区二级库存模型留待后续批次）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.daily_dispense_request (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    visit_id         uuid NOT NULL REFERENCES clinical_svc.visit (id),
    supply_date      date NOT NULL,
    quantity         integer NOT NULL CHECK (quantity > 0),
    prescription_id  uuid,
    prescription_no  text,
    status           text NOT NULL DEFAULT 'REQUESTED'
                     CHECK (status IN ('REQUESTED', 'FAILED')),
    error_message    text,
    created_by       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_daily_dispense_request_order_date UNIQUE (order_id, supply_date)
);

COMMENT ON TABLE clinical_svc.daily_dispense_request IS
    '长嘱药品按日请领台账：一次请领一行（order_id+supply_date 幂等），记录处方引用或失败原因';

CREATE INDEX IF NOT EXISTS ix_daily_dispense_request_visit
    ON clinical_svc.daily_dispense_request (visit_id, supply_date);

CREATE TABLE IF NOT EXISTS clinical_svc.ward_med_receipt (
    id                uuid PRIMARY KEY,
    request_id        uuid REFERENCES clinical_svc.daily_dispense_request (id),
    order_id          uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    visit_id          uuid NOT NULL REFERENCES clinical_svc.visit (id),
    dispense_id       uuid NOT NULL UNIQUE,
    supply_date       date NOT NULL,
    quantity          integer NOT NULL CHECK (quantity > 0),
    returned_quantity integer NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
    status            text NOT NULL DEFAULT 'RECEIVED'
                      CHECK (status IN ('RECEIVED', 'RETURNED')),
    received_by       text NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ward_receipt_returned CHECK (returned_quantity <= quantity)
);

COMMENT ON TABLE clinical_svc.ward_med_receipt IS
    '病区收药确认：按 dispense_id 幂等，记录实际收到数量（药房发药与病区收货可对账）';

CREATE INDEX IF NOT EXISTS ix_ward_med_receipt_visit
    ON clinical_svc.ward_med_receipt (visit_id, supply_date);

DROP TRIGGER IF EXISTS trg_daily_dispense_request_touch ON clinical_svc.daily_dispense_request;
CREATE TRIGGER trg_daily_dispense_request_touch
    BEFORE UPDATE ON clinical_svc.daily_dispense_request
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.touch_updated_at();

DROP TRIGGER IF EXISTS trg_ward_med_receipt_touch ON clinical_svc.ward_med_receipt;
CREATE TRIGGER trg_ward_med_receipt_touch
    BEFORE UPDATE ON clinical_svc.ward_med_receipt
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.touch_updated_at();
