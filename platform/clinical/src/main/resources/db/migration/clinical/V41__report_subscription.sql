-- ============================================================================
-- V41：报表订阅与投递（G19）
--
-- report_subscription：谁订阅哪份报表、走什么渠道（LOG / WEBHOOK）。
-- report_delivery：每个订阅每天一份投递记录，(subscription_id, report_date, report_code)
-- 唯一，定时任务重复执行不会重复推送；失败保留 attempts / last_error 供重试与排障。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.report_subscription (
    id          uuid PRIMARY KEY,
    name        text NOT NULL,
    report_code text NOT NULL DEFAULT 'DAILY_OPS',
    channel     text NOT NULL CHECK (channel IN ('LOG', 'WEBHOOK')),
    target      text,
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_report_subscription_name UNIQUE (name),
    CONSTRAINT ck_report_subscription_target
        CHECK (channel <> 'WEBHOOK' OR (target IS NOT NULL AND btrim(target) <> ''))
);

COMMENT ON TABLE clinical_svc.report_subscription IS
    '报表订阅：LOG 写服务日志，WEBHOOK 需目标域名在允许列表内';
COMMENT ON COLUMN clinical_svc.report_subscription.report_code IS
    '报表编码，当前仅 DAILY_OPS（运营日报）';

CREATE TABLE IF NOT EXISTS clinical_svc.report_delivery (
    id              uuid PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES clinical_svc.report_subscription (id) ON DELETE CASCADE,
    report_code     text NOT NULL,
    report_date     date NOT NULL,
    status          text NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        int NOT NULL DEFAULT 0,
    last_error      text,
    payload         jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    CONSTRAINT ux_report_delivery UNIQUE (subscription_id, report_date, report_code)
);

CREATE INDEX IF NOT EXISTS ix_report_delivery_status
    ON clinical_svc.report_delivery (status, report_date DESC);

COMMENT ON TABLE clinical_svc.report_delivery IS
    '报表投递记录：同一订阅同一天唯一，失败可重试（最多 3 次）';
