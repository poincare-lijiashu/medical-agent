package com.medassist.auth.repo;

import com.medassist.auth.web.dto.AuditEventResp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 跨域审计汇聚查询（只读）。
 *
 * <p>现状：各域的 change_log 在同一个 PostgreSQL 实例的不同 schema，直接 UNION ALL 即可形成
 * 统一审计视图（按时间倒序、支持 actor/action/targetType/时间窗过滤）。
 *
 * <p><b>过滤参数约定</b>：空字符串表示"不限"，时间窗用 [epoch, 9999-12-31] 表示"不限"。
 * 全部条件都是普通比较（不用 {@code :x IS NULL}），避免 JDBC 无法推断 NULL 参数类型。
 *
 * <p><b>域发现</b>：单库多 schema 形态下，各域可能分阶段上线；固定拼接未迁移的 schema 会让
 * 审计接口在"只部署了部分服务"的环境整表报错。因此启动后首次查询时用 {@code to_regclass}
 * 探测实际存在的审计表，只拼接存在的域（白名单常量，无注入面），结果缓存复用。
 *
 * <p><b>部署边界</b>：若未来各域拆分到独立数据库，本查询需改为
 * ① PostgreSQL FDW 只读外表，或 ② 审计事件统一投递到 Loki/OLAP 后由查询层合并。
 * 当前单库形态下不引入额外组件，保证"审计可查"不依赖跨服务 RPC。
 */
@Repository
public class AuditRepository {

    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END = Instant.parse("9999-12-31T23:59:59Z");

    /** 一个审计源（schema 内表名与展示用来源标签）。 */
    private record Source(String label, String table) {
    }

    /** 各域 change_log 结构一致（id/ts/actor/action/target_type/target_id/detail），仅表名不同。 */
    private static final List<Source> SOURCES = List.of(
            new Source("auth", "auth_svc.change_log"),
            new Source("empi", "empi_svc.change_log"),
            new Source("clinical", "clinical_svc.clinical_change_log"),
            new Source("pharmacy", "pharmacy_svc.pharmacy_change_log"),
            new Source("tech", "tech_svc.tech_change_log"),
            new Source("emr", "emr_svc.emr_change_log"),
            new Source("insurance", "insurance_svc.insurance_change_log"));

    private final JdbcClient jdbc;

    /** 探测结果缓存（表结构在运行期不会消失，只需拼一次）。 */
    private volatile String unionCache;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventResp> search(String actor, String action, String targetType,
                                       Instant from, Instant to, int limit, int offset) {
        // id 决胜列：同一毫秒的多条事件（批量写入）若只按 ts 排序，翻页会出现重复/漏项
        return jdbc.sql("SELECT * FROM (" + union() + ") e ORDER BY e.ts DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .param("actor", orEmpty(actor))
                .param("action", orEmpty(action))
                .param("targetType", orEmpty(targetType))
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to))
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new AuditEventResp(
                        rs.getString("source"),
                        rs.getLong("id"),
                        rs.getTimestamp("ts").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("detail")))
                .list();
    }

    public long count(String actor, String action, String targetType, Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM (" + union() + ") e")
                .param("actor", orEmpty(actor))
                .param("action", orEmpty(action))
                .param("targetType", orEmpty(targetType))
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to))
                .query(Long.class)
                .single();
    }

    /** 已部署域的 UNION 片段（懒加载 + 双检锁，避免每次查询都探测 schema）。 */
    private String union() {
        String cached = unionCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (unionCache == null) {
                unionCache = buildUnion();
            }
            return unionCache;
        }
    }

    private String buildUnion() {
        StringBuilder sb = new StringBuilder();
        for (Source source : SOURCES) {
            if (!tableExists(source.table())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\nUNION ALL\n");
            }
            sb.append("""
                    SELECT '%s' AS source, id, ts, actor, action, target_type, target_id, detail::text AS detail
                      FROM %s
                     WHERE (:actor = '' OR actor = :actor)
                       AND (:action = '' OR action = :action)
                       AND (:targetType = '' OR target_type = :targetType)
                       AND ts >= :fromTs AND ts <= :toTs
                    """.formatted(source.label(), source.table()));
        }
        if (sb.length() == 0) {
            // 连本服务自己的审计表都没有，说明迁移未完成：直接报错，不返回"看起来正常"的空结果
            throw new IllegalStateException("审计表不存在，请先执行 auth 服务数据库迁移");
        }
        return sb.toString();
    }

    private boolean tableExists(String qualifiedName) {
        Boolean exists = jdbc.sql("SELECT to_regclass(?) IS NOT NULL")
                .param(qualifiedName).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
