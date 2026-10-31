-- ============================================================================
-- V9：审计按操作人检索索引（N-05）
--
-- 统一审计查询按 actor + 时间范围检索（AuditRepository 的
-- "WHERE (:actor = '' OR actor = :actor) ... ts >= :fromTs ORDER BY ts DESC"），
-- 本表原先只有 (ts) 与 (target_type, target_id) 索引，按人查只能全表扫描。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_emr_change_actor_ts
    ON emr_svc.emr_change_log (actor, ts DESC);
