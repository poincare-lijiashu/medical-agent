-- ============================================================================
-- V43：电子票据推送记录（G21）
--
-- 与 charge_receipt 一对一（同一票据同一平台唯一）：有效票据才可推送；
-- 推送失败保留 attempts/last_error，可手动重试；成功记录平台票据号 external_no。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.receipt_push (
    id          uuid PRIMARY KEY,
    receipt_id  uuid NOT NULL REFERENCES clinical_svc.charge_receipt (id),
    platform    text NOT NULL DEFAULT 'FINANCE',
    status      text NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts    int NOT NULL DEFAULT 0,
    external_no text,
    last_error  text,
    payload     jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    pushed_at   timestamptz,
    CONSTRAINT ux_receipt_push UNIQUE (receipt_id, platform)
);

CREATE INDEX IF NOT EXISTS ix_receipt_push_status
    ON clinical_svc.receipt_push (status, created_at DESC);

COMMENT ON TABLE clinical_svc.receipt_push IS
    '电子票据推送：同一票据同一平台唯一，失败可重试，成功记录平台票据号';
