package com.medassist.auth.repo;

import com.medassist.auth.domain.Campus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 院区字典仓储（JdbcClient 显式 SQL）。
 *
 * <p>更新走 version CAS：院区维护是低频管理动作，但并发改名/停用仍可能发生，
 * 用 CAS 保证「最后写入者不会静默覆盖别人的修改」。
 */
@Repository
public class CampusRepository {

    private static final String COLS = "id, code, name, status, sort_no, version";

    private static final RowMapper<Campus> MAPPER = (ResultSet rs, int rowNum) -> new Campus(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getInt("sort_no"),
            rs.getLong("version")
    );

    private final JdbcClient jdbc;

    public CampusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部院区（含停用；管理端需要能重新启用，展示排序由 sort_no 决定）。 */
    public List<Campus> list() {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.campus ORDER BY sort_no, code")
                .query(MAPPER).list();
    }

    public Optional<Campus> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.campus WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /** 院区编码大小写不敏感查重（与唯一索引一致）。 */
    public boolean existsByCode(String code) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.campus WHERE lower(code) = lower(?)")
                .param(code).query(Long.class).single() > 0;
    }

    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM auth_svc.campus WHERE status = 'ACTIVE'")
                .query(Long.class).single();
    }

    public void insert(Campus c) {
        jdbc.sql("""
                        INSERT INTO auth_svc.campus (id, code, name, status, sort_no, version)
                        VALUES (:id, :code, :name, :status, :sortNo, 0)
                        """)
                .param("id", c.id())
                .param("code", c.code())
                .param("name", c.name())
                .param("status", c.status())
                .param("sortNo", c.sortNo())
                .update();
    }

    /** 名称/状态/排序更新（CAS）；返回 false 表示版本冲突或院区不存在。 */
    public boolean update(UUID id, String name, String status, int sortNo, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE auth_svc.campus
                           SET name = :name, status = :status, sort_no = :sortNo,
                               version = version + 1
                         WHERE id = :id AND version = :version
                        """)
                .param("name", name)
                .param("status", status)
                .param("sortNo", sortNo)
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    public void logChange(String actor, String action, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO auth_svc.change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, 'CAMPUS', :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .update();
    }
}
