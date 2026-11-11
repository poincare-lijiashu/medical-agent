package com.medassist.ops.repo;

import com.medassist.ops.domain.OpsModel;
import com.medassist.ops.domain.OpsModel.AuditLog;
import com.medassist.ops.domain.OpsModel.Calendar;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.SlaPolicy;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.domain.OpsModel.TicketEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 运营仓储（ops_svc 读写）。
 *
 * <p>纪律：
 * <ul>
 *   <li>工单状态推进必须带 {@code version} 做 CAS，避免两个人同时"解决"覆盖彼此；</li>
 *   <li>轨迹/审计表只 INSERT（数据库触发器兜底）；</li>
 *   <li>看板统计在 SQL 内完成，不把全院工单拉到内存里算。</li>
 * </ul>
 */
@Repository
public class OpsRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public OpsRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 日历

    private static final RowMapper<Calendar> CALENDAR_MAPPER = (rs, n) -> new Calendar(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("timezone"),
            intList(rs, "workdays"),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            dateList(rs, "holidays"),
            rs.getString("status"),
            rs.getLong("version"));

    public List<Calendar> listCalendars() {
        return plain.query("""
                SELECT code, name, timezone, workdays, start_time, end_time, holidays, status, version
                  FROM ops_svc.ops_calendar ORDER BY code
                """, CALENDAR_MAPPER);
    }

    public Optional<Calendar> findCalendar(String code) {
        return plain.query("""
                SELECT code, name, timezone, workdays, start_time, end_time, holidays, status, version
                  FROM ops_svc.ops_calendar WHERE code = ?
                """, CALENDAR_MAPPER, code).stream().findFirst();
    }

    public void upsertCalendar(Calendar calendar) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_calendar
                    (code, name, timezone, workdays, start_time, end_time, holidays, status, version)
                VALUES (:code, :name, :timezone, CAST(:workdays AS smallint[]), :startTime, :endTime,
                        CAST(:holidays AS date[]), :status, 0)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, timezone = EXCLUDED.timezone,
                    workdays = EXCLUDED.workdays, start_time = EXCLUDED.start_time,
                    end_time = EXCLUDED.end_time, holidays = EXCLUDED.holidays,
                    status = EXCLUDED.status, version = ops_svc.ops_calendar.version + 1,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("code", calendar.code())
                .addValue("name", calendar.name())
                .addValue("timezone", calendar.timezone())
                .addValue("workdays", arrayLiteral(calendar.workdays()))
                .addValue("startTime", calendar.startTime())
                .addValue("endTime", calendar.endTime())
                .addValue("holidays", dateArrayLiteral(calendar.holidays()))
                .addValue("status", calendar.status()));
    }

    // ------------------------------------------------------------------ 服务目录

    private static final RowMapper<ServiceCatalog> SERVICE_MAPPER = (rs, n) -> new ServiceCatalog(
            rs.getObject("id", UUID.class),
            rs.getString("service_code"),
            rs.getString("name"),
            rs.getString("domain"),
            rs.getString("owner"),
            rs.getString("support_group"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getInt("sort_no"),
            rs.getLong("version"));

    public List<ServiceCatalog> listServices(boolean onlyActive) {
        return plain.query("""
                SELECT id, service_code, name, domain, owner, support_group, description,
                       status, sort_no, version
                  FROM ops_svc.ops_service_catalog
                """ + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY sort_no, service_code", SERVICE_MAPPER);
    }

    public Optional<ServiceCatalog> findService(String code) {
        return plain.query("""
                SELECT id, service_code, name, domain, owner, support_group, description,
                       status, sort_no, version
                  FROM ops_svc.ops_service_catalog WHERE service_code = ?
                """, SERVICE_MAPPER, code).stream().findFirst();
    }

    public void upsertService(ServiceCatalog service) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_service_catalog
                    (id, service_code, name, domain, owner, support_group, description, status, sort_no)
                VALUES (:id, :code, :name, :domain, :owner, :group, :description, :status, :sortNo)
                ON CONFLICT (service_code) DO UPDATE SET
                    name = EXCLUDED.name, domain = EXCLUDED.domain, owner = EXCLUDED.owner,
                    support_group = EXCLUDED.support_group, description = EXCLUDED.description,
                    status = EXCLUDED.status, sort_no = EXCLUDED.sort_no,
                    version = ops_svc.ops_service_catalog.version + 1, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", service.id() == null ? UUID.randomUUID() : service.id())
                .addValue("code", service.serviceCode())
                .addValue("name", service.name())
                .addValue("domain", service.domain())
                .addValue("owner", service.owner())
                .addValue("group", service.supportGroup())
                .addValue("description", service.description())
                .addValue("status", service.status())
                .addValue("sortNo", service.sortNo()));
    }

    // ------------------------------------------------------------------ SLA

    private static final RowMapper<SlaPolicy> POLICY_MAPPER = (rs, n) -> new SlaPolicy(
            rs.getObject("id", UUID.class),
            rs.getString("service_code"),
            rs.getString("priority"),
            rs.getInt("response_minutes"),
            rs.getInt("resolve_minutes"),
            rs.getInt("escalation_minutes"),
            rs.getBoolean("business_hours"),
            rs.getString("calendar_code"),
            rs.getString("status"),
            rs.getLong("version"));

    public List<SlaPolicy> listPolicies(String serviceCode) {
        String sql = """
                SELECT id, service_code, priority, response_minutes, resolve_minutes,
                       escalation_minutes, business_hours, calendar_code, status, version
                  FROM ops_svc.ops_sla_policy
                """ + (serviceCode == null || serviceCode.isBlank() ? "" : " WHERE service_code = :code")
                + " ORDER BY service_code, priority";
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (serviceCode != null && !serviceCode.isBlank()) {
            params.addValue("code", serviceCode);
        }
        return jdbc.query(sql, params, POLICY_MAPPER);
    }

    public Optional<SlaPolicy> findPolicy(String serviceCode, String priority) {
        return plain.query("""
                SELECT id, service_code, priority, response_minutes, resolve_minutes,
                       escalation_minutes, business_hours, calendar_code, status, version
                  FROM ops_svc.ops_sla_policy WHERE service_code = ? AND priority = ?
                """, POLICY_MAPPER, serviceCode, priority).stream().findFirst();
    }

    public Optional<SlaPolicy> findPolicyById(UUID id) {
        return plain.query("""
                SELECT id, service_code, priority, response_minutes, resolve_minutes,
                       escalation_minutes, business_hours, calendar_code, status, version
                  FROM ops_svc.ops_sla_policy WHERE id = ?
                """, POLICY_MAPPER, id).stream().findFirst();
    }

    public SlaPolicy upsertPolicy(SlaPolicy policy) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_sla_policy
                    (id, service_code, priority, response_minutes, resolve_minutes,
                     escalation_minutes, business_hours, calendar_code, status)
                VALUES (:id, :service, :priority, :response, :resolve, :escalation,
                        :businessHours, :calendar, :status)
                ON CONFLICT (service_code, priority) DO UPDATE SET
                    response_minutes = EXCLUDED.response_minutes,
                    resolve_minutes = EXCLUDED.resolve_minutes,
                    escalation_minutes = EXCLUDED.escalation_minutes,
                    business_hours = EXCLUDED.business_hours,
                    calendar_code = EXCLUDED.calendar_code,
                    status = EXCLUDED.status,
                    version = ops_svc.ops_sla_policy.version + 1,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", policy.id() == null ? UUID.randomUUID() : policy.id())
                .addValue("service", policy.serviceCode())
                .addValue("priority", policy.priority())
                .addValue("response", policy.responseMinutes())
                .addValue("resolve", policy.resolveMinutes())
                .addValue("escalation", policy.escalationMinutes())
                .addValue("businessHours", policy.businessHours())
                .addValue("calendar", policy.calendarCode())
                .addValue("status", policy.status()));
        return findPolicy(policy.serviceCode(), policy.priority())
                .orElseThrow(() -> new IllegalStateException("SLA 策略写入后读取失败"));
    }

    // ------------------------------------------------------------------ 工单

    private static final String TICKET_COLUMNS = """
            id, ticket_no, title, description, service_code, priority, reporter, reporter_dept,
            assignee, assignee_group, status, source, sla_policy_id, response_due_at,
            resolve_due_at, first_response_at, resolved_at, closed_at, reopened_at, reopen_count,
            pending_reason, sla_paused_at, sla_paused_total_minutes, satisfaction, resolution,
            tags, sla_response_breached, sla_resolve_breached, version, created_at, updated_at
            """;

    private static final RowMapper<Ticket> TICKET_MAPPER = (rs, n) -> new Ticket(
            rs.getObject("id", UUID.class),
            rs.getString("ticket_no"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("service_code"),
            rs.getString("priority"),
            rs.getString("reporter"),
            rs.getString("reporter_dept"),
            rs.getString("assignee"),
            rs.getString("assignee_group"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getObject("sla_policy_id", UUID.class),
            instant(rs, "response_due_at"),
            instant(rs, "resolve_due_at"),
            instant(rs, "first_response_at"),
            instant(rs, "resolved_at"),
            instant(rs, "closed_at"),
            instant(rs, "reopened_at"),
            rs.getInt("reopen_count"),
            rs.getString("pending_reason"),
            instant(rs, "sla_paused_at"),
            rs.getInt("sla_paused_total_minutes"),
            (Integer) rs.getObject("satisfaction"),
            rs.getString("resolution"),
            stringList(rs, "tags"),
            rs.getBoolean("sla_response_breached"),
            rs.getBoolean("sla_resolve_breached"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of(), "ON_TRACK", "ON_TRACK");

    /** 工单检索条件。 */
    public record TicketFilter(String status, String priority, String serviceCode,
                               String assignee, String keyword, Boolean overdue) {
    }

    public Optional<Ticket> findTicket(UUID id) {
        return plain.query("SELECT " + TICKET_COLUMNS + " FROM ops_svc.ops_ticket WHERE id = ?",
                TICKET_MAPPER, id).stream().findFirst();
    }

    public Optional<Ticket> findTicketByNo(String ticketNo) {
        return plain.query("SELECT " + TICKET_COLUMNS + " FROM ops_svc.ops_ticket WHERE ticket_no = ?",
                TICKET_MAPPER, ticketNo).stream().findFirst();
    }

    public List<Ticket> listTickets(TicketFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + TICKET_COLUMNS
                + " FROM ops_svc.ops_ticket WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTicketWhere(sql, params, filter);
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, TICKET_MAPPER);
    }

    public long countTickets(TicketFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ops_svc.ops_ticket WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTicketWhere(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    private void appendTicketWhere(StringBuilder sql, MapSqlParameterSource params,
                                   TicketFilter filter) {
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.priority() != null && !filter.priority().isBlank()) {
            sql.append(" AND priority = :priority");
            params.addValue("priority", filter.priority());
        }
        if (filter.serviceCode() != null && !filter.serviceCode().isBlank()) {
            sql.append(" AND service_code = :service");
            params.addValue("service", filter.serviceCode());
        }
        if (filter.assignee() != null && !filter.assignee().isBlank()) {
            sql.append(" AND assignee = :assignee");
            params.addValue("assignee", filter.assignee());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND (ticket_no ILIKE :kw OR title ILIKE :kw OR coalesce(description, '') ILIKE :kw)");
            params.addValue("kw", "%" + filter.keyword().trim() + "%");
        }
        if (Boolean.TRUE.equals(filter.overdue())) {
            sql.append(" AND ((response_due_at < now() AND first_response_at IS NULL"
                    + " AND NOT sla_response_breached)"
                    + " OR (resolve_due_at < now() AND NOT sla_resolve_breached"
                    + " AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')))");
        }
    }

    public void insertTicket(Ticket ticket) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_ticket
                    (id, ticket_no, title, description, service_code, priority, reporter,
                     reporter_dept, assignee, assignee_group, status, source, sla_policy_id,
                     response_due_at, resolve_due_at, tags)
                VALUES
                    (:id, :no, :title, :description, :service, :priority, :reporter,
                     :reporterDept, :assignee, :group, :status, :source, :policy,
                     :responseDue, :resolveDue, CAST(:tags AS text[]))
                """, new MapSqlParameterSource()
                .addValue("id", ticket.id())
                .addValue("no", ticket.ticketNo())
                .addValue("title", ticket.title())
                .addValue("description", ticket.description())
                .addValue("service", ticket.serviceCode())
                .addValue("priority", ticket.priority())
                .addValue("reporter", ticket.reporter())
                .addValue("reporterDept", ticket.reporterDept())
                .addValue("assignee", ticket.assignee())
                .addValue("group", ticket.assigneeGroup())
                .addValue("status", ticket.status())
                .addValue("source", ticket.source())
                .addValue("policy", ticket.slaPolicyId())
                .addValue("responseDue", timestamp(ticket.responseDueAt()))
                .addValue("resolveDue", timestamp(ticket.resolveDueAt()))
                .addValue("tags", arrayLiteral(ticket.tags())));
    }

    /**
     * 局部更新工单可变字段（服务层只传本次真正变化的列）；CAS 失败返回 false 由服务层转 409。
     *
     * <p>白名单只允许状态机相关列，避免"顺手改了工单号/创建时间"这类破坏审计的写操作。
     */
    private static final java.util.Set<String> MUTABLE_TICKET_COLUMNS = java.util.Set.of(
            "title", "description", "priority", "assignee", "assignee_group", "status",
            "sla_policy_id", "response_due_at", "resolve_due_at", "first_response_at",
            "resolved_at", "closed_at", "reopened_at", "reopen_count", "pending_reason",
            "sla_paused_at", "sla_paused_total_minutes", "satisfaction", "resolution", "tags",
            "sla_response_breached", "sla_resolve_breached");

    public boolean updateFields(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!MUTABLE_TICKET_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("不允许修改的工单字段：" + column);
            }
            if ("tags".equals(column)) {
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) entry.getValue();
                sets.add("tags = CAST(:tags AS text[])");
                params.addValue("tags", arrayLiteral(tags));
            } else {
                sets.add(column + " = :" + column);
                params.addValue(column, jdbcValue(entry.getValue()));
            }
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        String sql = "UPDATE ops_svc.ops_ticket SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected";
        return jdbc.update(sql, params) > 0;
    }

    private static Object jdbcValue(Object value) {
        return value instanceof Instant instant ? timestamp(instant) : value;
    }

    /** 超时扫描：只允许把 false 置 true，避免并发扫描重复记事件。 */
    public boolean markResponseBreached(UUID id) {
        return plain.update("""
                UPDATE ops_svc.ops_ticket
                   SET sla_response_breached = true, last_sla_scan_at = now(), updated_at = now()
                 WHERE id = ? AND sla_response_breached = false
                   AND first_response_at IS NULL
                   AND response_due_at < now()
                   AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                """, id) > 0;
    }

    public boolean markResolveBreached(UUID id) {
        return plain.update("""
                UPDATE ops_svc.ops_ticket
                   SET sla_resolve_breached = true, last_sla_scan_at = now(), updated_at = now()
                 WHERE id = ? AND sla_resolve_breached = false
                   AND resolve_due_at < now()
                   AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                """, id) > 0;
    }

    /** 扫描候选：未完成、未暂停、有截止时间且已过期。 */
    public List<UUID> findBreachCandidates(int limit) {
        return plain.queryForList("""
                SELECT id FROM ops_svc.ops_ticket
                 WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                   AND sla_paused_at IS NULL
                   AND ((response_due_at < now() AND first_response_at IS NULL
                         AND NOT sla_response_breached)
                     OR (resolve_due_at < now() AND NOT sla_resolve_breached))
                 ORDER BY created_at
                 LIMIT ?
                """, UUID.class, limit);
    }

    public String nextTicketNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.ops_ticket_no_seq')", Long.class);
        return com.medassist.ops.support.TicketNumbers.ticket(seq == null ? 1 : seq);
    }

    // ------------------------------------------------------------------ 轨迹/审计

    public void insertEvent(TicketEvent event) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_ticket_event
                    (ticket_id, actor, action, from_status, to_status, note, detail)
                VALUES (:ticket, :actor, :action, :from, :to, :note, CAST(:detail AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("ticket", event.ticketId())
                .addValue("actor", event.actor())
                .addValue("action", event.action())
                .addValue("from", event.fromStatus())
                .addValue("to", event.toStatus())
                .addValue("note", event.note())
                .addValue("detail", write(event.detail())));
    }

    public List<TicketEvent> listEvents(UUID ticketId, int limit) {
        return plain.query("""
                SELECT id, ticket_id, ts, actor, action, from_status, to_status, note, detail
                  FROM ops_svc.ops_ticket_event
                 WHERE ticket_id = ?
                 ORDER BY ts, id
                 LIMIT ?
                """, (rs, n) -> new TicketEvent(
                rs.getLong("id"),
                rs.getObject("ticket_id", UUID.class),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("note"),
                readMap(rs.getString("detail"))), ticketId, limit);
    }

    public void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                      String clientIp, String traceId) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_change_log
                    (actor, action, target_ref, detail, client_ip, trace_id)
                VALUES (:actor, :action, :target, CAST(:detail AS jsonb), :ip, :trace)
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("target", targetRef)
                .addValue("detail", write(detail == null ? Map.of() : detail))
                .addValue("ip", clientIp)
                .addValue("trace", traceId));
    }

    public List<AuditLog> listAudit(int limit) {
        return plain.query("""
                SELECT id, ts, actor, action, target_ref, detail, client_ip, trace_id
                  FROM ops_svc.ops_change_log
                 ORDER BY ts DESC LIMIT ?
                """, (rs, n) -> new AuditLog(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_ref"),
                readMap(rs.getString("detail")),
                rs.getString("client_ip"),
                rs.getString("trace_id")), limit);
    }

    // ------------------------------------------------------------------ 看板

    public List<Map<String, Object>> countByStatus() {
        return plain.queryForList("SELECT status, count(*) AS n FROM ops_svc.ops_ticket GROUP BY status");
    }

    public List<Map<String, Object>> countOpenByPriority() {
        return plain.queryForList("""
                SELECT priority, count(*) AS n FROM ops_svc.ops_ticket
                 WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 GROUP BY priority ORDER BY priority
                """);
    }

    public List<Map<String, Object>> countOpenByService() {
        return plain.queryForList("""
                SELECT t.service_code, c.name, count(*) AS n
                  FROM ops_svc.ops_ticket t
                  JOIN ops_svc.ops_service_catalog c ON c.service_code = t.service_code
                 WHERE t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 GROUP BY t.service_code, c.name
                 ORDER BY n DESC LIMIT 20
                """);
    }

    public long countOverdueResponse() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_ticket
                 WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                   AND first_response_at IS NULL AND response_due_at < now()
                """, Long.class);
        return n == null ? 0 : n;
    }

    public long countOverdueResolve() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_ticket
                 WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                   AND resolve_due_at < now()
                """, Long.class);
        return n == null ? 0 : n;
    }

    public long countOpen() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_ticket
                 WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                """, Long.class);
        return n == null ? 0 : n;
    }

    public long countCreatedSince(Instant since) {
        Long n = plain.queryForObject(
                "SELECT count(*) FROM ops_svc.ops_ticket WHERE created_at >= ?", Long.class,
                timestamp(since));
        return n == null ? 0 : n;
    }

    public long countBreachedSince(Instant since) {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_ticket
                 WHERE created_at >= ? AND (sla_response_breached OR sla_resolve_breached)
                """, Long.class, timestamp(since));
        return n == null ? 0 : n;
    }

    public BigDecimal avgFirstResponseMinutes(Instant since) {
        return plain.queryForObject("""
                SELECT avg(extract(epoch FROM (first_response_at - created_at)) / 60.0)
                  FROM ops_svc.ops_ticket
                 WHERE first_response_at IS NOT NULL AND created_at >= ?
                """, BigDecimal.class, timestamp(since));
    }

    public BigDecimal avgResolveMinutes(Instant since) {
        return plain.queryForObject("""
                SELECT avg(extract(epoch FROM (resolved_at - created_at)) / 60.0)
                  FROM ops_svc.ops_ticket
                 WHERE resolved_at IS NOT NULL AND created_at >= ?
                """, BigDecimal.class, timestamp(since));
    }

    public BigDecimal avgSatisfaction(Instant since) {
        return plain.queryForObject("""
                SELECT avg(satisfaction) FROM ops_svc.ops_ticket
                 WHERE satisfaction IS NOT NULL AND created_at >= ?
                """, BigDecimal.class, timestamp(since));
    }

    public List<Map<String, Object>> dailyTrend(int days) {
        // 按医院业务时区切日：容器/数据库常是 UTC，直接 ::date 会把 00:00–08:00 的单算到前一天
        return plain.queryForList("""
                WITH days AS (
                    SELECT generate_series(
                        (now() AT TIME ZONE 'Asia/Shanghai')::date - (? - 1),
                        (now() AT TIME ZONE 'Asia/Shanghai')::date,
                        interval '1 day')::date AS d
                )
                SELECT to_char(days.d, 'YYYY-MM-DD') AS day,
                       (SELECT count(*) FROM ops_svc.ops_ticket t
                         WHERE (t.created_at AT TIME ZONE 'Asia/Shanghai')::date = days.d) AS created,
                       (SELECT count(*) FROM ops_svc.ops_ticket t
                         WHERE (t.resolved_at AT TIME ZONE 'Asia/Shanghai')::date = days.d) AS resolved
                  FROM days ORDER BY days.d
                """, days);
    }

    // ------------------------------------------------------------------ 类型/JSON

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return STATIC_JSON.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static List<Integer> intList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof Number[] numbers) {
            return Arrays.stream(numbers).map(Number::intValue).toList();
        }
        if (raw instanceof Object[] values) {
            List<Integer> result = new ArrayList<>();
            for (Object value : values) {
                result.add(value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)));
            }
            return result;
        }
        return List.of();
    }

    private static List<LocalDate> dateList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof java.sql.Date[] dates) {
            return Arrays.stream(dates).map(java.sql.Date::toLocalDate).toList();
        }
        if (raw instanceof Object[] values) {
            List<LocalDate> result = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof java.sql.Date date) {
                    result.add(date.toLocalDate());
                } else if (value instanceof LocalDate date) {
                    result.add(date);
                } else {
                    result.add(LocalDate.parse(String.valueOf(value)));
                }
            }
            return result;
        }
        return List.of();
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return Arrays.asList(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 生成 PG 数组字面量（值来自校验后的输入，不含引号/反斜杠）。 */
    private static String arrayLiteral(List<?> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }

    private static String dateArrayLiteral(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", dates.stream().map(LocalDate::toString).toList()) + "}";
    }
}
