package com.medassist.auth.repo;

import com.medassist.auth.domain.UserAccount;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号仓储（JdbcClient 显式 SQL）。
 *
 * <p>并发控制：所有更新走 version CAS（{@code WHERE version = ?}），返回受影响行数，
 * 调用方据 0 行判定冲突并抛 {@code CONFLICT}——不使用数据库行锁，避免长事务。
 */
@Repository
public class UserRepository {

    private static final String COLS = """
            id, username, password_hash, display_name, employee_no, org_code,
            status, version, last_login_at, created_at, updated_at
            """;

    private static final RowMapper<UserAccount> MAPPER = (ResultSet rs, int rowNum) -> new UserAccount(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("employee_no"),
            rs.getString("org_code"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.app_user WHERE id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    /** 用户名大小写不敏感查找（与唯一索引 lower(username) 一致）。 */
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.app_user WHERE lower(username) = lower(?)")
                .param(username)
                .query(MAPPER)
                .optional();
    }

    public boolean existsByUsername(String username) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.app_user WHERE lower(username) = lower(?)")
                .param(username)
                .query(Long.class)
                .single() > 0;
    }

    public List<UserAccount> list(String orgCode, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM auth_svc.app_user WHERE 1=1");
        if (orgCode != null && !orgCode.isBlank()) {
            sql.append(" AND org_code = :orgCode");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (orgCode != null && !orgCode.isBlank()) {
            spec = spec.param("orgCode", orgCode);
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public long count(String orgCode, String status) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM auth_svc.app_user WHERE 1=1");
        if (orgCode != null && !orgCode.isBlank()) {
            sql.append(" AND org_code = :orgCode");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (orgCode != null && !orgCode.isBlank()) {
            spec = spec.param("orgCode", orgCode);
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.query(Long.class).single();
    }

    public void insert(UserAccount u) {
        jdbc.sql("""
                        INSERT INTO auth_svc.app_user
                            (id, username, password_hash, display_name, employee_no, org_code, status, version)
                        VALUES (:id, :username, :passwordHash, :displayName, :employeeNo, :orgCode, :status, 0)
                        """)
                .param("id", u.id())
                .param("username", u.username())
                .param("passwordHash", u.passwordHash())
                .param("displayName", u.displayName())
                .param("employeeNo", u.employeeNo())
                .param("orgCode", u.orgCode())
                .param("status", u.status())
                .update();
    }

    /** 资料更新（CAS）；返回 false 表示版本冲突或记录不存在。 */
    public boolean updateProfile(UUID id, String displayName, String employeeNo,
                                 String orgCode, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE auth_svc.app_user
                           SET display_name = :displayName,
                               employee_no  = :employeeNo,
                               org_code     = :orgCode,
                               version      = version + 1,
                               updated_at   = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("displayName", displayName)
                .param("employeeNo", employeeNo)
                .param("orgCode", orgCode)
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 状态变更（CAS）。 */
    public boolean updateStatus(UUID id, String status, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE auth_svc.app_user
                           SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("status", status)
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 密码重置（不需要 CAS：由管理员显式操作，版本自增）。 */
    public boolean updatePassword(UUID id, String passwordHash) {
        return jdbc.sql("""
                        UPDATE auth_svc.app_user
                           SET password_hash = :hash, version = version + 1, updated_at = now()
                         WHERE id = :id
                        """)
                .param("hash", passwordHash)
                .param("id", id)
                .update() > 0;
    }

    /** 记录登录时间（登录成功后调用）。 */
    public void touchLogin(UUID id) {
        jdbc.sql("UPDATE auth_svc.app_user SET last_login_at = now() WHERE id = ?")
                .param(id)
                .update();
    }

    public List<String> rolesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT role_code FROM auth_svc.user_role
                         WHERE user_id = ? ORDER BY role_code
                        """)
                .param(userId)
                .query(String.class)
                .list();
    }

    /** 全量替换角色（先删后插，单事务由调用方保证）。 */
    public void replaceRoles(UUID userId, Collection<String> roleCodes) {
        jdbc.sql("DELETE FROM auth_svc.user_role WHERE user_id = ?").param(userId).update();
        for (String code : roleCodes) {
            jdbc.sql("INSERT INTO auth_svc.user_role (user_id, role_code) VALUES (?, ?)")
                    .param(userId)
                    .param(code)
                    .update();
        }
    }

    /** 统计启用状态的指定角色账号数（保护规则用；单条 SQL，避免 N+1 与分页截断误判）。 */
    public long countActiveByRole(String roleCode) {
        return jdbc.sql("""
                        SELECT count(DISTINCT u.id)
                          FROM auth_svc.app_user u
                          JOIN auth_svc.user_role ur ON ur.user_id = u.id
                         WHERE u.status = 'ACTIVE' AND ur.role_code = ?
                        """)
                .param(roleCode)
                .query(Long.class)
                .single();
    }

    /** 记录变更审计（auth 域自治日志）。 */
    public void logChange(String actor, String action, String targetType,
                          String targetId, String detailJson) {
        // 用 CAST(...) 而非 :detail::jsonb——命名参数后面紧跟 :: 会被解析成参数名的一部分
        jdbc.sql("""
                        INSERT INTO auth_svc.change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .update();
    }
}
