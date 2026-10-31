-- ============================================================================
-- V2：LIS 入站消息留档（N6 第一批：HL7 v2 ORU 结果回写）
--
-- 背景：医技域此前只能人工录入报告，LIS 结果无法自动落库（评审"医技未接 LIS/PACS"）。
-- 本迁移为 ORU^R01 入站建立**消息级留档与幂等键**：
--   * message_control_id 唯一 → 同一消息重复投递只处理一次（LIS 重试是常态）；
--   * raw_message / message_hash → 事后可原样复核，不受报告修订影响；
--   * status PROCESSED / IGNORED / FAILED + error_message → 失败可查、可重放；
--   * application_no / report_id → 消息与申请、报告双向可追。
-- ============================================================================

CREATE TABLE IF NOT EXISTS tech_svc.lis_inbound_message (
    id                 uuid PRIMARY KEY,
    message_control_id text NOT NULL UNIQUE,
    message_type       text NOT NULL,
    sending_app        text,
    application_no     text,
    application_id     uuid,
    report_id          uuid,
    status             text NOT NULL
                       CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED')),
    error_message      text,
    raw_message        text NOT NULL,
    message_hash       text NOT NULL,
    actor              text NOT NULL,
    received_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_lis_inbound_status
    ON tech_svc.lis_inbound_message (status, received_at DESC);
CREATE INDEX IF NOT EXISTS ix_lis_inbound_application
    ON tech_svc.lis_inbound_message (application_no) WHERE application_no IS NOT NULL;
