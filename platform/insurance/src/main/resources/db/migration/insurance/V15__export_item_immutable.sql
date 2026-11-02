-- ============================================================================
-- V15：报盘明细不可改删（对齐 emr V12 / MF-09，补齐医保域同型漏洞）
--
-- 问题（多智能体评审 P0-4，资金红线）：
--   V13 的 trg_export_item_single_active_batch 只挂在 BEFORE INSERT OR UPDATE，
--   没有任何 DELETE 防护。直连数据库删掉一条批次明细，就能把已报盘的清单再次
--   打进新批次 —— 省局侧重复结算。emr 域在 V12 已用 guard_hqms_item 修掉同型问题，
--   医保域当时漏修。
--
-- 修复口径与 emr 完全一致：
--   1. 批次处于有效状态（GENERATED / ACCEPTED）时，其明细既不可 UPDATE 也不可 DELETE；
--   2. 纠正报盘只允许走批次级状态迁移（撤销 → CANCELLED / 回执 → ACCEPTED / REJECTED），
--      状态离开有效集合后明细才可清理；
--   3. 历史批次不做数据改写，迁移只加守门，不改任何既有行。
--
-- 注意：触发器在 BEFORE 阶段直接 RAISE，报错码用 check_violation，
-- 便于服务层/运维脚本据此判定为"业务拒绝"而不是"数据库故障"。
-- ============================================================================

CREATE OR REPLACE FUNCTION insurance_svc.guard_export_item_immutable()
RETURNS trigger AS $$
DECLARE
    batch_status text;
BEGIN
    SELECT status INTO batch_status
      FROM insurance_svc.settlement_export_batch
     WHERE id = OLD.batch_id;

    IF batch_status IN ('GENERATED', 'ACCEPTED') THEN
        RAISE EXCEPTION
            '报盘批次 %（%）仍为有效状态，其报盘明细不可修改或删除；如报盘有误请走批次级撤销（CANCELLED）',
            OLD.batch_id, batch_status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_export_item_immutable
    ON insurance_svc.settlement_export_item;
CREATE TRIGGER trg_export_item_immutable
    BEFORE UPDATE OR DELETE ON insurance_svc.settlement_export_item
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.guard_export_item_immutable();

COMMENT ON FUNCTION insurance_svc.guard_export_item_immutable() IS
    '报盘明细守门：批次为 GENERATED/ACCEPTED 时明细不可改删，避免"删明细后重报"造成省局重复结算';
