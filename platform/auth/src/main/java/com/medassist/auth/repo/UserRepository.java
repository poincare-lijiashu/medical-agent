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

    /**
     * 历史哈希在线升级（PBKDF2 → BCrypt）。
     *
     * <p>按旧哈希做 CAS：并发登录/管理员重置密码时只会有一次替换生效，
     * 且不推进 version（避免无谓的乐观锁冲突）。
     */
    public boolean upgradePasswordHash(UUID id, String oldHash, String newHash) {
        return jdbc.sql("""
                        UPDATE auth_svc.app_user
                           SET password_hash = :newHash, updated_at = now()
                         WHERE id = :id AND password_hash = :oldHash
                        """)
                .param("newHash", newHash)
                .param("oldHash", oldHash)
                .param("id", id)
                .update() > 0;
    }

    /** 记录登录时间（登录成功后调用）。 */
    public void touchLogin(UUID id) {
        jdbc.sql("UPDATE auth_svc.app_user SET last_login_at = now() WHERE id = ?")
                .param(id)
                .update();
    }

    /**
     * 账号绑定的临床科室（多科室执业）。
     *
     * <p>按绑定时间排序：签发令牌时第一个作为"主科室"用于兼容只认单值的旧服务。
     */
    public List<UUID> deptIdsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT dept_id FROM auth_svc.user_dept
                         WHERE user_id = ? ORDER BY created_at, dept_id
                        """)
                .param(userId)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .list();
    }

    /**
     * 账号可访问院区（多院区执业，G45）。
     *
     * <p>未绑定院区的历史账号回退主院区，保证令牌始终带 campuses 声明，
     * 下游不会因为缺头而退化成"跨院区可见"。
     */
    public List<UUID> campusIdsOf(UUID userId) {
        List<UUID> ids = jdbc.sql("""
                        SELECT uc.campus_id FROM auth_svc.user_campus uc
                          JOIN auth_svc.campus c ON c.id = uc.campus_id
                         WHERE uc.user_id = ? AND c.status = 'ACTIVE'
                         ORDER BY uc.created_at, uc.campus_id
                        """)
                .param(userId)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .list();
        return ids.isEmpty()
                ? List.of(com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID)
                : ids;
    }

    /**
     * 绑定的院区是否全部存在且为 ACTIVE（G64 / 评审 §6.9）。
     *
     * <p>停用院区的语义是"不再分配新业务数据"，因此不能再把账号绑过去。
     */
    public boolean allCampusesActive(java.util.Collection<UUID> campusIds) {
        if (campusIds == null || campusIds.isEmpty()) {
            return true;
        }
        String csv = campusIds.stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        Long active = jdbc.sql("""
                        SELECT count(*) FROM auth_svc.campus
                         WHERE id = ANY(string_to_array(:ids, ',')::uuid[])
                           AND status = 'ACTIVE'
                        """)
                .param("ids", csv)
                .query(Long.class).single();
        return active != null && active == campusIds.size();
    }

    /** 全量替换科室绑定（先删后插，单事务由调用方保证）。 */
    public void replaceDepts(UUID userId, Collection<UUID> deptIds) {
        jdbc.sql("DELETE FROM auth_svc.user_dept WHERE user_id = ?").param(userId).update();
        for (UUID deptId : deptIds) {
            jdbc.sql("INSERT INTO auth_svc.user_dept (user_id, dept_id) VALUES (?, ?)")
                    .param(userId)
                    .param(deptId)
                    .update();
        }
    }

    /** 全量替换院区绑定（多院区执业，单事务由调用方保证）。 */
    public void replaceCampuses(UUID userId, Collection<UUID> campusIds) {
        jdbc.sql("DELETE FROM auth_svc.user_campus WHERE user_id = ?").param(userId).update();
        for (UUID campusId : campusIds) {
            jdbc.sql("INSERT INTO auth_svc.user_campus (user_id, campus_id) VALUES (?, ?)")
                    .param(userId)
                    .param(campusId)
                    .update();
        }
    }

    /**
     * 组织编码 → 科室中文名（签发令牌时写入 {@code dept} 声明）。
     *
     * <p>AI 中台按中文科室名做数据范围与质控归属，本平台按 {@code org_code}（D001…）授权。
     * 两套编码必须在**身份源**这里收敛：令牌同时携带 {@code org}（编码）与 {@code dept}（中文名），
     * 下游就不需要维护第二份映射表。查不到（空编码/历史脏数据）返回 {@link Optional#empty()}，
     * 由下游按"无科室"处理——宁可少看，不做越权放大。
     */
    public Optional<String> orgNameOf(String orgCode) {
        if (orgCode == null || orgCode.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT name FROM auth_svc.org WHERE code = ?")
                .param(orgCode)
                .query(String.class)
                .optional()
                .map(String::trim)
                .filter(name -> !name.isEmpty());
    }

    /**
     * 非 ACTIVE 账号的用户名（停用/锁定）。
     *
     * <p>供 AI 中台增量同步：访问令牌是无状态的，停用账号后旧令牌在 TTL 内仍可验签通过；
     * 下游按本清单拒绝，才能做到"停用即下线"，而不是等令牌自然过期。
     */
    public List<String> nonActiveUsernames() {
        return jdbc.sql("SELECT username FROM auth_svc.app_user WHERE status <> 'ACTIVE' ORDER BY username")
                .query(String.class)
                .list();
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
