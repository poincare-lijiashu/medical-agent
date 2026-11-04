package com.medassist.auth.repo;

import com.medassist.auth.domain.SsoIdentity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * SSO 登录状态与外部身份绑定仓储（G42）。
 *
 * <p>状态消费用单条 {@code UPDATE ... RETURNING} 原子完成：并发回调只有一个能拿到
 * verifier/nonce，其余全部按"状态失效"拒绝，等价于一次性票据。
 */
@Repository
public class SsoRepository {

    private static final RowMapper<LoginState> STATE_MAPPER = (rs, i) -> new LoginState(
            rs.getString("state"),
            rs.getString("provider"),
            rs.getString("code_verifier"),
            rs.getString("nonce"),
            rs.getString("redirect_uri"));

    private static final RowMapper<SsoIdentity> IDENTITY_MAPPER = (rs, i) -> new SsoIdentity(
            rs.getObject("id", UUID.class),
            rs.getString("provider"),
            rs.getString("subject"),
            rs.getObject("user_id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_login_at").toInstant());

    private final JdbcClient jdbc;

    public SsoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- state

    public void insertState(String state, String provider, String codeVerifier, String nonce,
                            String redirectUri, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth_svc.sso_login_state
                            (state, provider, code_verifier, nonce, redirect_uri, expires_at)
                        VALUES (:state, :provider, :verifier, :nonce, :redirectUri, :expiresAt)
                        """)
                .param("state", state)
                .param("provider", provider)
                .param("verifier", codeVerifier)
                .param("nonce", nonce)
                .param("redirectUri", redirectUri)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * 原子消费登录状态：只有未消费且未过期的 state 会返回记录。
     */
    public Optional<LoginState> consumeState(String state) {
        return jdbc.sql("""
                        UPDATE auth_svc.sso_login_state
                           SET consumed_at = now()
                         WHERE state = :state
                           AND consumed_at IS NULL
                           AND expires_at > now()
                        RETURNING state, provider, code_verifier, nonce, redirect_uri
                        """)
                .param("state", state)
                .query(STATE_MAPPER)
                .optional();
    }

    /** 清理过期或已消费超过一天的状态，避免表无限增长。 */
    public int purgeStates() {
        return jdbc.sql("""
                        DELETE FROM auth_svc.sso_login_state
                         WHERE expires_at < now() - interval '1 day'
                            OR consumed_at < now() - interval '1 day'
                        """)
                .update();
    }

    // ---------------------------------------------------------------- identity

    public Optional<SsoIdentity> findIdentity(String provider, String subject) {
        return jdbc.sql("""
                        SELECT id, provider, subject, user_id, email, display_name, created_at, last_login_at
                          FROM auth_svc.sso_identity
                         WHERE provider = :provider AND subject = :subject
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query(IDENTITY_MAPPER)
                .optional();
    }

    public Optional<SsoIdentity> findIdentityByUser(String provider, UUID userId) {
        return jdbc.sql("""
                        SELECT id, provider, subject, user_id, email, display_name, created_at, last_login_at
                          FROM auth_svc.sso_identity
                         WHERE provider = :provider AND user_id = :userId
                        """)
                .param("provider", provider)
                .param("userId", userId)
                .query(IDENTITY_MAPPER)
                .optional();
    }

    /** 首次绑定或刷新最近登录信息；{@code provider + subject} 冲突时只更新资料。 */
    public void upsertIdentity(SsoIdentity identity) {
        jdbc.sql("""
                        INSERT INTO auth_svc.sso_identity
                            (id, provider, subject, user_id, email, display_name, last_login_at)
                        VALUES (:id, :provider, :subject, :userId, :email, :displayName, now())
                        ON CONFLICT (provider, subject) DO UPDATE
                           SET email = COALESCE(EXCLUDED.email, auth_svc.sso_identity.email),
                               display_name = COALESCE(EXCLUDED.display_name, auth_svc.sso_identity.display_name),
                               last_login_at = now()
                        """)
                .param("id", identity.id())
                .param("provider", identity.provider())
                .param("subject", identity.subject())
                .param("userId", identity.userId())
                .param("email", identity.email())
                .param("displayName", identity.displayName())
                .update();
    }

    /** 登录状态（一次性）。 */
    public record LoginState(String state, String provider, String codeVerifier,
                             String nonce, String redirectUri) {
    }
}
