-- ============================================================================
-- 药房 V5：处方作废（医嘱撤销/停嘱联动）
--
-- 背景：处方由医嘱转送生成后，医师撤销医嘱或停长嘱时，药房侧没有任何信号，
-- 药师仍可能按已停止的医嘱发药——这是用药安全缺口。
--
-- 本迁移补：
--   * cancel_reason / cancelled_by / cancelled_at：作废必须留痕（谁、为什么、何时）；
--   * CHECK：状态为 CANCELLED 时 cancel_reason 必填（与 REJECTED 同一口径）。
--
-- 业务约束（服务层）：
--   * 已发药（DISPENSED）的处方不能作废，只能走退药；
--   * 重复作废幂等（返回 cancelled=true），临床重试不会报错。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription
    ADD COLUMN IF NOT EXISTS cancel_reason text,
    ADD COLUMN IF NOT EXISTS cancelled_by text,
    ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;

ALTER TABLE pharmacy_svc.prescription DROP CONSTRAINT IF EXISTS ck_prescription_cancel;
ALTER TABLE pharmacy_svc.prescription
    ADD CONSTRAINT ck_prescription_cancel CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    );

COMMENT ON COLUMN pharmacy_svc.prescription.cancel_reason IS
    '作废原因（CANCELLED 时必填）：来自医嘱撤销/停嘱或药房人工作废';
