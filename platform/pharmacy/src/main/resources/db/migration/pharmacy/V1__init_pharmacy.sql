-- ============================================================================
-- 药房域（pharmacy_svc）初始表结构：药品字典 / 库存批次 / 处方 / 发药 / 退药
--
-- 设计约束：
--   * 药品字典是药品编码、规格、医保编码与院内价格的唯一真源；处方明细保存快照，避免调价改历史；
--   * 库存按"批次 + 效期"管理，quantity 不允许为负（CHECK），扣减用原子 UPDATE ... WHERE quantity >= ?；
--   * 一张处方只允许发一次药（dispense.prescription_id 唯一），重复发药在数据库层被拒绝；
--   * 退药数量不得超过该明细已发数量（触发器兜底），并回补原批次，不新建批次；
--   * 全部写操作落 pharmacy_change_log（追加式，不提供删除接口）。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS pharmacy_svc;

-- ---- 药品字典 ----
CREATE TABLE IF NOT EXISTS pharmacy_svc.md_drug (
    id                 uuid PRIMARY KEY,
    drug_code          text NOT NULL UNIQUE,                      -- 院内药品编码
    name               text NOT NULL,                              -- 通用名
    trade_name         text,                                       -- 商品名
    spec               text NOT NULL,                              -- 规格（如 0.25g*24片）
    dosage_form        text NOT NULL
                       CHECK (dosage_form IN ('TABLET', 'CAPSULE', 'INJECTION', 'GRANULE', 'LIQUID', 'OINTMENT', 'OTHER')),
    unit               text NOT NULL,                              -- 发药单位（盒/支/瓶）
    package_unit       text,                                       -- 最小计价单位（片/支）
    package_size       int CHECK (package_size IS NULL OR package_size > 0),
    price              numeric(12, 2) NOT NULL CHECK (price >= 0),
    insurance_code     text,                                       -- 国家医保药品编码
    insurance_category text NOT NULL DEFAULT 'NONE'
                       CHECK (insurance_category IN ('A', 'B', 'C', 'NONE')),
    antibiotic_level   int NOT NULL DEFAULT 0
                       CHECK (antibiotic_level BETWEEN 0 AND 3),    -- 0 非抗菌 / 1 非限制 / 2 限制 / 3 特殊使用
    narcotic           boolean NOT NULL DEFAULT false,            -- 麻醉/精神类（需双人核对）
    enabled            boolean NOT NULL DEFAULT true,
    version            bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_md_drug_insurance
    ON pharmacy_svc.md_drug (insurance_code) WHERE insurance_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_md_drug_name ON pharmacy_svc.md_drug (name);

-- ---- 库存批次（一药多批，效期维度）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.drug_batch (
    id           uuid PRIMARY KEY,
    drug_id      uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_no     text NOT NULL,
    expire_date  date NOT NULL,
    quantity     int NOT NULL CHECK (quantity >= 0),
    supplier     text,
    received_at  timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_drug_batch UNIQUE (drug_id, batch_no)
);
-- FEFO：按效期升序取可用批次
CREATE INDEX IF NOT EXISTS ix_drug_batch_fefo
    ON pharmacy_svc.drug_batch (drug_id, expire_date) WHERE quantity > 0;

-- ---- 处方 ----
CREATE TABLE IF NOT EXISTS pharmacy_svc.prescription (
    id            uuid PRIMARY KEY,
    prescription_no text NOT NULL UNIQUE,                         -- RXYYYYMMDD######
    patient_id    uuid NOT NULL,
    patient_no    text NOT NULL,
    visit_id      uuid,
    visit_no      text,
    dept_id       uuid,
    prescriber    text NOT NULL,
    status        text NOT NULL DEFAULT 'PENDING_REVIEW'
                  CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'DISPENSED', 'CANCELLED')),
    reviewer      text,
    reviewed_at   timestamptz,
    reject_reason text,
    total_amount  numeric(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_prescription_review CHECK (
        (status = 'REJECTED' AND reject_reason IS NOT NULL)
        OR status <> 'REJECTED'
    )
);
CREATE INDEX IF NOT EXISTS ix_prescription_status
    ON pharmacy_svc.prescription (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_prescription_patient
    ON pharmacy_svc.prescription (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_prescription_visit
    ON pharmacy_svc.prescription (visit_id) WHERE visit_id IS NOT NULL;

-- ---- 处方明细（价格快照）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.prescription_item (
    id              uuid PRIMARY KEY,
    prescription_id uuid NOT NULL REFERENCES pharmacy_svc.prescription (id) ON DELETE CASCADE,
    drug_id         uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    drug_code       text NOT NULL,
    drug_name       text NOT NULL,
    spec            text NOT NULL,
    quantity        int NOT NULL CHECK (quantity > 0),
    unit            text NOT NULL,
    dosage          text,
    frequency       text,
    days            int CHECK (days IS NULL OR days > 0),
    unit_price      numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount          numeric(12, 2) NOT NULL CHECK (amount >= 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_prescription_item_drug UNIQUE (prescription_id, drug_id)
);
CREATE INDEX IF NOT EXISTS ix_prescription_item_rx
    ON pharmacy_svc.prescription_item (prescription_id);

-- ---- 发药单（一张处方只发一次）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.dispense (
    id              uuid PRIMARY KEY,
    dispense_no     text NOT NULL UNIQUE,                         -- DPYYYYMMDD######
    prescription_id uuid NOT NULL UNIQUE REFERENCES pharmacy_svc.prescription (id),
    operator        text NOT NULL,
    checker         text,                                         -- 麻醉/精神类双人核对
    status          text NOT NULL DEFAULT 'DISPENSED'
                    CHECK (status IN ('DISPENSED', 'PARTIAL_RETURNED', 'RETURNED')),
    total_amount    numeric(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    dispensed_at    timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_dispense_created ON pharmacy_svc.dispense (dispensed_at DESC);

-- ---- 发药明细（按批次扣减，可追溯）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.dispense_item (
    id                   uuid PRIMARY KEY,
    dispense_id          uuid NOT NULL REFERENCES pharmacy_svc.dispense (id) ON DELETE CASCADE,
    prescription_item_id uuid NOT NULL REFERENCES pharmacy_svc.prescription_item (id),
    drug_id              uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    batch_id             uuid NOT NULL REFERENCES pharmacy_svc.drug_batch (id),
    batch_no             text NOT NULL,
    expire_date          date NOT NULL,
    quantity             int NOT NULL CHECK (quantity > 0),
    unit_price           numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount               numeric(12, 2) NOT NULL CHECK (amount >= 0),
    returned_quantity    int NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_dispense_item_return CHECK (returned_quantity <= quantity)
);
CREATE INDEX IF NOT EXISTS ix_dispense_item_dispense
    ON pharmacy_svc.dispense_item (dispense_id);

-- ---- 退药记录（追加式）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.dispense_return (
    id               uuid PRIMARY KEY,
    dispense_id      uuid NOT NULL REFERENCES pharmacy_svc.dispense (id),
    dispense_item_id uuid NOT NULL REFERENCES pharmacy_svc.dispense_item (id),
    quantity         int NOT NULL CHECK (quantity > 0),
    reason           text NOT NULL,
    operator         text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_dispense_return_dispense
    ON pharmacy_svc.dispense_return (dispense_id);

-- ---- 审计（追加式）----
CREATE TABLE IF NOT EXISTS pharmacy_svc.pharmacy_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_pharmacy_change_target
    ON pharmacy_svc.pharmacy_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_pharmacy_change_ts ON pharmacy_svc.pharmacy_change_log (ts DESC);

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.prescription_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS pharmacy_svc.dispense_no_seq START 1;

-- ============================================================================
-- 初始主数据（示例，上线前必须由院内药典/医保目录同步覆盖）
-- 只放"能让流程跑通的最小集合"，不造处方/库存业务数据。
-- ============================================================================
INSERT INTO pharmacy_svc.md_drug
    (id, drug_code, name, trade_name, spec, dosage_form, unit, package_unit, package_size,
     price, insurance_code, insurance_category, antibiotic_level, narcotic)
VALUES
    ('a1111111-1111-1111-1111-111111111111', 'DRUG-AMOX-025', '阿莫西林胶囊', '阿莫仙', '0.25g*24粒',
     'CAPSULE', '盒', '粒', 24, 12.50, 'XA01AA0401010101010', 'A', 1, false),
    ('a2222222-2222-2222-2222-222222222222', 'DRUG-CEF-10', '注射用头孢曲松钠', NULL, '1.0g',
     'INJECTION', '支', '支', 1, 8.80, 'XA01AA0402020202020', 'B', 2, false),
    ('a3333333-3333-3333-3333-333333333333', 'DRUG-NS-500', '0.9%氯化钠注射液', NULL, '500ml',
     'INJECTION', '瓶', '瓶', 1, 3.20, 'XB05BB0101010101010', 'A', 0, false)
ON CONFLICT (drug_code) DO NOTHING;
