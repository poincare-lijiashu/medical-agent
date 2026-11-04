-- ============================================================================
-- V19：静配中心（PIVAS）与成品输液批次追溯（G34）
--
-- 业务闭环：
--   已审核处方 → 生成静配单（PENDING）→ 配置（按 FEFO 扣减批次，PREPARED）
--   → 复核（必须另一位药师，生成成品输液标签号 bag_no，CHECKED）。
--
-- 约束：
--   1. 一张处方只能有一张静配单；
--   2. 只有 APPROVED 处方可以进入静配；
--   3. 配置时逐批次原子扣减（quantity >= qty），任一药品不足整单回滚；
--   4. 复核人 ≠ 配置人（库级 CHECK 兜底）；复核驳回必须写原因；
--   5. 批次耗用明细 pivas_item_batch 为追加式，成品输液可按 bag_no 追溯到批次/效期/配置/复核人。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.pivas_order_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.pivas_bag_no_seq START 1;

CREATE TABLE IF NOT EXISTS pharmacy_svc.pivas_order (
    id                uuid PRIMARY KEY,
    order_no          text NOT NULL UNIQUE,
    prescription_id   uuid NOT NULL REFERENCES pharmacy_svc.prescription (id),
    prescription_no   text NOT NULL,
    patient_id        uuid NOT NULL,
    patient_no        text,
    visit_id          uuid,
    visit_no          text,
    dept_id           uuid,
    priority          text NOT NULL DEFAULT 'NORMAL'
                      CHECK (priority IN ('NORMAL', 'URGENT')),
    status            text NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'PREPARED', 'CHECKED', 'REJECTED')),
    solvent           text,
    solvent_volume_ml integer CHECK (solvent_volume_ml IS NULL OR solvent_volume_ml > 0),
    drug_summary      text NOT NULL,
    plan_batch        text,
    planned_at        timestamptz,
    prepared_by       text,
    prepared_at       timestamptz,
    checked_by        text,
    checked_at        timestamptz,
    bag_no            text UNIQUE,
    reject_reason     text,
    created_by        text NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pivas_order_prescription UNIQUE (prescription_id),
    CONSTRAINT ck_pivas_prepared CHECK (
        status NOT IN ('PREPARED', 'CHECKED') OR prepared_by IS NOT NULL),
    CONSTRAINT ck_pivas_checked CHECK (
        status <> 'CHECKED' OR (checked_by IS NOT NULL AND bag_no IS NOT NULL)),
    CONSTRAINT ck_pivas_self_check CHECK (
        checked_by IS NULL OR prepared_by IS NULL
        OR lower(checked_by) <> lower(prepared_by)),
    CONSTRAINT ck_pivas_reject_reason CHECK (
        status <> 'REJECTED' OR reject_reason IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_pivas_order_status
    ON pharmacy_svc.pivas_order (status, created_at);
CREATE INDEX IF NOT EXISTS ix_pivas_order_patient
    ON pharmacy_svc.pivas_order (patient_no, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_pivas_order_dept
    ON pharmacy_svc.pivas_order (dept_id, created_at DESC);

CREATE TABLE IF NOT EXISTS pharmacy_svc.pivas_item (
    id             uuid PRIMARY KEY,
    pivas_order_id uuid NOT NULL REFERENCES pharmacy_svc.pivas_order (id),
    seq            integer NOT NULL,
    drug_id        uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    drug_code      text NOT NULL,
    drug_name      text NOT NULL,
    spec           text,
    quantity       integer NOT NULL CHECK (quantity > 0),
    unit           text,
    dosage         text,
    frequency      text,
    CONSTRAINT ux_pivas_item_seq UNIQUE (pivas_order_id, seq)
);

CREATE INDEX IF NOT EXISTS ix_pivas_item_order
    ON pharmacy_svc.pivas_item (pivas_order_id, seq);

CREATE TABLE IF NOT EXISTS pharmacy_svc.pivas_item_batch (
    id            uuid PRIMARY KEY,
    pivas_item_id uuid NOT NULL REFERENCES pharmacy_svc.pivas_item (id),
    drug_id       uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id      uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    batch_no      text NOT NULL,
    expire_date   date NOT NULL,
    quantity      integer NOT NULL CHECK (quantity > 0),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_pivas_item_batch_item
    ON pharmacy_svc.pivas_item_batch (pivas_item_id);
CREATE INDEX IF NOT EXISTS ix_pivas_item_batch_batch
    ON pharmacy_svc.pivas_item_batch (batch_id);

-- 成品输液批次耗用是原始追溯凭证：只允许 INSERT，禁止改写或删除
DROP TRIGGER IF EXISTS trg_pivas_item_batch_append_only ON pharmacy_svc.pivas_item_batch;
CREATE TRIGGER trg_pivas_item_batch_append_only
    BEFORE UPDATE OR DELETE ON pharmacy_svc.pivas_item_batch
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

COMMENT ON TABLE pharmacy_svc.pivas_order IS
    '静配单：已审核处方 → 配置（扣批次）→ 复核（生成成品标签号）';
COMMENT ON TABLE pharmacy_svc.pivas_item_batch IS
    '成品输液批次耗用（追加式）：按 bag_no 可追溯到具体批次/效期/配置与复核人';
