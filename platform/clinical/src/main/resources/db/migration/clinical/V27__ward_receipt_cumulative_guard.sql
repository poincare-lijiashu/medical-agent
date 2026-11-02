-- ============================================================================
-- V27：病区收药累计上限（库级兜底）
--
-- 背景（多智能体评审 P0-2）：收药只校验"单次数量 ≤ 当日请领量"，
-- 换一个 dispenseId 就能再登记一次，累计可以超过请领量 → 病区二级库存虚增，
-- 随后还能把"不存在的药"退回，账实永久偏离。
--
-- 修复：在 ward_med_receipt 上加 BEFORE INSERT 触发器，
--   1. 按 (order_id, supply_date) 取事务级 advisory lock，串行化并发收药；
--   2. 校验"已收 + 本次 ≤ 当日请领量"，超出抛 check_violation（服务层映射 409）。
--
-- 说明：服务层仍保留同样的判断，用于返回可读的 422；数据库这层负责"应用层被绕过也不出错"。
-- ============================================================================

CREATE OR REPLACE FUNCTION clinical_svc.guard_ward_receipt_cumulative() RETURNS trigger AS $$
DECLARE
    requested integer;
    received  integer;
BEGIN
    -- 同一医嘱同一天的收药必须串行，否则两个事务各自读到旧合计都能通过
    PERFORM pg_advisory_xact_lock(
        hashtextextended('clinical:ward-receipt:' || NEW.order_id::text
                         || ':' || NEW.supply_date::text, 0));

    SELECT quantity INTO requested
      FROM clinical_svc.daily_dispense_request
     WHERE order_id = NEW.order_id AND supply_date = NEW.supply_date;

    -- 没有请领台账时不在这里拦：那是"未请领就收药"，由服务层直接拒绝并给业务文案
    IF requested IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT coalesce(sum(quantity), 0) INTO received
      FROM clinical_svc.ward_med_receipt
     WHERE order_id = NEW.order_id AND supply_date = NEW.supply_date;

    IF received + NEW.quantity > requested THEN
        RAISE EXCEPTION '病区累计收药超过当日请领数量（请领 %，已收 %，本次 %）',
            requested, received, NEW.quantity
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ward_receipt_cumulative ON clinical_svc.ward_med_receipt;
CREATE TRIGGER trg_ward_receipt_cumulative
    BEFORE INSERT ON clinical_svc.ward_med_receipt
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_ward_receipt_cumulative();

COMMENT ON FUNCTION clinical_svc.guard_ward_receipt_cumulative() IS
    '病区收药累计兜底：同一医嘱同一天累计收货不得超过当日请领量（并发按 order+date 串行）';
