-- ============================================================================
-- V44：报表订阅支持邮件渠道（G23）
--
-- EMAIL 渠道：target 为收件人地址（逗号/分号分隔，最多 10 个），
-- 由服务端通过部署配置的 SMTP 发送 HTML 正文 + CSV 附件。
-- 与 WEBHOOK/IM 一样属于外部投递，失败留原因可重试。
-- ============================================================================

ALTER TABLE clinical_svc.report_subscription
    DROP CONSTRAINT IF EXISTS ck_report_subscription_channel;

ALTER TABLE clinical_svc.report_subscription
    ADD CONSTRAINT ck_report_subscription_channel
    CHECK (channel IN ('LOG', 'WEBHOOK', 'DINGTALK', 'WECOM', 'FEISHU', 'EMAIL'));

-- LOG 之外的所有渠道都要求目标地址非空（EMAIL 是收件人列表）
ALTER TABLE clinical_svc.report_subscription
    DROP CONSTRAINT IF EXISTS ck_report_subscription_target;

ALTER TABLE clinical_svc.report_subscription
    ADD CONSTRAINT ck_report_subscription_target
    CHECK (channel = 'LOG' OR (target IS NOT NULL AND btrim(target) <> ''));

COMMENT ON COLUMN clinical_svc.report_subscription.channel IS
    '渠道：LOG / WEBHOOK（原始 JSON）/ DINGTALK / WECOM / FEISHU（机器人消息）/ EMAIL（SMTP 邮件）';
COMMENT ON COLUMN clinical_svc.report_subscription.target IS
    'WEBHOOK/IM 为目标 URL（域名须命中白名单）；EMAIL 为收件人地址列表（逗号或分号分隔）';
