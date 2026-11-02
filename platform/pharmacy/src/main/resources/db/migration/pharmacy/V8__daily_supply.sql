-- ============================================================================
-- 药房 V8：长期医嘱按日请领（B2 后续）
--
-- 背景：临床长嘱（order_mode=LONG）的 quantity 是"每日总量"，按日计费；
--       但原 V4 的唯一索引 `source_order_id` 限制"一张医嘱只能有一张处方"，
--       一次转送只能发一天的量，长嘱后续几天无药可发。
--
-- 本迁移把幂等键升级为 (source_order_id, supply_date)：
--   * 同一条医嘱每天最多一张处方（按日请领），重复请求按该组合幂等；
--   * 手工处方 source_order_id 为 NULL，不受约束；
--   * 历史数据 supply_date 用创建日期回填，保证唯一索引可建立。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription
    ADD COLUMN IF NOT EXISTS supply_date date;

UPDATE pharmacy_svc.prescription
   SET supply_date = (created_at AT TIME ZONE 'Asia/Shanghai')::date
 WHERE source_order_id IS NOT NULL AND supply_date IS NULL;

COMMENT ON COLUMN pharmacy_svc.prescription.supply_date IS
    '医嘱计费模式的供药日期（长嘱按日请领）；与 source_order_id 组成幂等键';

DROP INDEX IF EXISTS pharmacy_svc.ux_prescription_source_order;

CREATE UNIQUE INDEX IF NOT EXISTS ux_prescription_source_order_date
    ON pharmacy_svc.prescription (source_order_id, supply_date)
    WHERE source_order_id IS NOT NULL;

-- 停嘱/撤销时按 order_id 批量作废该医嘱后续处方
CREATE INDEX IF NOT EXISTS ix_prescription_source_order
    ON pharmacy_svc.prescription (source_order_id)
    WHERE source_order_id IS NOT NULL;

ALTER TABLE pharmacy_svc.prescription
    DROP CONSTRAINT IF EXISTS ck_prescription_supply_date;

ALTER TABLE pharmacy_svc.prescription
    ADD CONSTRAINT ck_prescription_supply_date
        CHECK (source_order_id IS NULL OR supply_date IS NOT NULL);
