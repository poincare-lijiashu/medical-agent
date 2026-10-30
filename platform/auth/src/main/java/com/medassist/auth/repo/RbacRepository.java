package com.medassist.auth.repo;

import com.medassist.auth.domain.Org;
import com.medassist.auth.domain.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色与组织架构只读仓储（配置数据变更频率低，全部走缓存友好的一次性查询）。
 */
@Repository
public class RbacRepository {

    private final JdbcClient jdbc;

    public RbacRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Role> listRoles() {
        return jdbc.sql("SELECT code, name, description, builtin FROM auth_svc.role ORDER BY code")
                .query((rs, i) -> new Role(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("builtin")))
                .list();
    }

    public Optional<Role> findRole(String code) {
        return jdbc.sql("SELECT code, name, description, builtin FROM auth_svc.role WHERE code = ?")
                .param(code)
                .query((rs, i) -> new Role(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("builtin")))
                .optional();
    }

    public List<Org> listOrgs() {
        return jdbc.sql("""
                        SELECT code, name, parent_code, type, status, sort_no
                          FROM auth_svc.org ORDER BY sort_no, code
                        """)
                .query((rs, i) -> new Org(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("parent_code"),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("sort_no")))
                .list();
    }

    public boolean orgExists(String code) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.org WHERE code = ? AND status = 'ACTIVE'")
                .param(code)
                .query(Long.class)
                .single() > 0;
    }
}
