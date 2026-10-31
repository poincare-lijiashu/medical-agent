-- ============================================================================
-- P1 评测修正（S7）：审计汇聚按时间倒序分页
--
-- AuditRepository 对 empi_svc.change_log 做 UNION ALL + ORDER BY ts DESC, id DESC，
-- 原表只有 (target_type, target_id) 索引，时间维度查询需全表排序。补时间索引。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_empi_change_log_ts
    ON empi_svc.change_log (ts DESC, id DESC);
