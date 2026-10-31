-- ============================================================================
-- V5：影像数量上限（第五轮 R5-S8）
--
-- 背景：V3 只约束 series_count/image_count >= 0，API 层的 @Max 挡不住直连库或
-- 未来新增写入路径。给一个"大到不可能正常触发、小到能防脏值"的上限：
-- 单检查 1 万序列 / 100 万图像（实际 CT 约数百序列、数万图像）。
-- ============================================================================

ALTER TABLE tech_svc.pacs_study
    DROP CONSTRAINT IF EXISTS ck_pacs_counts_upper;

ALTER TABLE tech_svc.pacs_study
    ADD CONSTRAINT ck_pacs_counts_upper CHECK (series_count <= 10000 AND image_count <= 1000000);
