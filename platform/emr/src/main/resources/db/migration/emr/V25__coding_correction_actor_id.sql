-- ============================================================================
-- V25：病案首页编码更正的四眼标识（G55）
--
-- 与 V24（record_change_request）同一口径：审批互斥不能比较用户名。
-- 编码更正决定 DRG 入组与首页上报内容，自审自批的经济影响比文书修改更大。
--
-- 存量行 requester_id 为 NULL：审批按 fail-closed 拒绝，不做启发式回填。
-- ============================================================================

ALTER TABLE emr_svc.coding_correction ADD COLUMN IF NOT EXISTS requester_id uuid;
ALTER TABLE emr_svc.coding_correction ADD COLUMN IF NOT EXISTS reviewer_id uuid;

COMMENT ON COLUMN emr_svc.coding_correction.requester_id IS
    '申请人账号主键（X-User-Id）；为空的历史申请不允许审批（四眼无法判定）';
COMMENT ON COLUMN emr_svc.coding_correction.reviewer_id IS
    '审批人账号主键；与 requester_id 相同即自审自批，服务层拒绝';

CREATE INDEX IF NOT EXISTS ix_coding_correction_requester
    ON emr_svc.coding_correction (requester_id);
