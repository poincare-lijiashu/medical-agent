-- ============================================================================
-- V73：传染病报告卡版本号从 1 起（§294 修正）
--
-- 新卡插入时未显式写 version，V72 的默认值是 0；而提交/受理接口按"版本必须 ≥1"
-- 做乐观锁校验。这里把默认值改为 1，并把已产生的 0 版本原地提升到 1，
-- 保证"新建卡可直接提交"的语义一致。
-- ============================================================================

ALTER TABLE clinical_svc.infection_report ALTER COLUMN version SET DEFAULT 1;
UPDATE clinical_svc.infection_report SET version = 1 WHERE version = 0;
