-- ============================================================================
-- V2：三级签名策略 / 归档后病案修改申请 / CDR 检索索引
--
-- 设计要点：
--   * 签名级别不复存在 medical_record 上，而是由 record_signature 派生
--     （MAX(级别)），签名表是唯一事实源，避免两处状态不一致；
--   * 归档所需的签名级别按文书类型配置（sign_policy），病案室可调整；
--   * 归档后修改必须走 record_change_request：申请人提交 → 他人审批 → 落库并
--     revision+1、回到 SUBMITTED、清空签名（必须重新签署与归档）；
--   * 检索使用 pg_trgm 加速 ILIKE；扩展不可用时仅退化为顺序扫描，不影响功能。
-- ============================================================================

-- ---- 归档签名策略 ----
CREATE TABLE IF NOT EXISTS emr_svc.sign_policy (
    doc_type       text PRIMARY KEY,
    required_level int NOT NULL CHECK (required_level BETWEEN 1 AND 3),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

INSERT INTO emr_svc.sign_policy (doc_type, required_level) VALUES
    ('ADMISSION_NOTE',   2),   -- 入院记录：书写医师 + 主治
    ('FIRST_COURSE',     2),   -- 首次病程：书写医师 + 主治
    ('PROGRESS_NOTE',    1),   -- 日常病程：书写医师
    ('DISCHARGE_SUMMARY',3),   -- 出院小结：书写 + 主治 + 主任
    ('OPERATION_NOTE',   3),   -- 手术记录：书写 + 主治 + 主任
    ('OUTPATIENT_NOTE',  1),
    ('CONSULT_NOTE',     1)
ON CONFLICT (doc_type) DO NOTHING;

-- ---- 归档后病案修改申请 ----
CREATE TABLE IF NOT EXISTS emr_svc.record_change_request (
    id                uuid PRIMARY KEY,
    record_id         uuid NOT NULL REFERENCES emr_svc.medical_record (id),
    record_no         text NOT NULL,
    reason            text NOT NULL,
    proposed_content  jsonb NOT NULL,
    proposed_text     text NOT NULL,
    status            text NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requester         text NOT NULL,
    requested_at      timestamptz NOT NULL DEFAULT now(),
    reviewer          text,
    reviewed_at       timestamptz,
    review_comment    text,
    applied_revision  int,
    CONSTRAINT ck_change_reviewed CHECK (
        (status = 'PENDING' AND reviewer IS NULL AND reviewed_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND reviewer IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

-- 一份病历同时只允许一条待审批申请（并发重复提交由数据库兜底）
CREATE UNIQUE INDEX IF NOT EXISTS ux_change_pending
    ON emr_svc.record_change_request (record_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_change_record
    ON emr_svc.record_change_request (record_id, requested_at DESC);

-- ---- 检索索引（pg_trgm 不可用时静默跳过，功能仍可用）----
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm 不可用，跳过三元组索引（检索退化为顺序扫描）';
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_record_text_trgm '
             || 'ON emr_svc.medical_record USING gin (content_text gin_trgm_ops)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_record_title_trgm '
             || 'ON emr_svc.medical_record USING gin (title gin_trgm_ops)';
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '三元组索引创建失败，检索退化为顺序扫描';
END $$;
