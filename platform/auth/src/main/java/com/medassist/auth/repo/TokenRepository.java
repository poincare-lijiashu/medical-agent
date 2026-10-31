package com.medassist.auth.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 令牌与登录尝试存储（刷新令牌 / 撤销名单 / 登录尝试）。
 *
 * <p>并发与一致性：
 * <ul>
 *   <li>刷新令牌轮换用 {@code UPDATE ... WHERE revoked_at IS NULL} 原子抢占，
 *       两个并发刷新只有一个能成功（防重放）；</li>
 *   <li>撤销名单按 {@code expires_at} 清理，网关只拉未过期项；</li>
 *   <li>登录失败计数直接落在 PG，多副本共享同一窗口，不依赖粘性会话。</li>
 * </ul>
 */
@Repository
public class TokenRepository {

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 刷新令牌

    public void insertRefresh(UUID id, UUID userId, String tokenHash, Instant expiresAt,
                              UUID rotatedFrom, String clientIp, String userAgent) {
        jdbc.sql("""
                        INSERT INTO auth_svc.refresh_token
                            (id, user_id, token_hash, expires_at, rotated_from, client_ip, user_agent)
                        VALUES (:id, :userId, :hash, :expiresAt, :rotatedFrom, :clientIp, :userAgent)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("rotatedFrom", rotatedFrom)
                .param("clientIp", clientIp)
                .param("userAgent", userAgent)
                .update();
    }

    /** 查找未撤销且未过期的刷新令牌（返回记录，供轮换）。 */
    public Optional<RefreshRecord> findActive(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, user_id, expires_at
                          FROM auth_svc.refresh_token
                         WHERE token_hash = :hash
                           AND revoked_at IS NULL
                           AND expires_at > now()
                        """)
                .param("hash", tokenHash)
                .query((rs, i) -> new RefreshRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /** 原子撤销（并发刷新只有一个成功）；返回 false 表示已被用过/撤销。 */
    public boolean revoke(UUID id) {
        return jdbc.sql("""
                        UPDATE auth_svc.refresh_token
                           SET revoked_at = now(), last_used_at = now()
                         WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("id", id)
                .update() > 0;
    }

    /** 撤销某账号全部刷新令牌（登出全部设备 / 停用账号）。 */
    public int revokeAllForUser(UUID userId) {
        return jdbc.sql("""
                        UPDATE auth_svc.refresh_token
                           SET revoked_at = now()
                         WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("userId", userId)
                .update();
    }

    public int purgeExpiredRefreshTokens() {
        return jdbc.sql("DELETE FROM auth_svc.refresh_token WHERE expires_at < now() - interval '7 days'")
                .update();
    }

    /**
     * 当前账号的活跃会话（= 未撤销、未过期的刷新令牌）。
     *
     * <p>每次刷新都会轮换并撤销旧行，因此这里返回的实际上是"每台设备一行"，
     * 不会随刷新次数膨胀。按最近使用时间倒序，便于前端展示"最近登录"。
     */
    public List<SessionRecord> listActiveSessions(UUID userId) {
        return jdbc.sql("""
                        SELECT id, client_ip, user_agent, issued_at, last_used_at, expires_at
                          FROM auth_svc.refresh_token
                         WHERE user_id = :userId
                           AND revoked_at IS NULL
                           AND expires_at > now()
                         ORDER BY COALESCE(last_used_at, issued_at) DESC
                        """)
                .param("userId", userId)
                .query((rs, i) -> new SessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("client_ip"),
                        rs.getString("user_agent"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    /** 撤销指定会话（限定本人，防越权踢别人设备）；返回 false 表示不存在/已撤销。 */
    public boolean revokeSession(UUID userId, UUID sessionId) {
        return jdbc.sql("""
                        UPDATE auth_svc.refresh_token
                           SET revoked_at = now(), last_used_at = now()
                         WHERE id = :id AND user_id = :userId AND revoked_at IS NULL
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 访问令牌撤销

    public void revokeJti(UUID jti, UUID userId, Instant expiresAt, String reason, String revokedBy) {
        // 同一 jti 重复登出幂等：冲突即忽略
        jdbc.sql("""
                        INSERT INTO auth_svc.token_revocation (jti, user_id, expires_at, reason, revoked_by)
                        VALUES (:jti, :userId, :expiresAt, :reason, :revokedBy)
                        ON CONFLICT (jti) DO NOTHING
                        """)
                .param("jti", jti)
                .param("userId", userId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("reason", reason)
                .param("revokedBy", revokedBy)
                .update();
    }

    /** 撤销名单增量查询（since 为上次同步时间；清理过期行后返回未过期项）。 */
    public List<RevocationRecord> listRevocationsSince(Instant since) {
        jdbc.sql("DELETE FROM auth_svc.token_revocation WHERE expires_at < now()").update();
        return jdbc.sql("""
                        SELECT jti, expires_at
                          FROM auth_svc.token_revocation
                         WHERE revoked_at > :since AND expires_at > now()
                         ORDER BY revoked_at
                        """)
                .param("since", java.sql.Timestamp.from(since))
                .query((rs, i) -> new RevocationRecord(
                        rs.getObject("jti", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- 登录尝试

    public void recordAttempt(String username, String clientIp, boolean success) {
        jdbc.sql("""
                        INSERT INTO auth_svc.login_attempt (username, client_ip, success)
                        VALUES (:username, :ip, :success)
                        """)
                .param("username", username)
                .param("ip", clientIp)
                .param("success", success)
                .update();
    }

    /** 窗口内同一账号的失败次数。 */
    public long countRecentFailuresForUser(String username, Instant since) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM auth_svc.login_attempt
                         WHERE ts >= :since
                           AND success = false
                           AND lower(username) = lower(:username)
                        """)
                .param("since", java.sql.Timestamp.from(since))
                .param("username", username)
                .query(Long.class)
                .single();
    }

    /** 窗口内同一来源 IP 的失败次数（阈值应远高于账号维度，防 NAT 出口连坐）。 */
    public long countRecentFailuresForIp(String clientIp, Instant since) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM auth_svc.login_attempt
                         WHERE ts >= :since
                           AND success = false
                           AND client_ip = :ip
                        """)
                .param("since", java.sql.Timestamp.from(since))
                .param("ip", clientIp)
                .query(Long.class)
                .single();
    }

    public int purgeOldAttempts(Instant before) {
        return jdbc.sql("DELETE FROM auth_svc.login_attempt WHERE ts < :before")
                .param("before", java.sql.Timestamp.from(before))
                .update();
    }

    public record RefreshRecord(UUID id, UUID userId, Instant expiresAt) {
    }

    public record RevocationRecord(UUID jti, Instant expiresAt) {
    }

    public record SessionRecord(UUID id, String clientIp, String userAgent,
                                Instant issuedAt, Instant lastUsedAt, Instant expiresAt) {
    }
}
