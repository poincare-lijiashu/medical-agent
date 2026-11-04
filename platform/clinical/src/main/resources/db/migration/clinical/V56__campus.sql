-- ============================================================================
-- V56：门诊/住院院区归属（G46，延续 G45 的多院区边界）
--
-- 归属规则：
--   * visit.campus_id  就诊院区——挂号/入院时固化，医嘱、收费、诊断、病历都随就诊；
--   * ward.campus_id   病区院区——入院必须落在账号可见的院区病区，床位不跨院区占用。
--
-- 存量数据回填主院区（与 auth V72 的 MAIN 一致），随后收紧 NOT NULL，
-- 避免后续写入缺省归属导致"跨院区可见"。
-- ============================================================================

-- ---- 就诊院区 ----
ALTER TABLE clinical_svc.visit ADD COLUMN IF NOT EXISTS campus_id uuid;

UPDATE clinical_svc.visit
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;

ALTER TABLE clinical_svc.visit ALTER COLUMN campus_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_visit_campus_date
    ON clinical_svc.visit (campus_id, visit_date);
COMMENT ON COLUMN clinical_svc.visit.campus_id IS '就诊所属院区（挂号/入院时固化；列表与详情按此收敛）';

-- ---- 病区院区 ----
ALTER TABLE clinical_svc.ward ADD COLUMN IF NOT EXISTS campus_id uuid;

UPDATE clinical_svc.ward
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;

ALTER TABLE clinical_svc.ward ALTER COLUMN campus_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_ward_campus
    ON clinical_svc.ward (campus_id);
COMMENT ON COLUMN clinical_svc.ward.campus_id IS '病区所属院区（入院与床位操作必须在该院区内）';
