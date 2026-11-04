-- ============================================================================
-- V17：效期自动报废草稿（G28）
--
-- 每日定时扫描已过期且仍有库存的批次，按药品聚合生成 DRAFT 报损单；
-- 草稿仍需人工提交并由另一位药剂师审批，绝不自动扣减库存。
--
--   * status 增加 DRAFT：DRAFT → SUBMITTED → APPROVED / REJECTED；
--   * source 区分人工（MANUAL）与系统到期（AUTO_EXPIRY），便于审计与排查；
--   * 同一批次已存在于 DRAFT/SUBMITTED/APPROVED 报损单时不会重复生成。
-- ============================================================================

ALTER TABLE pharmacy_svc.stock_loss
    DROP CONSTRAINT IF EXISTS stock_loss_status_check;

ALTER TABLE pharmacy_svc.stock_loss
    ADD CONSTRAINT ck_stock_loss_status
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'));

ALTER TABLE pharmacy_svc.stock_loss
    ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'MANUAL';

ALTER TABLE pharmacy_svc.stock_loss
    DROP CONSTRAINT IF EXISTS ck_stock_loss_source;

ALTER TABLE pharmacy_svc.stock_loss
    ADD CONSTRAINT ck_stock_loss_source
    CHECK (source IN ('MANUAL', 'AUTO_EXPIRY'));

ALTER TABLE pharmacy_svc.stock_loss
    DROP CONSTRAINT IF EXISTS ck_stock_loss_reviewed;

ALTER TABLE pharmacy_svc.stock_loss
    ADD CONSTRAINT ck_stock_loss_reviewed
    CHECK (
        (status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR status IN ('DRAFT', 'SUBMITTED')
    );

CREATE INDEX IF NOT EXISTS ix_stock_loss_source
    ON pharmacy_svc.stock_loss (source, status, created_at DESC);

COMMENT ON COLUMN pharmacy_svc.stock_loss.source IS
    '来源：MANUAL 人工 / AUTO_EXPIRY 效期自动草稿（仍需人工提交与审批）';
