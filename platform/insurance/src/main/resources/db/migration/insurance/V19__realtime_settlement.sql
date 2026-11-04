-- ============================================================================
-- V19：医保实时结算交易（G22）
--
-- 在已确认结算清单之上建立"对外实时交易"适配层：
--   * 同一清单 + 同一请求号唯一（幂等），重复提交不会向省局重复出账；
--   * 状态机 PENDING → SETTLED / FAILED，失败保留原因并可手动重试；
--   * 成功记录省局返回的交易号 external_no，报文请求/响应原文留档；
--   * 只登记交易结果，不改写清单金额——清单金额仍以本地测算与确认口径为准。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.realtime_settlement (
    id               uuid PRIMARY KEY,
    bill_id          uuid NOT NULL REFERENCES insurance_svc.settlement_bill (id),
    visit_id         uuid NOT NULL,
    request_id       text NOT NULL,
    biz_type         text NOT NULL DEFAULT 'SETTLE'
                     CHECK (biz_type IN ('PRE_SETTLE', 'SETTLE')),
    platform         text NOT NULL DEFAULT 'PROVINCE',
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SETTLED', 'FAILED')),
    attempts         int NOT NULL DEFAULT 0,
    external_no      text,
    last_error       text,
    request_payload  jsonb,
    response_payload jsonb,
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    settled_at       timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_realtime_settlement UNIQUE (bill_id, request_id)
);

CREATE INDEX IF NOT EXISTS ix_realtime_settlement_status
    ON insurance_svc.realtime_settlement (status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_realtime_settlement_bill
    ON insurance_svc.realtime_settlement (bill_id, created_at DESC);

COMMENT ON TABLE insurance_svc.realtime_settlement IS
    '医保实时结算交易：同一清单同一请求号幂等；失败可重试，成功记录省局交易号';
