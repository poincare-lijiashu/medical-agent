-- 入站集成消息与幂等（第 258 批）
--
-- 为什么加：院内系统推送（HIS 患者/医嘱、LIS 结果、PACS 状态）最常见的故障是
-- "对端超时后重推同一条消息"。如果平台没有幂等键，就会出现重复计费、重复执行、
-- 重复报告；如果直接丢弃重复消息，又无法向对端证明"已经收过"。
--
-- 规则：
--   * (system_code, external_id) 唯一：external_id 优先取 HL7 MSH-10 消息控制号；
--   * payload_hash 记录报文摘要：同号同文 → 幂等重复；同号不同文 → 409 冲突；
--   * 状态 RECEIVED/PROCESSED/FAILED：先落库再处理，处理失败保留原文可重放；
--   * 不物理删除，保留入站证据与审计链路。
CREATE TABLE clinical_svc.integration_inbound (
    id            uuid PRIMARY KEY,
    system_code   text NOT NULL,
    message_type  text NOT NULL,
    external_id   text NOT NULL,
    business_key  text,
    payload       text NOT NULL,
    payload_hash  text NOT NULL,
    status        text NOT NULL DEFAULT 'RECEIVED'
                  CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    error         text,
    received_by   text NOT NULL,
    processed_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

COMMENT ON TABLE clinical_svc.integration_inbound IS
    '入站集成消息：唯一幂等键 + 报文摘要，重复推送只处理一次';

CREATE UNIQUE INDEX ux_integration_inbound_external
    ON clinical_svc.integration_inbound (system_code, external_id);

CREATE INDEX ix_integration_inbound_status
    ON clinical_svc.integration_inbound (status, created_at DESC);

CREATE INDEX ix_integration_inbound_business
    ON clinical_svc.integration_inbound (business_key);
