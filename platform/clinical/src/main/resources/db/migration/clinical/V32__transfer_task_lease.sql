-- ============================================================================
-- 临床域 V32：转送补偿任务的多副本租约（P2-7）
--
-- 背景：
--   * V14 的 worker 每轮直接 `SELECT ... WHERE status='PENDING' LIMIT n`，没有抢占语义；
--   * 多副本同时扫描会拿到同一批任务，N 个副本各调一次下游、各 failTransferTask 一次，
--     attempts 被放大 N 倍 → 约 3 轮就被误判 FAILED，运维看到的是"下游一直不可用"，
--     实际只是副本互相踩。
--
-- 设计：
--   1. 增加 locked_until / locked_by（租约），抢占用 `FOR UPDATE SKIP LOCKED`；
--   2. 实例崩溃后租约到期自动回到可抢占状态，不需要人工清理；
--   3. attempts 仍只在真正失败时 +1，由持有租约的那个副本负责。
-- ============================================================================

ALTER TABLE clinical_svc.order_transfer_task
    ADD COLUMN IF NOT EXISTS locked_until timestamptz,
    ADD COLUMN IF NOT EXISTS locked_by   text;

COMMENT ON COLUMN clinical_svc.order_transfer_task.locked_until IS
    '租约到期时间：为空或已过期才可被 worker 抢占（多副本互斥，崩溃后自动释放）';
COMMENT ON COLUMN clinical_svc.order_transfer_task.locked_by IS
    '持有租约的 worker 标识（主机名-随机后缀），仅用于排障，不参与业务判断';
