-- ============================================================================
-- V10：模拟结果的结构约束（第四轮 SF-13）
--
-- 目前诊断/手术编码列表的"必须是 JSON 数组"与"命中即必须有 group_code/weight"
-- 只由服务层保证；一旦有人手工改库或未来新增写入路径，脏数据会污染对账口径。
-- 这里在数据库层补两道防线：
--   1) diagnoses / procedures 必须是 JSON 数组；
--   2) matched=true 时 group_code 与 weight 必须非空（未命中则允许为空 + 有原因）。
-- ============================================================================

ALTER TABLE insurance_svc.payment_group_simulation
    DROP CONSTRAINT IF EXISTS ck_simulation_json_arrays;

ALTER TABLE insurance_svc.payment_group_simulation
    ADD CONSTRAINT ck_simulation_json_arrays CHECK (
        jsonb_typeof(diagnoses) = 'array' AND jsonb_typeof(procedures) = 'array'
    );

ALTER TABLE insurance_svc.payment_group_simulation
    DROP CONSTRAINT IF EXISTS ck_simulation_matched_consistency;

ALTER TABLE insurance_svc.payment_group_simulation
    ADD CONSTRAINT ck_simulation_matched_consistency CHECK (
        matched = false OR (group_code IS NOT NULL AND weight IS NOT NULL
                            AND payment_standard IS NOT NULL)
    );
