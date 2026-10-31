-- ============================================================================
-- V4：CDR 检索索引
--
-- 背景：原来的单条 SQL 把 title/content_text/record_no/patient_no 四个 ILIKE 用 OR
-- 串在一起，任何一列都没有可用索引，只能全表扫描 + 排序，病历量上去后检索会拖垮库。
--
-- 处理口径：
--   1) 标题/正文模糊检索（%kw%）走 pg_trgm 的 GIN 索引；
--   2) 病历号/患者号按"前缀"检索（kw%），走 text_pattern_ops 的 B-tree 索引。
--
-- pg_trgm 是 PostgreSQL 13+ 的 trusted extension，库属主即可安装；个别托管库或
-- 安全加固的实例可能禁止安装，此时迁移不失败，仅退化为顺序扫描（功能可用，性能下降）。
-- ============================================================================

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm 不可用（%），标题/正文模糊检索将退化为顺序扫描', SQLERRM;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_record_title_trgm
                     ON emr_svc.medical_record USING gin (title gin_trgm_ops)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_record_content_trgm
                     ON emr_svc.medical_record USING gin (content_text gin_trgm_ops)';
    ELSE
        RAISE NOTICE '跳过 trgm 索引创建：emr_svc.medical_record.title / content_text';
    END IF;
END $$;

-- 编号列默认排序规则不是 C 时，普通 B-tree 无法服务 LIKE 'kw%'，需 text_pattern_ops。
CREATE INDEX IF NOT EXISTS ix_record_no_prefix
    ON emr_svc.medical_record (record_no text_pattern_ops);
CREATE INDEX IF NOT EXISTS ix_record_patient_no_prefix
    ON emr_svc.medical_record (patient_no text_pattern_ops);

COMMENT ON INDEX emr_svc.ix_record_no_prefix IS 'CDR 检索：病历号前缀匹配';
COMMENT ON INDEX emr_svc.ix_record_patient_no_prefix IS 'CDR 检索：患者号前缀匹配';
