-- ============================================================================
-- V14：LIS 入站占位与重放（G67，评审 §3.5/§3.6）
--
-- 背景：旧实现"判重 → 写报告/危急值 → 最后落消息"，并发同 control_id 双投递会
-- 双双通过判重、双写报告草稿与危急值；且 FAILED/IGNORED 被唯一键永久缓存，
-- 全仓没有重放入口，瞬时故障的 ORU 结果永久丢失。
--
-- 口径：消息先落 PROCESSING 占位（唯一索引让并发只有一个请求继续做副作用），
-- 处理完成后回写 PROCESSED/IGNORED/FAILED；FAILED/IGNORED 可经重放入口重新处理，
-- 重放同样先 CAS 回 PROCESSING，避免并发重复处理。
-- ============================================================================

ALTER TABLE tech_svc.lis_inbound_message
    DROP CONSTRAINT IF EXISTS lis_inbound_message_status_check;
ALTER TABLE tech_svc.lis_inbound_message
    ADD CONSTRAINT lis_inbound_message_status_check
    CHECK (status IN ('PROCESSING', 'PROCESSED', 'IGNORED', 'FAILED'));

COMMENT ON COLUMN tech_svc.lis_inbound_message.status IS
    'PROCESSING=已占位处理中；FAILED/IGNORED 可经 /tech/lis/messages/{id}/replay 重放';
