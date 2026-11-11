-- 集成消息台账（第 257 批）
--
-- 为什么加：院内系统集成（HIS/LIS/PACS/EMR/医保/CA）真正的风险不是"接口写不出来"，
-- 而是"网络抖一下、对端重启一次，数据就悄悄丢了"，且事后无法回答：
--   哪条消息没发出去？重试了几次？最后一次错在哪？对端回执号是多少？
--
-- 本表是最小可用的出站台账（outbox）：
--   * 业务只负责 enqueue，发送与重试由后台 worker 统一完成；
--   * SENDING 是"已认领"，配合 locked_at 支持多实例与进程崩溃后的回收；
--   * ACKED 记录对端回执号，FAILED/DEAD 保留最后一次错误与下次重试时间；
--   * 只做追加与状态流转，不物理删除，便于对账与审计。
CREATE TABLE clinical_svc.integration_message (
    id             uuid PRIMARY KEY,
    system_code    text NOT NULL,
    message_type   text NOT NULL,
    business_key   text,
    payload        text NOT NULL,
    status         text NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'SENDING', 'ACKED', 'FAILED', 'DEAD')),
    attempts       integer NOT NULL DEFAULT 0,
    next_retry_at  timestamptz NOT NULL DEFAULT now(),
    last_error     text,
    external_id    text,
    created_by     text NOT NULL,
    locked_at      timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

COMMENT ON TABLE clinical_svc.integration_message IS
    '出站集成消息台账：PENDING→SENDING→ACKED/FAILED/DEAD，支持重试与对账';

-- 认领待发消息：PENDING 到期，或 SENDING 超时（进程崩溃后回收）
CREATE INDEX ix_integration_due
    ON clinical_svc.integration_message (status, next_retry_at);

-- 按业务号对账（患者号/就诊号/结算号）
CREATE INDEX ix_integration_business_key
    ON clinical_svc.integration_message (business_key);

CREATE INDEX ix_integration_system
    ON clinical_svc.integration_message (system_code, created_at DESC);
