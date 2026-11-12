package com.medassist.auth.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Cell 迁移台账仓储（§282）。
 *
 * <p>只做两件事：写入"某院某服务跑到了哪版"，以及查询最新一版。
 * 版本事实来自各 Cell 的 {@code flyway_schema_history}，不是人工登记——
 * 手工维护的台账一定会和真实库不一致，且在事故中最不可信。
 */
@Repository
public class CellMigrationRepository {

    private final JdbcClient jdbc;

    public CellMigrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 每服务最新一条迁移记录。
     *
     * <p>{@code DISTINCT ON (service)} 取"版本号最大的一条"：Flyway 的 version 是字符串，
     * 这里按 {@code installed_on} + {@code id} 排序，避免 9.10 与 10.0 的字符串比较陷阱。
     */
    public List<CellMigration> latestByService(String tenantCode) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (service)
                               service, version, description, success, installed_on
                          FROM auth_svc.cell_migration
                         WHERE tenant_code = :tenantCode
                         ORDER BY service,
                                  installed_on DESC NULLS LAST,
                                  id DESC
                        """)
                .param("tenantCode", tenantCode)
                .query((rs, i) -> new CellMigration(
                        rs.getString("service"),
                        rs.getString("version"),
                        rs.getString("description"),
                        rs.getBoolean("success"),
                        rs.getTimestamp("installed_on") == null
                                ? null : rs.getTimestamp("installed_on").toInstant()))
                .list();
    }

    /** 是否存在失败的迁移（失败必须显式暴露，不能只看"有记录=已迁移"）。 */
    public boolean hasFailure(String tenantCode) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.cell_migration WHERE tenant_code = ? AND success = false")
                .param(tenantCode)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 幂等写入一条迁移事实。
     *
     * <p>重复同步不改历史：同一 (租户, 服务, 版本) 只保留第一次记录，
     * 并用 {@code success = false} 覆盖为"曾经失败过"——失败不能被后来的成功抹掉。
     */
    public void upsert(String tenantCode, String service, String version, String description,
                       Integer checksum, Instant installedOn, boolean success) {
        jdbc.sql("""
                        INSERT INTO auth_svc.cell_migration
                            (tenant_code, service, version, description, checksum, installed_on, success)
                        VALUES (:tenantCode, :service, :version, :description, :checksum,
                                :installedOn, :success)
                        ON CONFLICT (tenant_code, service, version) DO UPDATE
                           SET success = auth_svc.cell_migration.success AND EXCLUDED.success,
                               installed_on = COALESCE(auth_svc.cell_migration.installed_on,
                                                       EXCLUDED.installed_on)
                        """)
                .param("tenantCode", tenantCode)
                .param("service", service)
                .param("version", version)
                .param("description", description)
                .param("checksum", checksum)
                .param("installedOn", installedOn == null ? null : java.sql.Timestamp.from(installedOn))
                .param("success", success)
                .update();
    }

    /** 迁移事实视图。 */
    public record CellMigration(String service, String version, String description,
                                boolean success, Instant installedOn) {
    }
}
