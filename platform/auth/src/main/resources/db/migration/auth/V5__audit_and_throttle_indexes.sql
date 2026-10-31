-- ============================================================================
-- P1 评测修正（M6 / S7）
--
-- 1) 登录限速查询用的是 lower(username) = lower(?)，
--    普通 btree (username, ts DESC) 无法命中，每次登录都会全表扫描 login_attempt
--    （保留 30 天，被刷时行数巨大）。补函数索引。
-- 2) 审计汇聚按 ts 倒序分页、支持时间窗过滤，change_log 需要 (ts, id) 索引；
--    分页加 id 决胜列后，索引也按 (ts DESC, id DESC) 建，避免同毫秒记录翻页漂移。
-- ============================================================================

CREATE INDEX IF NOT EXISTS ix_login_attempt_user_lower
    ON auth_svc.login_attempt (lower(username), ts DESC);

CREATE INDEX IF NOT EXISTS ix_auth_change_log_ts
    ON auth_svc.change_log (ts DESC, id DESC);
