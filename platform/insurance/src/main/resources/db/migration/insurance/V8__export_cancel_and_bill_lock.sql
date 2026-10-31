-- ============================================================================
-- V8：报盘批次撤销 + 清单报盘互斥（第四轮 MF-01 / SF-01）
--
-- 背景（第四轮评测）：
--   * 同一 CONFIRMED 清单可被重复纳入多个批次，省局侧将重复结算（资金红线）；
--   * 已纳入批次（GENERATED/ACCEPTED）的清单仍可被作废，院内台账与省局台账劈叉；
--   * GENERATED 批次一旦生成就无法纠正，会把清单永久锁死。
--
-- 处理：
--   1) status 增加 CANCELLED（仅 GENERATED 可撤销），撤销后其清单可在新批次重报；
--   2) 记录撤销原因/操作人/时间，保证可追溯；
--   3) 清单与批次的有效关联由服务层"排除已属 GENERATED/ACCEPTED 批次"的查询保证，
--      撤销批次（CANCELLED）与退回批次（REJECTED）不阻止重报。
--
-- 注意：CHECK 约束在 PG 中只能新增/替换，这里先删后建（约束名固定，重跑安全）。
-- ============================================================================

ALTER TABLE insurance_svc.settlement_export_batch
    ADD COLUMN IF NOT EXISTS cancelled_reason text,
    ADD COLUMN IF NOT EXISTS cancelled_by   text,
    ADD COLUMN IF NOT EXISTS cancelled_at   timestamptz;

ALTER TABLE insurance_svc.settlement_export_batch
    DROP CONSTRAINT IF EXISTS settlement_export_batch_status_check;

ALTER TABLE insurance_svc.settlement_export_batch
    DROP CONSTRAINT IF EXISTS ck_export_status;

ALTER TABLE insurance_svc.settlement_export_batch
    ADD CONSTRAINT ck_export_status
        CHECK (status IN ('GENERATED', 'ACCEPTED', 'REJECTED', 'CANCELLED'));

ALTER TABLE insurance_svc.settlement_export_batch
    DROP CONSTRAINT IF EXISTS ck_export_receipt;

ALTER TABLE insurance_svc.settlement_export_batch
    ADD CONSTRAINT ck_export_receipt CHECK (
        (status = 'GENERATED' AND receipt_no IS NULL AND receipt_at IS NULL
            AND cancelled_at IS NULL)
        OR (status IN ('ACCEPTED', 'REJECTED')
            AND receipt_no IS NOT NULL AND receipt_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED'
            AND cancelled_at IS NOT NULL AND receipt_no IS NULL AND receipt_at IS NULL)
    );

-- 清单 → 批次的反查（作废前判断"是否已报盘"、生成时报盘互斥）
CREATE INDEX IF NOT EXISTS ix_export_item_bill_batch
    ON insurance_svc.settlement_export_item (bill_id, batch_id);
