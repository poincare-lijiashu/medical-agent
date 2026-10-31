-- ============================================================================
-- V7：病案修改申请的闭环（S-02 / S-06）
--
-- 背景（第三轮评测）：
--   * S-02：审批通过后修订留痕与申请单没有关联，追查"这一版是谁申请改的"要跨表人工拼接；
--   * S-06：待审批申请没有撤回通道，一旦误提交会长期占用"同一病历只允许一条 PENDING"的通道；
--           驳回后重新申请也无法与上一次关联。
--
-- 处理：
--   * record_amendment 增加 change_request_id（审批产生的修订直接指向申请单）；
--   * record_change_request 状态增加 WITHDRAWN（申请人撤回），并增加 resubmit_of 关联上次申请；
--   * 撤回同样要留"谁在什么时候撤回"，因此沿用 reviewer/reviewed_at 两列，但不写 applied_revision。
-- ============================================================================

ALTER TABLE emr_svc.record_amendment
    ADD COLUMN IF NOT EXISTS change_request_id uuid;

ALTER TABLE emr_svc.record_amendment
    DROP CONSTRAINT IF EXISTS fk_amendment_change_request;
ALTER TABLE emr_svc.record_amendment
    ADD CONSTRAINT fk_amendment_change_request
    FOREIGN KEY (change_request_id) REFERENCES emr_svc.record_change_request (id);

CREATE INDEX IF NOT EXISTS ix_amendment_change_request
    ON emr_svc.record_amendment (change_request_id)
    WHERE change_request_id IS NOT NULL;

ALTER TABLE emr_svc.record_change_request
    ADD COLUMN IF NOT EXISTS resubmit_of uuid;

ALTER TABLE emr_svc.record_change_request
    DROP CONSTRAINT IF EXISTS fk_change_resubmit;
ALTER TABLE emr_svc.record_change_request
    ADD CONSTRAINT fk_change_resubmit
    FOREIGN KEY (resubmit_of) REFERENCES emr_svc.record_change_request (id);

ALTER TABLE emr_svc.record_change_request
    DROP CONSTRAINT IF EXISTS record_change_request_status_check;
ALTER TABLE emr_svc.record_change_request
    ADD CONSTRAINT record_change_request_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'));

ALTER TABLE emr_svc.record_change_request
    DROP CONSTRAINT IF EXISTS ck_change_reviewed;
ALTER TABLE emr_svc.record_change_request
    ADD CONSTRAINT ck_change_reviewed CHECK (
        (status = 'PENDING' AND reviewer IS NULL AND reviewed_at IS NULL)
        OR (status = 'APPROVED' AND reviewer IS NOT NULL AND reviewed_at IS NOT NULL
            AND applied_revision IS NOT NULL)
        OR (status IN ('REJECTED', 'WITHDRAWN') AND reviewer IS NOT NULL
            AND reviewed_at IS NOT NULL AND applied_revision IS NULL)
    );
