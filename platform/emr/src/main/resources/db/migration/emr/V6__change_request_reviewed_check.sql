-- ============================================================================
-- V6：病案修改申请的审批结果约束补强（S-18）
--
-- 原约束只要求"非 PENDING 必须有审批人/审批时间"，没有约束 APPROVED 必须落
-- applied_revision（实际应用到的版本号）。缺这一条时，审批通过的申请可能"没有真正改到病历"
-- 却显示已通过，事后追溯无从判断改的是哪一版。
-- ============================================================================

ALTER TABLE emr_svc.record_change_request
    DROP CONSTRAINT IF EXISTS ck_change_reviewed;

ALTER TABLE emr_svc.record_change_request
    ADD CONSTRAINT ck_change_reviewed CHECK (
        (status = 'PENDING' AND reviewer IS NULL AND reviewed_at IS NULL)
        OR (status = 'APPROVED' AND reviewer IS NOT NULL AND reviewed_at IS NOT NULL
            AND applied_revision IS NOT NULL)
        OR (status = 'REJECTED' AND reviewer IS NOT NULL AND reviewed_at IS NOT NULL
            AND applied_revision IS NULL)
    );
