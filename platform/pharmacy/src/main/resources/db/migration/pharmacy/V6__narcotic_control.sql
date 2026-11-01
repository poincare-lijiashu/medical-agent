-- ============================================================================
-- 药房域 V6：麻醉药品"五专"闭环（专权 / 专册 / 空安瓿回收）
--
-- 法规背景（《麻醉药品和精神药品管理条例》《医疗机构麻醉药品、第一类精神药品管理规定》）：
--   * 专用处方 + 处方权：只有取得麻醉药品处方权的医师才能开具；
--   * 专册登记：麻醉药品的每一笔发药都要按"患者—药品—批号—处方医师—发药人—核对人"登记；
--   * 空安瓿回收：注射剂使用后的空安瓿必须交回，药房回收登记后医师才能再次开具同一药品。
--
-- 已有基础（V1）：md_drug.narcotic 标记、dispense.checker 双人核对字段。
-- 本批补齐缺失的三件事：
--   1. narcotic_prescriber：处方权授予名单（医务科授予/撤销，撤销必须留原因）；
--   2. narcotic_register：专册登记（一次发药明细一行，追加式）；
--   3. 空安瓿回收状态机：PENDING_RETURN → RETURNED（回收数量必须等于发药数量）。
--
-- 边界：不做麻醉药品专用柜/双人双锁的库房实物管理、不做处方限量规则（依院内制度配置）。
-- ============================================================================

-- ---- 1. 麻醉药品处方权名单 ----
CREATE TABLE IF NOT EXISTS pharmacy_svc.narcotic_prescriber (
    id            uuid PRIMARY KEY,
    prescriber    text NOT NULL UNIQUE,               -- 医师账号（与 auth 的 username 对齐）
    license_no    text,                                -- 麻醉药品处方权证书编号
    granted_by    text NOT NULL,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    revoked_by    text,
    revoked_at    timestamptz,
    revoke_reason text,
    enabled       boolean NOT NULL DEFAULT true,
    version       bigint NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_narcotic_prescriber_state CHECK (
        (enabled AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR (NOT enabled AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_narcotic_prescriber_enabled
    ON pharmacy_svc.narcotic_prescriber (enabled) WHERE enabled;

COMMENT ON TABLE pharmacy_svc.narcotic_prescriber IS
    '麻醉药品处方权名单：只有 enabled 的医师可开具 narcotic=true 的药品；撤销必须留原因';

-- ---- 2. 麻醉药品专册登记 ----
CREATE TABLE IF NOT EXISTS pharmacy_svc.narcotic_register (
    id                  uuid PRIMARY KEY,
    dispense_item_id    uuid NOT NULL UNIQUE REFERENCES pharmacy_svc.dispense_item (id),
    dispense_id         uuid NOT NULL REFERENCES pharmacy_svc.dispense (id),
    prescription_id     uuid NOT NULL REFERENCES pharmacy_svc.prescription (id),
    prescription_no     text NOT NULL,
    patient_id          uuid NOT NULL,
    patient_no          text NOT NULL,
    drug_id             uuid NOT NULL REFERENCES pharmacy_svc.md_drug (id),
    drug_code           text NOT NULL,
    drug_name           text NOT NULL,
    spec                text NOT NULL,
    quantity            int NOT NULL CHECK (quantity > 0),
    unit                text NOT NULL,
    batch_no            text NOT NULL,
    prescriber          text NOT NULL,
    dispenser           text NOT NULL,
    checker             text NOT NULL,
    dispensed_at        timestamptz NOT NULL,
    ampoule_returned    int NOT NULL DEFAULT 0 CHECK (ampoule_returned >= 0),
    ampoule_returned_at timestamptz,
    ampoule_receiver    text,
    status              text NOT NULL DEFAULT 'PENDING_RETURN'
                        CHECK (status IN ('PENDING_RETURN', 'RETURNED')),
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_narcotic_ampoule CHECK (ampoule_returned <= quantity),
    CONSTRAINT ck_narcotic_return_state CHECK (
        (status = 'PENDING_RETURN' AND ampoule_returned = 0
            AND ampoule_returned_at IS NULL)
        OR (status = 'RETURNED' AND ampoule_returned = quantity
            AND ampoule_returned_at IS NOT NULL AND ampoule_receiver IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_narcotic_register_patient
    ON pharmacy_svc.narcotic_register (patient_id, drug_id, status);
CREATE INDEX IF NOT EXISTS ix_narcotic_register_time
    ON pharmacy_svc.narcotic_register (dispensed_at DESC);

COMMENT ON TABLE pharmacy_svc.narcotic_register IS
    '麻醉药品专册：一次发药明细一行；PENDING_RETURN=空安瓿未回收，回收后置 RETURNED';

-- 专册只允许"回收空安瓿"这一种 UPDATE：数量/药品/批号等事实字段一律不可改
CREATE OR REPLACE FUNCTION pharmacy_svc.guard_narcotic_register() RETURNS trigger AS $$
BEGIN
    IF NEW.dispense_item_id <> OLD.dispense_item_id
        OR NEW.prescription_no <> OLD.prescription_no
        OR NEW.drug_code <> OLD.drug_code
        OR NEW.quantity <> OLD.quantity
        OR NEW.batch_no <> OLD.batch_no
        OR NEW.prescriber <> OLD.prescriber
        OR NEW.dispenser <> OLD.dispenser
        OR NEW.checker <> OLD.checker THEN
        RAISE EXCEPTION '麻醉药品专册登记事实不可修改（只能登记空安瓿回收）'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_narcotic_register ON pharmacy_svc.narcotic_register;
CREATE TRIGGER trg_guard_narcotic_register
    BEFORE UPDATE ON pharmacy_svc.narcotic_register
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.guard_narcotic_register();

-- ---- 3. 示例麻醉药品（上线前由院内药典替换）----
INSERT INTO pharmacy_svc.md_drug
    (id, drug_code, name, trade_name, spec, dosage_form, unit, package_unit, package_size,
     price, insurance_code, insurance_category, antibiotic_level, narcotic)
VALUES
    ('a4444444-4444-4444-4444-444444444444', 'DRUG-MOR-10', '盐酸吗啡注射液', NULL, '10mg:1ml',
     'INJECTION', '支', '支', 1, 5.50, 'XA07BC0101010101010', 'B', 0, true)
ON CONFLICT (drug_code) DO NOTHING;
