-- ============================================================================
-- V94：开放平台客户端（第 280 批 / 计划书 W9.3）
--
--   * 第三方系统（集成商 / 科研平台 / 集团报表）用 OAuth2 client_credentials
--     换取访问令牌，而不是拿一个人的账号密码去调接口；
--   * 第三方**只允许只读范围**：范围目录里没有任何写权限点，申请写权限直接拒绝——
--     这是"最小权限"在开放平台上的硬约束，不靠约定；
--   * 密钥只存 SHA-256 + 前缀提示，明文只在创建/轮换时返回一次；
--   * 令牌是短 TTL 的普通 JWT（默认 15 分钟），复用现有网关验签链路，
--     因此不需要为第三方单独建一套鉴权栈。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.oauth_client (
    id                uuid PRIMARY KEY,
    client_id         text NOT NULL UNIQUE,
    name              text NOT NULL,
    description       text,
    secret_hash       text NOT NULL,
    secret_hint       text NOT NULL,
    scopes            text[] NOT NULL,
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    token_ttl_seconds integer NOT NULL DEFAULT 900
                      CHECK (token_ttl_seconds BETWEEN 60 AND 86400),
    expires_at        timestamptz,
    last_used_at      timestamptz,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text,
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_oauth_client_status ON auth_svc.oauth_client (status, created_at DESC);
COMMENT ON TABLE auth_svc.oauth_client IS
    '开放平台客户端：scopes 只能是只读权限点；secret_hash 为 SHA-256，明文不落库';
COMMENT ON COLUMN auth_svc.oauth_client.secret_hint IS
    '运维识别用的前缀提示（如 cli_ab12…f3），不是密钥的一部分';

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('integration:client:read', '开放平台客户端查看', 'integration'),
    ('integration:client:manage', '开放平台客户端管理', 'integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'integration:client:read'),
    ('ADMIN', 'integration:client:manage'),
    ('OPS_MANAGER', 'integration:client:read')
ON CONFLICT DO NOTHING;
