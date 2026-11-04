-- ============================================================================
-- V20：处方院区归属（G47，延续 G45/G46 的多院区边界）
--
-- 处方院区在开方时固化：
--   * 手工开方取当前账号的写入院区（DataScope.campusForWrite）；
--   * 医嘱转处方取临床随请求下发的 campusId（clinical 在转送时按就诊院区填充）。
--
-- 存量数据优先按就诊院区回填（同库跨 schema 只用于一次性迁移，运行时不跨库查询），
-- 找不到就诊的回退主院区，随后收紧 NOT NULL。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription ADD COLUMN IF NOT EXISTS campus_id uuid;

UPDATE pharmacy_svc.prescription p
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = p.visit_id
   AND p.campus_id IS NULL;

UPDATE pharmacy_svc.prescription
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;

ALTER TABLE pharmacy_svc.prescription ALTER COLUMN campus_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_prescription_campus_created
    ON pharmacy_svc.prescription (campus_id, created_at DESC);

COMMENT ON COLUMN pharmacy_svc.prescription.campus_id IS '处方所属院区（开方时固化；列表/详情/待办计数按此收敛）';
