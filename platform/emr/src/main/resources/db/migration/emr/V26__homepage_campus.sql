-- ============================================================================
-- V26：病案首页补院区归属（G60，评审 §6.10）
--
-- 背景：G45 只给 medical_record / consent_record 加了 campus_id，
-- record_homepage 仍无院区列，而 EmrAccessGuard.requireHomepage 只校科室；
-- 科室是全院字典、不跨院区，于是主院区账号可读取东院区首页（姓名/诊断/费用/手术），
-- 同一 guard 内 requireRecord（dept+campus）与 requireHomepage（仅 dept）两套口径。
--
-- 本迁移：
--   * 补 campus_id；存量优先按同 schema 的 medical_record.visit_id 回溯院区
--     （emr 自己的一张表，不依赖 clinical schema，避免跨服务迁移顺序耦合）；
--   * 回溯不到的落主院区（与 G45 口径一致），不做跨 schema 猜测；
--   * 建索引；写入由 HomepageService 用 scope.campusForWrite() 固化。
-- ============================================================================

ALTER TABLE emr_svc.record_homepage
    ADD COLUMN IF NOT EXISTS campus_id uuid;

UPDATE emr_svc.record_homepage h
   SET campus_id = r.campus_id
  FROM emr_svc.medical_record r
 WHERE h.campus_id IS NULL
   AND r.visit_id = h.visit_id
   AND r.campus_id IS NOT NULL;

UPDATE emr_svc.record_homepage
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'
 WHERE campus_id IS NULL;

ALTER TABLE emr_svc.record_homepage
    ALTER COLUMN campus_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_homepage_campus
    ON emr_svc.record_homepage (campus_id, created_at DESC);

COMMENT ON COLUMN emr_svc.record_homepage.campus_id IS
    '院区归属（G60）：生成时按提交账号写入院区固化，列表/详情按院区隔离';
