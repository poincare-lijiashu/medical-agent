-- ============================================================================
-- 临床核心 V4：住院医嘱模式（长嘱/临嘱）+ 按日计费 + 预交金
--
-- 关键设计：
--   * 长嘱（LONG）按"天"计费，同一条医嘱同一天只能计一次费——幂等由 charge_item.source_key
--     唯一索引保证（DAILY:{orderId}:{yyyy-MM-dd}）；
--   * 原 charge_item.order_id 唯一约束无法表达"同一医嘱多天计费"，本迁移改为
--     source_key 唯一（临嘱/一次性收费写 ORDER:{orderId}），历史数据自动回填；
--   * 预交金独立成表：正数=存入，负数=退还；余额 = sum(amount)，退还不能超过余额。
-- ============================================================================

-- ---- 医嘱模式与有效期 ----
ALTER TABLE clinical_svc.visit_order
    ADD COLUMN IF NOT EXISTS order_mode text NOT NULL DEFAULT 'TEMP',
    ADD COLUMN IF NOT EXISTS start_date date,
    ADD COLUMN IF NOT EXISTS stop_date  date,
    ADD COLUMN IF NOT EXISTS stopped_by text,
    ADD COLUMN IF NOT EXISTS stop_reason text;

ALTER TABLE clinical_svc.visit_order DROP CONSTRAINT IF EXISTS visit_order_order_mode_check;
ALTER TABLE clinical_svc.visit_order
    ADD CONSTRAINT visit_order_order_mode_check CHECK (order_mode IN ('TEMP', 'LONG'));

UPDATE clinical_svc.visit_order SET start_date = created_at::date WHERE start_date IS NULL;

CREATE INDEX IF NOT EXISTS ix_order_long_active
    ON clinical_svc.visit_order (visit_id, order_mode, status, start_date, stop_date);

-- ---- 费用明细幂等键：从 order_id 唯一改为 source_key 唯一 ----
ALTER TABLE clinical_svc.charge_item DROP CONSTRAINT IF EXISTS charge_item_order_id_key;
ALTER TABLE clinical_svc.charge_item ADD COLUMN IF NOT EXISTS source_key text;

UPDATE clinical_svc.charge_item
   SET source_key = CASE WHEN order_id IS NULL THEN 'REG:' || id::text
                         ELSE 'ORDER:' || order_id::text END
 WHERE source_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_charge_item_source
    ON clinical_svc.charge_item (source_key);

COMMENT ON COLUMN clinical_svc.charge_item.source_key IS
    '计费幂等键：ORDER:{orderId} 一次性 / DAILY:{orderId}:{date} 按日 / REG:{uuid} 挂号费';

-- ---- 预交金 ----
CREATE TABLE IF NOT EXISTS clinical_svc.deposit (
    id           uuid PRIMARY KEY,
    admission_id uuid NOT NULL REFERENCES clinical_svc.admission (id),
    amount       numeric(12, 2) NOT NULL CHECK (amount <> 0),
    kind         text NOT NULL CHECK (kind IN ('DEPOSIT', 'REFUND')),
    method       text NOT NULL,
    operator     text NOT NULL,
    remark       text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_deposit_admission ON clinical_svc.deposit (admission_id, created_at);
COMMENT ON TABLE clinical_svc.deposit IS '住院预交金（DEPOSIT 正数 / REFUND 负数；余额 = sum(amount)）';
