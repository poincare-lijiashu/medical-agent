-- G45 病案域院区归属（多院区隔离试点）
--
-- medical_record / consent_record 增加 campus_id；存量数据归主院区。
-- 写入由 X-Campus 推导，查询按可见院区过滤，详情按院区二次校验。
ALTER TABLE emr_svc.medical_record
    ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE emr_svc.medical_record
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE emr_svc.medical_record
    ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_record_campus
    ON emr_svc.medical_record (campus_id, created_at DESC);

ALTER TABLE emr_svc.consent_record
    ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE emr_svc.consent_record
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE emr_svc.consent_record
    ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_consent_campus
    ON emr_svc.consent_record (campus_id, created_at DESC);

COMMENT ON COLUMN emr_svc.medical_record.campus_id IS '院区归属（G45）：创建时按账号可见院区固化，查询/详情按院区隔离';
COMMENT ON COLUMN emr_svc.consent_record.campus_id IS '院区归属（G45）：创建时按账号可见院区固化，查询/详情按院区隔离';
