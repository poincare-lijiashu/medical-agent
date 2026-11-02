-- ============================================================================
-- V9：退药幂等键 + 退药记录追加式防护
--
-- 背景（多智能体评审 P0-3）：returnDrug 只有"累计退药 ≤ 已发"这一条上限守卫，
-- 没有调用方幂等键。护士站网络抖动/双击导致的重试，只要还在上限内就会再退一次，
-- 结果是批次库存被双倍回补、dispense_return 出现两条相同记录，与临床退费链对不上。
--
-- 修复：
--   1. 退药记录 id 由调用方传入（ReturnReq.returnId），作为主键天然唯一；
--      重复提交命中主键冲突 → 服务层回查并原样返回，不再动库存。
--   2. dispense_return 是账实旁证，禁止 UPDATE/DELETE（与 V7 审计表同口径）。
--
-- 兼容：未传 returnId 的旧调用由服务端生成 UUID，行为与之前一致（只是不保证重试幂等）。
-- ============================================================================

-- 退药记录：只允许 INSERT。退药是库存与费用的原始凭证，改一行就等于改账。
DROP TRIGGER IF EXISTS trg_dispense_return_append_only ON pharmacy_svc.dispense_return;
CREATE TRIGGER trg_dispense_return_append_only
    BEFORE UPDATE OR DELETE ON pharmacy_svc.dispense_return
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

COMMENT ON COLUMN pharmacy_svc.dispense_return.id IS
    '退药单号：由调用方传入的幂等键（ReturnReq.returnId），重试命中同一主键不再动库存';

CREATE INDEX IF NOT EXISTS ix_dispense_return_item
    ON pharmacy_svc.dispense_return (dispense_item_id, created_at DESC);
