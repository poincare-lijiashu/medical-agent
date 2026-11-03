-- ============================================================================
-- 药房 V12：药品采购 — 供应商 — 采购单 — 到货验收（G2）
--
-- 设计要点（与院内 GSP 药事管理口径一致）：
--   * 供应商是采购的前置条件：必须 ACTIVE 且《药品经营许可证》在有效期内，
--     否则不允许新开采购单（已开单据不受影响，货已备好不能因证照到期拒收）。
--   * 采购单状态机：DRAFT → SUBMITTED → PARTIAL / RECEIVED；DRAFT、SUBMITTED 可取消。
--     PARTIAL / RECEIVED 不走取消（货已入库，只能走退货/报损，避免用"取消"抹掉库存痕迹）。
--   * 验收单一次提交即入库：验收明细直接落到 drug_batch，并对采购明细做
--     received_quantity 的数据库级守卫（received_quantity + qty <= quantity），
--     超量验收在应用层与数据库层都会被拒绝，不会出现"收得比买的多"。
--   * 幂等：同一采购单 + 同一 request_id 只产生一张验收单（唯一约束），
--     客户端重试/双击不会重复入库。
--   * 批次唯一键沿用 drug_batch(drug_id, batch_no)：同批号二次到货按"补录数量"累加，
--     不新建批次，保证库存与效期口径唯一。
-- ============================================================================

-- ---- 供应商 ----
CREATE TABLE IF NOT EXISTS md_supplier (
    id                  uuid PRIMARY KEY,
    code                text NOT NULL UNIQUE,                     -- 供应商编码（院内）
    name                text NOT NULL,
    contact             text,
    phone               text,
    license_no          text NOT NULL,                            -- 药品经营许可证号
    license_expire_date date NOT NULL,                            -- 证照有效期（过期不可新开采购单）
    status              text NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    remark              text,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_md_supplier_status ON md_supplier (status, name);

-- ---- 采购单 ----
CREATE TABLE IF NOT EXISTS purchase_order (
    id             uuid PRIMARY KEY,
    order_no       text NOT NULL UNIQUE,                          -- POYYYYMMDD######
    supplier_id    uuid NOT NULL REFERENCES md_supplier (id),
    status         text NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIAL', 'RECEIVED', 'CANCELLED')),
    expected_date  date,                                          -- 期望到货日期
    total_amount   numeric(14, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    remark         text,
    created_by     text NOT NULL,
    submitted_by   text,
    submitted_at   timestamptz,
    cancel_reason  text,
    cancelled_by   text,
    cancelled_at   timestamptz,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_purchase_order_cancel
        CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_purchase_order_status ON purchase_order (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_purchase_order_supplier ON purchase_order (supplier_id, created_at DESC);

-- ---- 采购明细（药品价格快照，后续调价不改历史）----
CREATE TABLE IF NOT EXISTS purchase_order_item (
    id                uuid PRIMARY KEY,
    purchase_order_id uuid NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    drug_id           uuid NOT NULL REFERENCES md_drug (id),
    drug_code         text NOT NULL,
    drug_name         text NOT NULL,
    spec              text NOT NULL,
    unit              text NOT NULL,
    quantity          int NOT NULL CHECK (quantity > 0),
    received_quantity int NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_price        numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount            numeric(14, 2) NOT NULL CHECK (amount >= 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_purchase_order_item_drug UNIQUE (purchase_order_id, drug_id),
    CONSTRAINT ck_purchase_order_item_received CHECK (received_quantity <= quantity)
);
CREATE INDEX IF NOT EXISTS ix_purchase_order_item_po
    ON purchase_order_item (purchase_order_id);

-- ---- 到货验收单 ----
CREATE TABLE IF NOT EXISTS goods_receipt (
    id                uuid PRIMARY KEY,
    receipt_no        text NOT NULL UNIQUE,                       -- GRYYYYMMDD######
    purchase_order_id uuid NOT NULL REFERENCES purchase_order (id),
    supplier_id       uuid NOT NULL REFERENCES md_supplier (id),
    request_id        text,                                       -- 幂等键（同一采购单内唯一）
    delivery_no       text,                                       -- 配送单 / 物流单号
    courier           text,                                       -- 承运方 / 配送人
    delivered_at      timestamptz,                                -- 供应商实际送达时间
    received_by       text NOT NULL,                              -- 验收人
    remark            text,
    total_amount      numeric(14, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- request_id 为 NULL 时 PostgreSQL 视作互不相等，允许手工多次验收
    CONSTRAINT ux_goods_receipt_idempotent UNIQUE (purchase_order_id, request_id)
);
CREATE INDEX IF NOT EXISTS ix_goods_receipt_po
    ON goods_receipt (purchase_order_id, created_at DESC);

-- ---- 验收明细（一行对应一个批次的入库）----
CREATE TABLE IF NOT EXISTS goods_receipt_item (
    id          uuid PRIMARY KEY,
    receipt_id  uuid NOT NULL REFERENCES goods_receipt (id) ON DELETE CASCADE,
    po_item_id  uuid NOT NULL REFERENCES purchase_order_item (id),
    drug_id     uuid NOT NULL REFERENCES md_drug (id),
    batch_id    uuid NOT NULL REFERENCES drug_batch (id),
    batch_no    text NOT NULL,
    expire_date date NOT NULL,
    quantity    int NOT NULL CHECK (quantity > 0),
    unit_price  numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount      numeric(14, 2) NOT NULL CHECK (amount >= 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_goods_receipt_item_receipt
    ON goods_receipt_item (receipt_id);
CREATE INDEX IF NOT EXISTS ix_goods_receipt_item_po_item
    ON goods_receipt_item (po_item_id);

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS purchase_order_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS goods_receipt_no_seq START 1;
