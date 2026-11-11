package com.medassist.auth.repo;

import com.medassist.auth.web.dto.AuditActionOption;
import com.medassist.auth.web.dto.AuditEventResp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨域审计汇聚查询（只读）。
 *
 * <p>现状：各域的 change_log 在同一个 PostgreSQL 实例的不同 schema，直接 UNION ALL 即可形成
 * 统一审计视图（按时间倒序、支持 actor/action/targetType/targetId/时间窗过滤）。
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

    /** 已部署域的探测结果（运行期不会消失，只探测一次）。 */
    private volatile List<Source> deployedSources;

    /** 按过滤组合缓存的 UNION SQL（16 种组合：actor/action/targetType/targetId 各有无）。 */
    private final Map<Integer, String> sqlCache = new ConcurrentHashMap<>();

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventResp> search(String actor, String action, String targetType,
                                       String targetId, Instant from, Instant to,
                                       int limit, int offset) {
        String actorValue = orEmpty(actor);
        String actionValue = orEmpty(action);
        String targetValue = orEmpty(targetType);
        String targetIdValue = orEmpty(targetId);
        // id 决胜列：同一毫秒的多条事件（批量写入）若只按 ts 排序，翻页会出现重复/漏项
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT * FROM ("
                        + union(actorValue, actionValue, targetValue, targetIdValue)
                        + ") e ORDER BY e.ts DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to))
                .param("limit", limit)
                .param("offset", offset);
        if (!actorValue.isEmpty()) {
            spec = spec.param("actor", actorValue);
        }
        if (!actionValue.isEmpty()) {
            spec = spec.param("action", actionValue);
        }
        if (!targetValue.isEmpty()) {
            spec = spec.param("targetType", targetValue);
        }
        if (!targetIdValue.isEmpty()) {
            spec = spec.param("targetId", targetIdValue);
        }
        return spec.query((rs, i) -> new AuditEventResp(
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

    public long count(String actor, String action, String targetType, String targetId,
                      Instant from, Instant to) {
        String actorValue = orEmpty(actor);
        String actionValue = orEmpty(action);
        String targetValue = orEmpty(targetType);
        String targetIdValue = orEmpty(targetId);
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT count(*) FROM ("
                        + union(actorValue, actionValue, targetValue, targetIdValue) + ") e")
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to));
        if (!actorValue.isEmpty()) {
            spec = spec.param("actor", actorValue);
        }
        if (!actionValue.isEmpty()) {
            spec = spec.param("action", actionValue);
        }
        if (!targetValue.isEmpty()) {
            spec = spec.param("targetType", targetValue);
        }
        if (!targetIdValue.isEmpty()) {
            spec = spec.param("targetId", targetIdValue);
        }
        return spec.query(Long.class).single();
    }

    /**
     * 动作字典（第 243 批）：各域出现过的 action + 次数，用于筛选下拉。
     *
     * <p>为什么不做成枚举表：新增动作由各域代码决定，写一份静态清单必然过期；
     * 直接按时间窗 group 出实际出现过的动作，永远是“当前真实在写的东西”。
     * `limit` 由服务层收敛，避免动作爆炸时把整页拉满。
     */
    public List<AuditActionOption> actionCatalog(Instant from, Instant to, int limit) {
        return jdbc.sql("SELECT e.action AS action, count(*) AS n FROM ("
                        + union("", "", "", "") + ") e"
                        + """
                           GROUP BY e.action
                           ORDER BY n DESC, e.action
                           LIMIT :limit
                          """)
                .param("fromTs", Timestamp.from(from == null ? BEGINNING : from))
                .param("toTs", Timestamp.from(to == null ? END : to))
                .param("limit", limit)
                .query((rs, i) -> new AuditActionOption(rs.getString("action"), rs.getLong("n")))
                .list();
    }

    /**
     * 已部署域的 UNION 片段（按过滤组合缓存）。
     *
     * <p>空过滤条件**直接省略**而不是写成 {@code (:actor = '' OR actor = :actor)}：
     * 后者在 pgjdbc 的 generic plan 下无法做 Index Cond，会让 (actor, ts) 索引完全失效
     * （第四轮 SF-14）。
     *
     * <p>`targetId` 用于按单号/主键反查“谁动过这条记录”（如打印单据 `docType:docNo`）。
     */
    private String union(String actor, String action, String targetType, String targetId) {
        int key = (actor.isEmpty() ? 0 : 1) | (action.isEmpty() ? 0 : 2)
                | (targetType.isEmpty() ? 0 : 4) | (targetId.isEmpty() ? 0 : 8);
        return sqlCache.computeIfAbsent(key, k -> buildUnion(
                !actor.isEmpty(), !action.isEmpty(), !targetType.isEmpty(), !targetId.isEmpty()));
    }

    private String buildUnion(boolean filterActor, boolean filterAction, boolean filterTarget,
                              boolean filterTargetId) {
        StringBuilder sb = new StringBuilder();
        StringBuilder where = new StringBuilder(" WHERE ts >= :fromTs AND ts <= :toTs");
        if (filterActor) {
            where.append(" AND actor = :actor");
        }
        if (filterAction) {
            where.append(" AND action = :action");
        }
        if (filterTarget) {
            where.append(" AND target_type = :targetType");
        }
        if (filterTargetId) {
            where.append(" AND target_id = :targetId");
        }
        for (Source source : sources()) {
            if (sb.length() > 0) {
                sb.append("\nUNION ALL\n");
            }
            sb.append("""
                    SELECT '%s' AS source, id, ts, actor, action, target_type, target_id, detail::text AS detail
                      FROM %s
                    """.formatted(source.label(), source.table())).append(where);
        }
        if (sb.length() == 0) {
            // 连本服务自己的审计表都没有，说明迁移未完成：直接报错，不返回"看起来正常"的空结果
            throw new IllegalStateException("审计表不存在，请先执行 auth 服务数据库迁移");
        }
        return sb.toString();
    }

    /** 已部署域（懒加载 + 双检锁；探测只做一次）。 */
    private List<Source> sources() {
        List<Source> cached = deployedSources;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (deployedSources == null) {
                deployedSources = SOURCES.stream().filter(s -> tableExists(s.table())).toList();
            }
            return deployedSources;
        }
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
