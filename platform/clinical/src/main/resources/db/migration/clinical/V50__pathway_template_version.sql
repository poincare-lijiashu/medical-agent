-- ============================================================================
-- V50：临床路径模板版本（G32）
--
-- 发布后的模板不可修改（V48 约束），需要调整时从原模板克隆出新版本：
--   * origin_id：同一路径家族的根模板 ID（首次创建时等于自身）；
--   * version_no：家族内版本号，从 1 开始递增。
-- 已入径患者仍按任务快照执行，不受新版本影响。
-- ============================================================================

ALTER TABLE clinical_svc.pathway_template
    ADD COLUMN IF NOT EXISTS version_no integer NOT NULL DEFAULT 1;

ALTER TABLE clinical_svc.pathway_template
    ADD COLUMN IF NOT EXISTS origin_id uuid;

UPDATE clinical_svc.pathway_template
   SET origin_id = id
 WHERE origin_id IS NULL;

ALTER TABLE clinical_svc.pathway_template
    ALTER COLUMN origin_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_pathway_template_origin
    ON clinical_svc.pathway_template (origin_id, version_no DESC);

COMMENT ON COLUMN clinical_svc.pathway_template.origin_id IS
    '路径家族根模板 ID；克隆新版本时沿用，用于版本对比与统计';
COMMENT ON COLUMN clinical_svc.pathway_template.version_no IS
    '家族内版本号，从 1 开始递增；同一 orig_id 下唯一';
