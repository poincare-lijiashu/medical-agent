-- ============================================================================
-- 物资耗材域（material_svc）初始结构（G3）
--
-- 为什么耗材不能复用药品表：
--   * 药品有处方/审方/适应症，耗材走"科室请领/手术备货"；两者的状态机与权限点不同；
--   * 高值/植入耗材的合规核心是"批次 → 患者 → 手术"的正向追溯与"批次 → 患者"的逆向召回，
--     药品发药明细无法表达手术维度；
--   * 一般耗材可以没有效期，植入类必须有，这需要独立的字段语义。
--
-- 设计约束：
--   * 批次库存 quantity 不允许为负（CHECK），扣减用原子 UPDATE ... WHERE quantity >= ?；
--   * 出库单以 (dept_id, request_id) 做幂等，双击/重试不会重复扣库存；
--   * 出库明细记录 traced_quantity，并保证 traced_quantity <= quantity：
--     同一批次发到手术间 10 件，最多只能登记 10 件用于患者，无法"超用"；
--   * 追溯表记录耗材编码/名称快照，字典改名不改历史召回口径；
--   * 变更日志与出库/追溯记录均为追加式，库级触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS material_svc;

-- ---- 耗材目录 ----
CREATE TABLE IF NOT EXISTS material_svc.md_consumable (
    id               uuid PRIMARY KEY,
    code             text NOT NULL UNIQUE,                        -- 院内耗材编码
    name             text NOT NULL,
    spec             text NOT NULL,
    unit             text NOT NULL,
    category         text NOT NULL DEFAULT 'GENERAL'
                     CHECK (category IN ('GENERAL', 'HIGH_VALUE', 'IMPLANT')),
    price            numeric(12, 2) NOT NULL CHECK (price >= 0),
    manufacturer     text,
    registration_no  text,                                        -- 医疗器械注册证号（高值/植入必填）
    enabled          boolean NOT NULL DEFAULT true,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_md_consumable_name ON material_svc.md_consumable (name);
CREATE INDEX IF NOT EXISTS ix_md_consumable_category
    ON material_svc.md_consumable (category, enabled);

-- ---- 批次库存（效期维度；一般耗材可为 NULL）----
CREATE TABLE IF NOT EXISTS material_svc.consumable_batch (
    id            uuid PRIMARY KEY,
    consumable_id uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    batch_no      text NOT NULL,
    expire_date   date,
    quantity      int NOT NULL CHECK (quantity >= 0),
    supplier      text,
    unit_price    numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    version       bigint NOT NULL DEFAULT 0,
    received_at   timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_consumable_batch UNIQUE (consumable_id, batch_no)
);
-- FEFO：效期升序（无有效期排最后），仅取有余量的批次
CREATE INDEX IF NOT EXISTS ix_consumable_batch_fefo
    ON material_svc.consumable_batch (consumable_id, expire_date NULLS LAST, batch_no)
    WHERE quantity > 0;

-- ---- 出库单（库房 → 科室 / 手术间 / 门诊）----
CREATE TABLE IF NOT EXISTS material_svc.consumable_issue (
    id           uuid PRIMARY KEY,
    issue_no     text NOT NULL UNIQUE,                            -- MIYYYYMMDD######
    dept_id      uuid NOT NULL,
    dept_name    text,
    purpose      text NOT NULL DEFAULT 'DEPT_STOCK'
                 CHECK (purpose IN ('DEPT_STOCK', 'SURGERY', 'CLINIC')),
    request_id   text NOT NULL,                                   -- 幂等键（与 dept_id 组合唯一）
    operator     text NOT NULL,
    remark       text,
    total_amount numeric(14, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_consumable_issue_idempotent UNIQUE (dept_id, request_id)
);
CREATE INDEX IF NOT EXISTS ix_consumable_issue_dept
    ON material_svc.consumable_issue (dept_id, created_at DESC);

-- ---- 出库明细（一行 = 一个批次；请求行可能拆成多批）----
CREATE TABLE IF NOT EXISTS material_svc.consumable_issue_item (
    id              uuid PRIMARY KEY,
    issue_id        uuid NOT NULL REFERENCES material_svc.consumable_issue (id) ON DELETE CASCADE,
    consumable_id   uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    batch_id        uuid NOT NULL REFERENCES material_svc.consumable_batch (id),
    batch_no        text NOT NULL,
    expire_date     date,
    quantity        int NOT NULL CHECK (quantity > 0),
    traced_quantity int NOT NULL DEFAULT 0 CHECK (traced_quantity >= 0),
    unit_price      numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount          numeric(14, 2) NOT NULL CHECK (amount >= 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_consumable_issue_item_traced CHECK (traced_quantity <= quantity)
);
CREATE INDEX IF NOT EXISTS ix_consumable_issue_item_issue
    ON material_svc.consumable_issue_item (issue_id);
CREATE INDEX IF NOT EXISTS ix_consumable_issue_item_consumable
    ON material_svc.consumable_issue_item (consumable_id, created_at DESC);

-- ---- 使用追溯（高值/植入耗材必须登记患者；手术耗材同时登记手术）----
CREATE TABLE IF NOT EXISTS material_svc.consumable_trace (
    id              uuid PRIMARY KEY,
    issue_item_id   uuid NOT NULL REFERENCES material_svc.consumable_issue_item (id),
    consumable_id   uuid NOT NULL REFERENCES material_svc.md_consumable (id),
    consumable_code text NOT NULL,                                -- 快照：字典改名不改历史召回
    consumable_name text NOT NULL,
    batch_no        text NOT NULL,
    expire_date     date,
    quantity        int NOT NULL CHECK (quantity > 0),
    patient_id      uuid NOT NULL,
    patient_no      text NOT NULL,
    visit_id        uuid,
    surgery_id      uuid,
    operator        text NOT NULL,
    used_at         timestamptz NOT NULL DEFAULT now(),
    remark          text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_consumable_trace_batch
    ON material_svc.consumable_trace (batch_no, used_at DESC);
CREATE INDEX IF NOT EXISTS ix_consumable_trace_patient
    ON material_svc.consumable_trace (patient_id, used_at DESC);
CREATE INDEX IF NOT EXISTS ix_consumable_trace_consumable
    ON material_svc.consumable_trace (consumable_id, used_at DESC);
CREATE INDEX IF NOT EXISTS ix_consumable_trace_surgery
    ON material_svc.consumable_trace (surgery_id) WHERE surgery_id IS NOT NULL;

-- ---- 变更日志（追加式）----
CREATE TABLE IF NOT EXISTS material_svc.material_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_material_change_target
    ON material_svc.material_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_material_change_ts ON material_svc.material_change_log (ts DESC);

CREATE OR REPLACE FUNCTION material_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_material_change_log_append_only ON material_svc.material_change_log;
CREATE TRIGGER trg_material_change_log_append_only
    BEFORE UPDATE OR DELETE ON material_svc.material_change_log
    FOR EACH ROW EXECUTE FUNCTION material_svc.reject_audit_mutation();

-- 出库单与追溯记录是召回证据链，禁止删除（错误出库走退回/冲销，不抹痕迹）
DROP TRIGGER IF EXISTS trg_consumable_issue_no_delete ON material_svc.consumable_issue;
CREATE TRIGGER trg_consumable_issue_no_delete
    BEFORE DELETE ON material_svc.consumable_issue
    FOR EACH ROW EXECUTE FUNCTION material_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_consumable_trace_no_mutation ON material_svc.consumable_trace;
CREATE TRIGGER trg_consumable_trace_no_mutation
    BEFORE UPDATE OR DELETE ON material_svc.consumable_trace
    FOR EACH ROW EXECUTE FUNCTION material_svc.reject_audit_mutation();

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS material_svc.consumable_issue_no_seq START 1;

-- ============================================================================
-- 初始主数据（示例；上线前由物资科按中标目录导入）
-- ============================================================================
INSERT INTO material_svc.md_consumable
    (id, code, name, spec, unit, category, price, manufacturer, registration_no)
VALUES
    ('b1111111-1111-1111-1111-111111111111', 'CONS-SYR-5ML', '一次性使用无菌注射器', '5ml 带针', '支',
     'GENERAL', 0.80, '某医疗器械有限公司', NULL),
    ('b2222222-2222-2222-2222-222222222222', 'CONS-STENT-001', '冠状动脉支架系统', '3.0×18mm', '套',
     'IMPLANT', 4800.00, '某介入器械有限公司', '国械注准20263130001')
ON CONFLICT (code) DO NOTHING;
