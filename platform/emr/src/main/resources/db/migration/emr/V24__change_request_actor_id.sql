-- ============================================================================
-- V24：病案修改申请的四眼标识（G54）
--
-- 背景：审批互斥原先比较用户名（requester vs actor）。用户名不唯一标识自然人——
-- 账号重建、共享账号、同名账号都能让"同一人"以两个用户名出现，从而自审自批
-- 归档病历的修改申请（V14 已对首页归档改用 submitted_by_id，此处补齐同一口径）。
--
-- 存量行 requester_id 为 NULL：审批时按 fail-closed 拒绝，由管理员确认历史申请
-- 的归属后重走流程，不做启发式回填（回填错等于永久丢失"谁申请的"）。
-- ============================================================================

ALTER TABLE emr_svc.record_change_request ADD COLUMN IF NOT EXISTS requester_id uuid;
ALTER TABLE emr_svc.record_change_request ADD COLUMN IF NOT EXISTS reviewer_id uuid;

COMMENT ON COLUMN emr_svc.record_change_request.requester_id IS
    '申请人账号主键（X-User-Id）；为空的历史申请不允许审批（四眼无法判定）';
COMMENT ON COLUMN emr_svc.record_change_request.reviewer_id IS
    '审批人账号主键；与 requester_id 相同即自审自批，服务层拒绝';

CREATE INDEX IF NOT EXISTS ix_record_change_request_requester
    ON emr_svc.record_change_request (requester_id);
