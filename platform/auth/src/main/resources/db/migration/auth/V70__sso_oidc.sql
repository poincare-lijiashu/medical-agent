-- G42 统一身份 SSO（OIDC 授权码 + PKCE）
--
-- sso_login_state：一次性登录状态。state 同时作为 CSRF 令牌；code_verifier 用于 PKCE；
-- nonce 用于 id_token 重放防护。消费即标记 consumed_at，过期/已消费一律拒绝。
CREATE TABLE auth_svc.sso_login_state (
    state         text PRIMARY KEY,
    provider      text NOT NULL,
    code_verifier text NOT NULL,
    nonce         text NOT NULL,
    redirect_uri  text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    consumed_at   timestamptz,
    CONSTRAINT ck_sso_state_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_sso_login_state_expire ON auth_svc.sso_login_state (expires_at);

-- sso_identity：外部身份（provider + subject）与本地账号的绑定。
-- 同一外部身份只能绑定一个本地账号；同一账号在同一 provider 下只允许一个 subject，
-- 防止"换了 IdP 账号后静默接管原账号"。
CREATE TABLE auth_svc.sso_identity (
    id            uuid PRIMARY KEY,
    provider      text NOT NULL,
    subject       text NOT NULL,
    user_id       uuid NOT NULL REFERENCES auth_svc.app_user (id),
    email         text,
    display_name  text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz NOT NULL,
    CONSTRAINT uq_sso_identity_subject UNIQUE (provider, subject),
    CONSTRAINT uq_sso_identity_user UNIQUE (provider, user_id)
);

CREATE INDEX ix_sso_identity_user ON auth_svc.sso_identity (user_id);
