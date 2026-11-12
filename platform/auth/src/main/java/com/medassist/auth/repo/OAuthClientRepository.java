package com.medassist.auth.repo;

import com.medassist.auth.domain.OAuthClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 开放平台客户端仓储。
 *
 * <p>secret_hash 与可展示字段分开取：{@link StoredClient} 只在令牌端点内部使用，
 * 管理端接口永远不会把哈希带出去。
 */
@Repository
public class OAuthClientRepository {

    private static final String COLS = """
            id, client_id, name, description, secret_hint, scopes, status,
            token_ttl_seconds, expires_at, last_used_at, created_by, created_at, updated_by, updated_at
            """;

    private static final RowMapper<OAuthClient> MAPPER = (ResultSet rs, int row) -> new OAuthClient(
            rs.getObject("id", UUID.class),
            rs.getString("client_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("secret_hint"),
            stringList(rs, "scopes"),
            rs.getString("status"),
            rs.getInt("token_ttl_seconds"),
            instant(rs, "expires_at"),
            instant(rs, "last_used_at"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            rs.getString("updated_by"),
            instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public OAuthClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OAuthClient> list() {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.oauth_client ORDER BY created_at DESC")
                .query(MAPPER).list();
    }

    public Optional<OAuthClient> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.oauth_client WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public Optional<OAuthClient> findByClientId(String clientId) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.oauth_client WHERE client_id = ?")
                .param(clientId).query(MAPPER).optional();
    }

    /** 令牌端点专用：带出 secret_hash。 */
    public Optional<StoredClient> findForAuth(String clientId) {
        return jdbc.sql("SELECT " + COLS + ", secret_hash FROM auth_svc.oauth_client WHERE client_id = ?")
                .param(clientId)
                .query((rs, row) -> new StoredClient(MAPPER.mapRow(rs, row), rs.getString("secret_hash")))
                .optional();
    }

    public void insert(UUID id, String clientId, String name, String description, String secretHash,
                       String secretHint, List<String> scopes, int ttlSeconds, Instant expiresAt,
                       String actor) {
        jdbc.sql("""
                INSERT INTO auth_svc.oauth_client
                    (id, client_id, name, description, secret_hash, secret_hint, scopes,
                     status, token_ttl_seconds, expires_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS text[]), 'ACTIVE', ?, ?, ?, ?)
                """)
                .param(id).param(clientId).param(name).param(description)
                .param(secretHash).param(secretHint).param(pgTextArray(scopes))
                .param(ttlSeconds).param(expiresAt == null ? null : Timestamp.from(expiresAt))
                .param(actor).param(actor)
                .update();
    }

    public int updateSecret(UUID id, String secretHash, String secretHint, String actor) {
        return jdbc.sql("""
                UPDATE auth_svc.oauth_client
                   SET secret_hash = ?, secret_hint = ?, updated_by = ?, updated_at = now()
                 WHERE id = ?
                """).param(secretHash).param(secretHint).param(actor).param(id).update();
    }

    public int updateStatus(UUID id, String status, String actor) {
        return jdbc.sql("""
                UPDATE auth_svc.oauth_client
                   SET status = ?, updated_by = ?, updated_at = now()
                 WHERE id = ?
                """).param(status).param(actor).param(id).update();
    }

    public int updateScopes(UUID id, List<String> scopes, String actor) {
        return jdbc.sql("""
                UPDATE auth_svc.oauth_client
                   SET scopes = CAST(? AS text[]), updated_by = ?, updated_at = now()
                 WHERE id = ?
                """).param(pgTextArray(scopes)).param(actor).param(id).update();
    }

    public void touchUsed(UUID id) {
        jdbc.sql("UPDATE auth_svc.oauth_client SET last_used_at = now() WHERE id = ?").param(id).update();
    }

    /** 管理动作审计：与用户管理共用 auth_svc.change_log（追加式，触发器禁改）。 */
    public void audit(String actor, String action, String clientId, String detailJson) {
        jdbc.sql("""
                INSERT INTO auth_svc.change_log (actor, action, target_type, target_id, detail)
                VALUES (?, ?, 'OAUTH_CLIENT', ?, CAST(? AS jsonb))
                """)
                .param(actor == null ? "unknown" : actor)
                .param(action)
                .param(clientId)
                .param(detailJson == null || detailJson.isBlank() ? "{}" : detailJson)
                .update();
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** PG 数组字面量：scope 只含 [a-z0-9:_-]，无需转义。 */
    private static String pgTextArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values) + "}";
    }

    /** 令牌端点内部使用的组合视图（含哈希，绝不出 Controller）。 */
    public record StoredClient(OAuthClient client, String secretHash) {
    }
}
