-- ============================================================================
-- V13：报盘清单"同一清单只能在一个有效批次"库级兜底
--
-- 问题（多智能体评审 B1，资金红线）：服务层 advisory lock 的键含"导出期间"，
-- 两个**重叠但边界不同**的期间（如 1/1–1/15 与 1/1–1/31）会拿到不同的锁，
-- 各自的 NOT EXISTS 在对方提交前都查到空 → 同一张清单进入两个 GENERATED 批次，
-- 省局侧即重复结算。违反"幂等优先用数据库约束"的工程铁律。
--
-- 修复：把"一张清单只能属于一个 GENERATED/ACCEPTED 批次"下沉为数据库约束：
--   1. 按 bill_id 取事务级 advisory lock —— 并发插入同一清单时串行判定；
--   2. 若已存在其它有效批次包含该清单，抛 unique_violation（服务层映射 409）。
-- 服务层的期间锁保留，只作为"提前失败"与减少碰撞的性能优化，不再承担正确性。
--
-- 注意：REJECTED（省局退回）批次不占位，允许重新报盘——与既有"退回/撤销可重报"
-- 的业务口径一致。
-- ============================================================================

CREATE OR REPLACE FUNCTION insurance_svc.guard_export_item_single_active_batch()
RETURNS trigger AS $$
DECLARE
    batch_status text;
BEGIN
    SELECT status INTO batch_status
      FROM insurance_svc.settlement_export_batch
     WHERE id = NEW.batch_id;

    -- 只有进入"有效批次"的清单才需要唯一性判定；REJECTED 批次可以重报
    IF batch_status IN ('GENERATED', 'ACCEPTED') THEN
        -- 同一清单的并发插入必须串行：否则两个事务互相看不见未提交行
        PERFORM pg_advisory_xact_lock(
            hashtextextended('insurance:export-item:' || NEW.bill_id::text, 0));
        IF EXISTS (
            SELECT 1
              FROM insurance_svc.settlement_export_item i
              JOIN insurance_svc.settlement_export_batch b ON b.id = i.batch_id
             WHERE i.bill_id = NEW.bill_id
               AND i.batch_id <> NEW.batch_id
               AND b.status IN ('GENERATED', 'ACCEPTED')
        ) THEN
            RAISE EXCEPTION '该结算清单已被其它有效报盘批次包含（bill_id=%）', NEW.bill_id
                USING ERRCODE = 'unique_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_export_item_single_active_batch
    ON insurance_svc.settlement_export_item;
CREATE TRIGGER trg_export_item_single_active_batch
    BEFORE INSERT OR UPDATE ON insurance_svc.settlement_export_item
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.guard_export_item_single_active_batch();

COMMENT ON FUNCTION insurance_svc.guard_export_item_single_active_batch() IS
    '报盘清单唯一性兜底：一张清单不得同时属于两个 GENERATED/ACCEPTED 批次（防省局重复结算）';
