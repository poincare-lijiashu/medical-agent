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
 * <p>现状：auth / empi 的 {@code change_log} 在同一个 PostgreSQL 实例的不同 schema，
 * 直接 UNION ALL 即可形成统一审计视图（按时间倒序、支持 actor/action/targetType/时间窗过滤）。
 *
 * <p><b>过滤参数约定</b>：空字符串表示"不限"，时间窗用 [epoch, 9999-12-31] 表示"不限"。
 * 全部条件都是普通比较（不用 {@code :x IS NULL}），避免 JDBC 无法推断 NULL 参数类型。
 *
 * <p><b>部署边界</b>：若未来各域拆分到独立数据库，本查询需改为
 * ① PostgreSQL FDW 只读外表，或 ② 审计事件统一投递到 Loki/OLAP 后由查询层合并。
 * 当前单库形态下不引入额外组件，保证"审计可查"不依赖跨服务 RPC。
 */
@Repository
public class AuditRepository {

    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END = Instant.parse("9999-12-31T23:59:59Z");

    /** 各域的 change_log 结构一致（source, id, ts, actor, action, target_type, target_id, detail）。 */
    private static final String UNION = """
            SELECT 'auth' AS source, id, ts, actor, action, target_type, target_id, detail::text AS detail
              FROM auth_svc.change_log
             WHERE (:actor = '' OR actor = :actor)
               AND (:action = '' OR action = :action)
               AND (:targetType = '' OR target_type = :targetType)
               AND ts >= :fromTs AND ts <= :toTs
            UNION ALL
            SELECT 'empi' AS source, id, ts, actor, action, target_type, target_id, detail::text AS detail
              FROM empi_svc.change_log
             WHERE (:actor = '' OR actor = :actor)
               AND (:action = '' OR action = :action)
               AND (:targetType = '' OR target_type = :targetType)
               AND ts >= :fromTs AND ts <= :toTs
            """;

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventResp> search(String actor, String action, String targetType,
                                       Instant from, Instant to, int limit, int offset) {
        return jdbc.sql("SELECT * FROM (" + UNION + ") e ORDER BY e.ts DESC LIMIT :limit OFFSET :offset")
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
        return jdbc.sql("SELECT count(*) FROM (" + UNION + ") e")
                .param("actor", orEmpty(actor))
                .param("action", orEmpty(action))
                .param("targetType", orEmpty(targetType))
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to))
                .query(Long.class)
                .single();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
