-- ============================================================================
-- 病历域 V14：病案首页四眼归档的操作者主键（第六轮 R6-MF-13）
--
-- 背景：归档过去只比对 username，账号重建、共享账号即可"自证不同人"；
-- 且 submitted_by 为空时 fail-open（历史数据/直连 SQL/导入路径都会命中）。
-- 本迁移记录提交时的 X-User-Id，使"提交人 ≠ 归档人"可以按不可变主键判定。
-- ============================================================================

ALTER TABLE emr_svc.record_homepage
    ADD COLUMN IF NOT EXISTS submitted_by_id uuid;

COMMENT ON COLUMN emr_svc.record_homepage.submitted_by_id IS
    '提交人账号主键（网关注入的 X-User-Id）；归档时按该主键做四眼校验，缺失即拒绝';

-- 历史数据没有主键：明确标记为"不可自动判定四眼"，由应用层拒绝归档并提示重提
CREATE INDEX IF NOT EXISTS ix_homepage_submitted_by_id
    ON emr_svc.record_homepage (submitted_by_id)
    WHERE submitted_by_id IS NOT NULL;
