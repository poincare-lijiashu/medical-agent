-- ============================================================================
-- V4：允许"取消后重做"同一张检查申请
--
-- 背景（第五轮 R5-S7）：V3 用 UNIQUE(application_id) 保证一申请一影像，
-- 但 CANCELLED 是终态，PACS 误取消（患者改约、设备故障重扫）后无法再次登记：
-- 同 UID 幂等返回一张已取消的记录，异 UID 报"已登记另一影像检查"，检查就此卡死。
--
-- 改法：把"全表唯一"放宽为"未取消记录唯一"（部分唯一索引），
-- 已取消的记录作为历史留档保留，新登记会产生一条新记录，审计可完整追溯。
-- ============================================================================

ALTER TABLE tech_svc.pacs_study
    DROP CONSTRAINT IF EXISTS pacs_study_application_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_pacs_study_active_application
    ON tech_svc.pacs_study (application_id)
    WHERE status <> 'CANCELLED';

COMMENT ON INDEX tech_svc.ux_pacs_study_active_application IS
    '同一检查申请同时只允许一条未取消的影像登记；CANCELLED 保留为历史，可重做';
