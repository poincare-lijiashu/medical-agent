-- ============================================================================
-- 医保 V11：结算清单引用诊断（ICD-10）
--
-- 背景：结算清单此前只有费用明细，没有诊断；省局清单与 DRG/DIP 分组都要求
-- "主诊断 + 其他诊断"。诊断已在临床域结构化（clinical V13），本迁移把生成清单
-- 时的诊断快照固化到清单上，避免事后诊断被修改导致清单不可追溯。
--
-- 字段说明：
--   * primary_diagnosis_code / primary_diagnosis_name：主诊断（清单抬头用）；
--   * diagnosis_count：诊断总条数（含主诊断）；
--   * diagnoses：全部诊断的 JSON 快照（供报盘/病案首页/后续 DRG 使用）。
-- ============================================================================

ALTER TABLE insurance_svc.settlement_bill
    ADD COLUMN IF NOT EXISTS primary_diagnosis_code text,
    ADD COLUMN IF NOT EXISTS primary_diagnosis_name text,
    ADD COLUMN IF NOT EXISTS diagnosis_count int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS diagnoses jsonb NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN insurance_svc.settlement_bill.primary_diagnosis_code IS
    '主诊断编码（生成清单时的临床诊断快照；未录入诊断时为空）';
COMMENT ON COLUMN insurance_svc.settlement_bill.diagnoses IS
    '全部诊断快照 JSON：[{code,name,primary}]，供报盘与 DRG 分组使用';
