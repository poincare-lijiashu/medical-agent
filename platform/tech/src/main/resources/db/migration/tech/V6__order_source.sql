-- ============================================================================
-- 医技 V6：申请来源医嘱（临床医嘱 → 检查/检验申请 的幂等键）
--
-- 背景：与药房同一缺口——医师开的检查/检验医嘱到不了医技，患者要拿着纸质单
-- 到登记窗口重新排队开单。本迁移让临床把 EXAM 医嘱转成医技申请。
--
-- 约定：
--   * source_order_id 唯一（部分索引，手工开单为 NULL 不参与）→ 一张医嘱最多一张申请；
--   * cancel_reason：医嘱撤销/停嘱联动作废时留痕（仅 APPLIED 可作废，已采集/执行中的
--     申请必须人工处理，避免"标本已采、检查已做却把单作废"）。
-- ============================================================================

ALTER TABLE tech_svc.exam_application
    ADD COLUMN IF NOT EXISTS source_order_id uuid,
    ADD COLUMN IF NOT EXISTS cancel_reason text;

COMMENT ON COLUMN tech_svc.exam_application.source_order_id IS
    '来源临床医嘱 id（NULL=医技窗口手工开单）；一张医嘱最多一张申请';
COMMENT ON COLUMN tech_svc.exam_application.cancel_reason IS
    '作废原因（来自医嘱撤销/停嘱或医技人工作废）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_exam_application_source_order
    ON tech_svc.exam_application (source_order_id)
    WHERE source_order_id IS NOT NULL;
