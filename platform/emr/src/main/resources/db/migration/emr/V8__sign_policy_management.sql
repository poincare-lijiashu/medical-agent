-- ============================================================================
-- V8：签名策略可管理（N-04）
--
-- 背景（第三轮评测 S-05 后续）：sign_policy 决定"哪类文书归档前必须签到哪一级"，
-- 是电子病历分级评审的硬指标配置项，但此前只能改数据库。本迁移补管理能力所需字段：
--
--   * version    —— 乐观锁版本号，更新必须携带当前版本（"最后写赢"会静默降低审签要求）；
--   * updated_by —— 最近一次修改人，配合 emr_change_log 的 SIGN_POLICY_UPSERT 留痕。
--
-- 说明：已应用的 V2 初始化数据版本号为 0，首次修改携带 version=0 即可。
-- ============================================================================

ALTER TABLE emr_svc.sign_policy
    ADD COLUMN IF NOT EXISTS version    bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_by text;
