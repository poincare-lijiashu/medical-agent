-- ============================================================================
-- 临床 V11：医嘱 → 药房转送状态（HIS 主链路：开药嘱即生成待审处方）
--
-- 背景：药品医嘱此前只在临床计费，药房看不到处方，患者必须到药房窗口重新报药名。
-- 本迁移让临床把药品医嘱转给药房生成处方，并记录转送结果：
--   * transfer_status：NONE（非药品医嘱）/ PENDING（已登记，等待转送）/
--                      TRANSFERRED（药房已生成处方）/ FAILED（转送失败，可重试）；
--   * external_id / external_no：药房处方 id 与处方号（对账与追溯用）；
--   * transfer_error：失败原因（截断保存，供医护端提示与运维排查）。
--
-- 语义要点：转送失败不回滚医嘱——医嘱是医疗事实，药房是下游；失败置 FAILED，
-- 由 POST /clinical/visits/{visitId}/orders/{orderId}/transfer-retry 重试（幂等）。
--
-- 同时补药品类收费项目：药房药品编码与临床收费项目编码必须一致，
-- 主链路才能把医嘱药品直接映射成处方药品（此前主数据里没有 DRUG 类项目）。
-- ============================================================================

ALTER TABLE clinical_svc.visit_order
    ADD COLUMN IF NOT EXISTS transfer_status text NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS external_type text,
    ADD COLUMN IF NOT EXISTS external_id uuid,
    ADD COLUMN IF NOT EXISTS external_no text,
    ADD COLUMN IF NOT EXISTS transfer_error text;

ALTER TABLE clinical_svc.visit_order DROP CONSTRAINT IF EXISTS visit_order_transfer_status_check;
ALTER TABLE clinical_svc.visit_order
    ADD CONSTRAINT visit_order_transfer_status_check
    CHECK (transfer_status IN ('NONE', 'PENDING', 'TRANSFERRED', 'FAILED'));

COMMENT ON COLUMN clinical_svc.visit_order.transfer_status IS
    '下游转送状态：NONE=无需转送 / PENDING=待转送 / TRANSFERRED=已转送 / FAILED=失败可重试';
COMMENT ON COLUMN clinical_svc.visit_order.external_id IS '下游单据 id（当前为药房处方 id）';
COMMENT ON COLUMN clinical_svc.visit_order.external_no IS '下游单据号（当前为处方号）';

-- 补偿扫描：只看异常态，索引很小
CREATE INDEX IF NOT EXISTS ix_order_transfer_abnormal
    ON clinical_svc.visit_order (transfer_status, updated_at DESC)
    WHERE transfer_status IN ('PENDING', 'FAILED');

-- ---- 药品类收费项目（编码与药房药品字典一致）----
INSERT INTO clinical_svc.md_charge_item (id, item_code, name, category, unit, price)
VALUES
    ('44444444-4444-4444-4444-444444444401', 'DRUG-AMOX-025', '阿莫西林胶囊', 'DRUG', '盒', 12.50),
    ('44444444-4444-4444-4444-444444444402', 'DRUG-CEF-10', '注射用头孢曲松钠', 'DRUG', '支', 8.80),
    ('44444444-4444-4444-4444-444444444403', 'DRUG-NS-500', '0.9%氯化钠注射液', 'DRUG', '瓶', 3.20)
ON CONFLICT (item_code) DO NOTHING;
