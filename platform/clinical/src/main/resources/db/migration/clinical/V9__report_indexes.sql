-- ============================================================================
-- V9：运营报表支撑索引（第五轮 R5-M8）
--
-- 背景：GET /clinical/reports/operations 的四类聚合都按 charge_item.created_at
-- 落在期间内过滤，但 V1 只给 charge_item(charge_id) 建了索引，费用明细又是增长最快的表，
-- 报表会退化为全表扫并拖慢同库的挂号/收费事务。
--
-- 说明：只建 created_at 单列索引。查询里还要 join charge 排除 REFUNDED，
-- 该条件对索引选择影响很小（charge 按主键点查），多列索引的写放大不划算。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_charge_item_created
    ON clinical_svc.charge_item (created_at);
