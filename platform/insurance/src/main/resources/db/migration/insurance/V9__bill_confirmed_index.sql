-- ============================================================================
-- V9：报盘查询索引（第四轮 MF-04）
--
-- listConfirmedBills / countConfirmedBillsAlreadyExported 的过滤条件是
--   status = 'CONFIRMED' AND confirmed_at >= ? AND confirmed_at < ?
--   ORDER BY confirmed_at, bill_no
-- 而 settlement_bill 原有索引只有 (patient_id, created_at)、(visit_id, created_at)、
-- bill_no 唯一键，没有任何含 confirmed_at 的索引 → 每次报盘全表扫描 + 排序，
-- 表随就诊数增长后会拖慢同库其他事务。
--
-- 部分索引：WHERE status = 'CONFIRMED' 与查询的等值条件一致，索引体积最小；
-- 列序 (confirmed_at, bill_no) 同时满足范围过滤与 ORDER BY。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_bill_confirmed_at
    ON insurance_svc.settlement_bill (confirmed_at, bill_no)
    WHERE status = 'CONFIRMED';
