-- ============================================================================
-- V74：登录限速原子占位桶（G62，评审 §1.4）
--
-- 背景：原实现是"先 check（计数查询）→ bcrypt 校验（~100ms）→ 后 record"的两步，
-- 窗口内并发 N 个请求在任何 record 落库前都能通过 check，实际可尝试次数远超 maxFailures。
--
-- 本迁移引入按桶原子占位的计数表：
--   * bucket_key：u:<username>|<ip>（账号+IP 维度）/ i:<ip>（IP 维度）；
--   * claim 用 INSERT ... ON CONFLICT DO UPDATE 单语句自增（行锁天然串行），
--     返回自增后的次数，服务层据此判定是否超限；
--   * 登录成功时 refund 退还一次（成功不算失败），失败保留计数；
--   * window_start 超过窗口即重置，无需定时清理。
--
-- login_attempt 仍保留为审计明细（谁在何时失败），不再承担限速判定。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.login_throttle (
    bucket_key   text PRIMARY KEY,
    window_start timestamptz NOT NULL DEFAULT now(),
    failures     int NOT NULL DEFAULT 0 CHECK (failures >= 0),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth_svc.login_throttle IS
    '登录限速原子占位桶（G62）：claim 自增、成功退款、窗口过期自动重置';
COMMENT ON COLUMN auth_svc.login_throttle.bucket_key IS
    '桶键：u:<username>|<ip> 为账号+IP 维度，i:<ip> 为来源 IP 维度';
