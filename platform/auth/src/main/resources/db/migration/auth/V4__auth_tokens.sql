-- ============================================================================
-- P1：刷新令牌、令牌撤销、登录尝试（限速依据）
--
-- 设计要点：
--   * 刷新令牌只存 SHA-256 哈希（数据库泄露不可直接使用）；
--   * 刷新即轮换（rotated_from 留痕，旧的立即 revoke），支持盗用检测；
--   * 访问令牌无状态，撤销靠 jti 黑名单：网关每 15s 增量拉取一次，
--     登出/停用后最迟 15s 生效（不做逐请求内省，避免把网关变成 auth 的同步依赖）；
--   * 登录限流按 username + client_ip 统计最近失败次数，跨副本一致（PG 计数）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.refresh_token (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES auth_svc.app_user (id) ON DELETE CASCADE,
    token_hash   text NOT NULL UNIQUE,
    issued_at    timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    rotated_from uuid,
    last_used_at timestamptz,
    client_ip    text,
    user_agent   text
);
CREATE INDEX IF NOT EXISTS ix_refresh_token_user
    ON auth_svc.refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_refresh_token_exp
    ON auth_svc.refresh_token (expires_at);
COMMENT ON TABLE auth_svc.refresh_token IS '刷新令牌（只存哈希；轮换式，旧令牌立即失效）';

CREATE TABLE IF NOT EXISTS auth_svc.token_revocation (
    jti        uuid PRIMARY KEY,
    user_id    uuid,
    expires_at timestamptz NOT NULL,
    reason     text NOT NULL,
    revoked_at timestamptz NOT NULL DEFAULT now(),
    revoked_by text
);
CREATE INDEX IF NOT EXISTS ix_token_revocation_exp
    ON auth_svc.token_revocation (expires_at);
COMMENT ON TABLE auth_svc.token_revocation IS '访问令牌撤销名单（jti），网关增量同步';

CREATE TABLE IF NOT EXISTS auth_svc.login_attempt (
    id        bigserial PRIMARY KEY,
    username  text NOT NULL,
    client_ip text NOT NULL,
    success   boolean NOT NULL,
    ts        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_login_attempt_user
    ON auth_svc.login_attempt (username, ts DESC);
CREATE INDEX IF NOT EXISTS ix_login_attempt_ip
    ON auth_svc.login_attempt (client_ip, ts DESC);
COMMENT ON TABLE auth_svc.login_attempt IS '登录尝试（成功/失败），用于暴力破解限速与审计';
