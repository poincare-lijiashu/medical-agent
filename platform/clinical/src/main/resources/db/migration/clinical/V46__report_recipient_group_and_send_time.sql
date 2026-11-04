-- ============================================================================
-- V46：报表订阅收件人分组与发送时段（G25）
--
-- 收件人分组：按科室/岗位维护一组收件邮箱，订阅引用分组后，
-- 新增/停用成员在下一次投递自动生效，不必逐条改订阅。
--
-- 发送时段：每份订阅独立的 HH:mm，定时任务每分钟检查一次，
-- 到点（或应用晚启动时补发当天已过时点）为该订阅生成前一日投递占位；
-- (订阅, 日期, 报表) 唯一保证补发/重启不会重复投递。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.report_recipient_group (
    id          uuid PRIMARY KEY,
    code        text NOT NULL,
    name        text NOT NULL,
    description text,
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_report_recipient_group_code UNIQUE (code)
);

COMMENT ON TABLE clinical_svc.report_recipient_group IS
    '报表收件人分组：按科室/岗位维护一组邮箱，订阅引用后成员变更自动生效';

CREATE TABLE IF NOT EXISTS clinical_svc.report_recipient_group_member (
    id           uuid PRIMARY KEY,
    group_id     uuid NOT NULL REFERENCES clinical_svc.report_recipient_group (id) ON DELETE CASCADE,
    email        text NOT NULL,
    display_name text,
    enabled      boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_report_recipient_group_member UNIQUE (group_id, email)
);

CREATE INDEX IF NOT EXISTS ix_report_recipient_group_member
    ON clinical_svc.report_recipient_group_member (group_id, enabled);

ALTER TABLE clinical_svc.report_subscription
    ADD COLUMN IF NOT EXISTS send_time time NOT NULL DEFAULT '00:30',
    ADD COLUMN IF NOT EXISTS recipient_group_id uuid
        REFERENCES clinical_svc.report_recipient_group (id);

ALTER TABLE clinical_svc.report_subscription
    DROP CONSTRAINT IF EXISTS ck_report_subscription_target;

-- LOG 不需要目标；EMAIL 允许"直接收件人"或"收件人分组"二选一/并用；其余渠道必须有 URL。
ALTER TABLE clinical_svc.report_subscription
    ADD CONSTRAINT ck_report_subscription_target
    CHECK (channel = 'LOG'
           OR (target IS NOT NULL AND btrim(target) <> '')
           OR (channel = 'EMAIL' AND recipient_group_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS ix_report_subscription_due
    ON clinical_svc.report_subscription (send_time)
    WHERE enabled;

COMMENT ON COLUMN clinical_svc.report_subscription.send_time IS
    '每日发送时段 HH:mm；应用晚启动时会对当天已过时点的订阅补发前一日报表';
COMMENT ON COLUMN clinical_svc.report_subscription.recipient_group_id IS
    'EMAIL 收件人分组：与 target 直接收件人合并去重后投递';
