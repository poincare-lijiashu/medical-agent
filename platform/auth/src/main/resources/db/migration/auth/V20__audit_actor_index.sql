-- ============================================================================
-- V20：审计按操作人检索索引（N-05）
--
-- 背景（第三轮评测）：统一审计查询 /api/v2/audit/events 支持按 actor 过滤，
-- 但三域审计表只有 (ts) 与 (target_type, target_id) 索引，按人检索只能全表扫描；
-- 三甲等保/内部审计的常见动作就是"查某人最近做了什么"，随表增长会拖慢同库业务。
--
-- 索引列序：等值列 actor 在前、排序/范围列 ts 在后，并对同毫秒批量写入用 id 决胜
-- （与 AuditRepository 的 ORDER BY ts DESC, id DESC 对齐）。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_auth_change_log_actor_ts
    ON auth_svc.change_log (actor, ts DESC, id DESC);
