-- ============================================================================
-- V42：报表订阅支持企业 IM 渠道（G20）
--
-- 新增 DINGTALK / WECOM / FEISHU 三种机器人 Webhook 渠道；
-- 三类渠道与 WEBHOOK 一样，目标域名必须命中 allowed-hosts 白名单。
-- ============================================================================

ALTER TABLE clinical_svc.report_subscription
    DROP CONSTRAINT IF EXISTS report_subscription_channel_check;

ALTER TABLE clinical_svc.report_subscription
    ADD CONSTRAINT ck_report_subscription_channel
    CHECK (channel IN ('LOG', 'WEBHOOK', 'DINGTALK', 'WECOM', 'FEISHU'));

COMMENT ON COLUMN clinical_svc.report_subscription.channel IS
    '渠道：LOG / WEBHOOK（原始 JSON）/ DINGTALK / WECOM / FEISHU（机器人消息）';
