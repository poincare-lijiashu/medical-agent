-- ============================================================================
-- V45：电子票据厂商报文适配（G24）
--
-- 在 G21 推送适配层上补齐厂商报文要素：
--   * vendor：票据平台/厂商编码（默认 FINANCE，可由部署配置覆盖）；
--   * message_type：ISSUE 开具 / RED_FLUSH 红冲；
--   * original_push_id：红冲指向原开具推送；
--   * template_version：报文模板版本，便于厂商升级后追溯。
--
-- 唯一键从（票据, 平台）改为（票据, 平台, 报文类型）：
-- 同一张票先开具、作废后红冲，是两条独立的对外报文。
-- ============================================================================

ALTER TABLE clinical_svc.receipt_push
    ADD COLUMN IF NOT EXISTS vendor text NOT NULL DEFAULT 'FINANCE',
    ADD COLUMN IF NOT EXISTS message_type text NOT NULL DEFAULT 'ISSUE',
    ADD COLUMN IF NOT EXISTS original_push_id uuid,
    ADD COLUMN IF NOT EXISTS template_version text NOT NULL DEFAULT '1.0';

ALTER TABLE clinical_svc.receipt_push
    DROP CONSTRAINT IF EXISTS ux_receipt_push;

ALTER TABLE clinical_svc.receipt_push
    ADD CONSTRAINT ux_receipt_push UNIQUE (receipt_id, platform, message_type);

ALTER TABLE clinical_svc.receipt_push
    DROP CONSTRAINT IF EXISTS ck_receipt_push_message_type;

ALTER TABLE clinical_svc.receipt_push
    ADD CONSTRAINT ck_receipt_push_message_type
    CHECK (message_type IN ('ISSUE', 'RED_FLUSH'));

ALTER TABLE clinical_svc.receipt_push
    DROP CONSTRAINT IF EXISTS fk_receipt_push_original;

ALTER TABLE clinical_svc.receipt_push
    ADD CONSTRAINT fk_receipt_push_original
    FOREIGN KEY (original_push_id) REFERENCES clinical_svc.receipt_push (id);

CREATE INDEX IF NOT EXISTS ix_receipt_push_message_type
    ON clinical_svc.receipt_push (message_type, status, created_at DESC);

COMMENT ON COLUMN clinical_svc.receipt_push.vendor IS
    '票据平台/厂商编码，报文模板按厂商区分';
COMMENT ON COLUMN clinical_svc.receipt_push.message_type IS
    '报文类型：ISSUE 开具 / RED_FLUSH 红冲';
COMMENT ON COLUMN clinical_svc.receipt_push.original_push_id IS
    '红冲报文指向的原开具推送记录';
