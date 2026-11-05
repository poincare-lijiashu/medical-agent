-- ============================================================================
-- V57：临床子表院区归属（G66，评审 §6.1）
--
-- 背景：G46 只给 visit / ward 加了 campus_id，收费、票据、急诊分诊、输血申请、
-- 护理执行五张派生表仍无院区列；这些表是就诊级资金与执行数据，按科室字典
-- （全院共享、不跨院区）判定会让主院区账号读到东院区数据。
--
-- 口径：
--   * 五张表的院区一律从**同 schema 的就诊**回溯（不跨 schema，避免迁移顺序耦合）；
--   * 急诊分诊在"未挂号"阶段还没有就诊，回填落主院区，新建时按提交账号写入院区固化；
--   * 随后收紧 NOT NULL 并建索引，列表/看板按院区过滤。
-- ============================================================================

-- 1) 收费单
ALTER TABLE clinical_svc.charge ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE clinical_svc.charge c
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = c.visit_id AND c.campus_id IS NULL;
UPDATE clinical_svc.charge
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE clinical_svc.charge ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_charge_campus
    ON clinical_svc.charge (campus_id, created_at DESC);

-- 2) 收费票据
ALTER TABLE clinical_svc.charge_receipt ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE clinical_svc.charge_receipt r
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = r.visit_id AND r.campus_id IS NULL;
UPDATE clinical_svc.charge_receipt
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE clinical_svc.charge_receipt ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_charge_receipt_campus
    ON clinical_svc.charge_receipt (campus_id, created_at DESC);

-- 3) 急诊分诊（无就诊的待分诊记录落主院区；新建按账号写入院区）
ALTER TABLE clinical_svc.emergency_triage ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE clinical_svc.emergency_triage t
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = t.visit_id AND t.campus_id IS NULL;
UPDATE clinical_svc.emergency_triage
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE clinical_svc.emergency_triage ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_emergency_triage_campus
    ON clinical_svc.emergency_triage (campus_id, arrival_at DESC);

-- 4) 输血申请
ALTER TABLE clinical_svc.transfusion_request ADD COLUMN IF NOT EXISTS campus_id uuid;
UPDATE clinical_svc.transfusion_request t
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = t.visit_id AND t.campus_id IS NULL;
UPDATE clinical_svc.transfusion_request
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE clinical_svc.transfusion_request ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_transfusion_request_campus
    ON clinical_svc.transfusion_request (campus_id, created_at DESC);

-- 5) 护理执行
--    nursing_execution 是追加式审计表（V29 的 trg_nursing_execution_append_only 拒绝 UPDATE），
--    存量回填只能在迁移事务内临时关掉该触发器，回填后立刻恢复；触发器定义本身不改，
--    业务侧"改历史"仍会被拒绝。
ALTER TABLE clinical_svc.nursing_execution ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE clinical_svc.nursing_execution
    DISABLE TRIGGER trg_nursing_execution_append_only;
UPDATE clinical_svc.nursing_execution e
   SET campus_id = v.campus_id
  FROM clinical_svc.visit v
 WHERE v.id = e.visit_id AND e.campus_id IS NULL;
UPDATE clinical_svc.nursing_execution
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;
ALTER TABLE clinical_svc.nursing_execution
    ENABLE TRIGGER trg_nursing_execution_append_only;
ALTER TABLE clinical_svc.nursing_execution ALTER COLUMN campus_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_nursing_execution_campus
    ON clinical_svc.nursing_execution (campus_id, exec_date DESC);

COMMENT ON COLUMN clinical_svc.charge.campus_id IS '院区归属（G66）：随就诊固化，列表/详情按院区隔离';
COMMENT ON COLUMN clinical_svc.charge_receipt.campus_id IS '院区归属（G66）：随就诊固化，票据查询/作废按院区隔离';
COMMENT ON COLUMN clinical_svc.emergency_triage.campus_id IS '院区归属（G66）：分诊建单按提交账号院区固化';
COMMENT ON COLUMN clinical_svc.transfusion_request.campus_id IS '院区归属（G66）：随就诊固化';
COMMENT ON COLUMN clinical_svc.nursing_execution.campus_id IS '院区归属（G66）：随就诊固化';
