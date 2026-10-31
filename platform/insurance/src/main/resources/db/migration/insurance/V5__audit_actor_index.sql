-- ============================================================================
-- V5：审计按操作人检索索引（N-05）
--
-- 统一审计查询按 actor + 时间范围检索（AuditRepository），
-- 本表原先只有 (ts) 与 (target_type, target_id) 索引，按人查只能全表扫描。
-- 医保资金相关操作（确认/作废/目录变更）是审计高频检索对象，单独建索引。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_ins_change_actor_ts
    ON insurance_svc.insurance_change_log (actor, ts DESC);
