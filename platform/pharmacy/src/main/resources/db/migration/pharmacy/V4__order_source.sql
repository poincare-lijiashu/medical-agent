-- ============================================================================
-- 药房 V4：处方来源医嘱（临床医嘱 → 药房处方 主链路的幂等键）
--
-- 背景：此前药房处方只能由药房窗口手工开立，医师在临床工作站开的药品医嘱
-- 到不了药房，患者必须先看病再跑到药房窗口重新报药名——HIS 主链路是断的。
--
-- 本迁移引入 source_order_id：
--   * 由临床医嘱转送的处方必须带来源医嘱 id，一张医嘱最多生成一张处方；
--   * 唯一索引（部分索引，手工处方为 NULL 不参与）保证重复转送不会产生重复处方；
--   * 临床侧失败重试时按该字段返回已有处方（幂等），避免"重试一次多一张处方"。
--
-- 计费口径：转送生成的处方固定 billing_mode=ORDER（费用已在临床医嘱计价），
-- 药房只负责审核与发药，不产生第二条计费链。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription
    ADD COLUMN IF NOT EXISTS source_order_id uuid;

COMMENT ON COLUMN pharmacy_svc.prescription.source_order_id IS
    '来源临床医嘱 id（NULL=药房手工开方）；一张医嘱最多一张处方，重复转送按该字段幂等';

CREATE UNIQUE INDEX IF NOT EXISTS ux_prescription_source_order
    ON pharmacy_svc.prescription (source_order_id)
    WHERE source_order_id IS NOT NULL;
