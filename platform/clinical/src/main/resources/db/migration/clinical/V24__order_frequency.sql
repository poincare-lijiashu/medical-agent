-- ============================================================================
-- V24：长嘱数量口径与频次快照（评审 B2）
--
-- 背景：`visit_order.quantity` 此前没有明确是"单次量"还是"每日/本次总量"，
--       而按日计费直接按 amount 每天计一次，导致同一张长嘱在不同人眼里金额不同。
--
-- 本迁移只固化口径，不改变已有金额：
--   1. 明确 quantity 语义（代码注释 + 文档 §1.57）：
--        TEMP：本次总量；LONG：每日总量（amount = 每日金额）
--   2. 新增 frequency_times（每日执行次数快照），由服务端按受控频次词表解析，
--      客户端不可传；历史数据默认 1（qd），不影响既有金额
--   3. 数据库层兜底：1..24 次/日
-- ============================================================================

ALTER TABLE clinical_svc.visit_order
    ADD COLUMN IF NOT EXISTS frequency_times smallint NOT NULL DEFAULT 1;

ALTER TABLE clinical_svc.visit_order
    DROP CONSTRAINT IF EXISTS visit_order_frequency_times_check;

ALTER TABLE clinical_svc.visit_order
    ADD CONSTRAINT visit_order_frequency_times_check
        CHECK (frequency_times BETWEEN 1 AND 24);

COMMENT ON COLUMN clinical_svc.visit_order.quantity IS
    'TEMP=本次总量；LONG=每日总量（amount 为每日金额，按日计费每天一次）';
COMMENT ON COLUMN clinical_svc.visit_order.frequency_times IS
    '每日执行次数快照（由受控频次词表解析，客户端不可传）；LONG 用于护理执行，计费仍按天';
