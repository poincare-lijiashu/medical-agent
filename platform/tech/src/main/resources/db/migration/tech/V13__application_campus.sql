-- ============================================================================
-- V13：检查/检验申请院区归属（G49，延续 G45/G46/G47/G48 的多院区边界）
--
-- 申请院区在开单时固化：
--   * 手工开单取当前账号的写入院区（DataScope.campusForWrite）；
--   * 临床医嘱转申请取临床随请求下发的 campusId（clinical 转送时按就诊院区填充）。
--
-- 存量数据优先按就诊院区回填（同库跨 schema 只用于一次性迁移，运行时不跨库查询），
-- 找不到就诊的回退主院区，随后收紧 NOT NULL。
--
-- G69（评审 §6.8）：tech 可能先于 clinical 完成迁移，旧脚本的 `FROM clinical_svc.visit`
-- 会因缺列阻断启动。这里按列存在性守卫：clinical 未就绪时跳过按就诊回填，
-- 存量落主院区并标 campus_source='MAIN_FALLBACK'，便于事后精确修复。
-- ============================================================================

ALTER TABLE tech_svc.exam_application ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE tech_svc.exam_application ADD COLUMN IF NOT EXISTS campus_source text;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'clinical_svc' AND table_name = 'visit'
                  AND column_name = 'campus_id') THEN
        UPDATE tech_svc.exam_application a
           SET campus_id = v.campus_id, campus_source = 'VISIT'
          FROM clinical_svc.visit v
         WHERE v.id = a.visit_id
           AND a.campus_id IS NULL;
    END IF;
END $$;

UPDATE tech_svc.exam_application
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid,
       campus_source = COALESCE(campus_source, 'MAIN_FALLBACK')
 WHERE campus_id IS NULL;

ALTER TABLE tech_svc.exam_application ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE tech_svc.exam_application ALTER COLUMN campus_source SET DEFAULT 'RUNTIME';
ALTER TABLE tech_svc.exam_application ALTER COLUMN campus_source SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_exam_application_campus_created
    ON tech_svc.exam_application (campus_id, created_at DESC);

COMMENT ON COLUMN tech_svc.exam_application.campus_id IS '申请所属院区（开单时固化；列表/计数/报告/危急值/PACS 按此收敛）';
COMMENT ON COLUMN tech_svc.exam_application.campus_source IS
    '院区来源：RUNTIME=运行时固化，VISIT=迁移时按就诊回填，MAIN_FALLBACK=迁移时无法判定落主院区';
