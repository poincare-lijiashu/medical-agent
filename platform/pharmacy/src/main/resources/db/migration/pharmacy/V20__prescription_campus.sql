-- ============================================================================
-- V20：处方院区归属（G47，延续 G45/G46 的多院区边界）
--
-- 处方院区在开方时固化：
--   * 手工开方取当前账号的写入院区（DataScope.campusForWrite）；
--   * 医嘱转处方取临床随请求下发的 campusId（clinical 在转送时按就诊院区填充）。
--
-- 存量数据优先按就诊院区回填（同库跨 schema 只用于一次性迁移，运行时不跨库查询），
-- 找不到就诊的回退主院区，随后收紧 NOT NULL。
--
-- G69（评审 §6.8）：各服务独立启动、各自跑 Flyway，若 pharmacy 先于 clinical 完成迁移，
-- 旧脚本的 `FROM clinical_svc.visit` 会因缺列直接阻断启动。这里按列存在性做守卫：
-- clinical 尚未就绪时跳过按就诊回填，存量落主院区并把 campus_source 记为 MAIN_FALLBACK，
-- 便于事后按标记精确修复（不会把"运行时本来就该是主院区"的数据误改）。
-- ============================================================================

ALTER TABLE pharmacy_svc.prescription ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE pharmacy_svc.prescription ADD COLUMN IF NOT EXISTS campus_source text;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'clinical_svc' AND table_name = 'visit'
                  AND column_name = 'campus_id') THEN
        UPDATE pharmacy_svc.prescription p
           SET campus_id = v.campus_id, campus_source = 'VISIT'
          FROM clinical_svc.visit v
         WHERE v.id = p.visit_id
           AND p.campus_id IS NULL;
    END IF;
END $$;

UPDATE pharmacy_svc.prescription
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid,
       campus_source = COALESCE(campus_source, 'MAIN_FALLBACK')
 WHERE campus_id IS NULL;

ALTER TABLE pharmacy_svc.prescription ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE pharmacy_svc.prescription ALTER COLUMN campus_source SET DEFAULT 'RUNTIME';
ALTER TABLE pharmacy_svc.prescription ALTER COLUMN campus_source SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_prescription_campus_created
    ON pharmacy_svc.prescription (campus_id, created_at DESC);

COMMENT ON COLUMN pharmacy_svc.prescription.campus_id IS '处方所属院区（开方时固化；列表/详情/待办计数按此收敛）';
COMMENT ON COLUMN pharmacy_svc.prescription.campus_source IS
    '院区来源：RUNTIME=运行时固化，VISIT=迁移时按就诊回填，MAIN_FALLBACK=迁移时无法判定落主院区';
