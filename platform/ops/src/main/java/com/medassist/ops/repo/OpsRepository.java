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
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {
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

    /**
     * 升级候选：未首响、策略配置了升级时限、已超过「创建时间 + 升级时限」且从未升级过。
     *
     * <p>为什么不用 {@link #findBreachCandidates}：升级时限通常早于响应时限（如 P2 30 分钟升级、
     * 响应时限 30 分钟，但 P3 是 240 分钟升级 / 120 分钟响应，两者不成固定比例），
     * 混在违约扫描里会让"超时"和"该升级"两种事实互相掩盖。
     */
    public List<UUID> findEscalationCandidates(int limit) {
        return plain.queryForList("""
                SELECT t.id FROM ops_svc.ops_ticket t
                  JOIN ops_svc.ops_sla_policy p ON p.id = t.sla_policy_id
                 WHERE t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                   AND t.sla_paused_at IS NULL
                   AND t.first_response_at IS NULL
                   AND p.escalation_minutes > 0
                   AND t.created_at + make_interval(mins => p.escalation_minutes) < now()
                   AND NOT EXISTS (SELECT 1 FROM ops_svc.ops_ticket_event e
                                    WHERE e.ticket_id = t.id AND e.action = 'ESCALATE')
                 ORDER BY t.created_at
                 LIMIT ?
                """, UUID.class, limit);
    }

    /** 某个工单是否已有指定动作的事件（升级幂等：同一工单只升级一次）。 */
    public boolean hasEvent(UUID ticketId, String action) {
        Long count = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_ticket_event
                 WHERE ticket_id = ? AND action = ?
                """, Long.class, ticketId, action);
        return count != null && count > 0;
    }

    public String nextTicketNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.ops_ticket_no_seq')", Long.class);
        return com.medassist.ops.support.TicketNumbers.ticket(seq == null ? 1 : seq);
    }

    // ------------------------------------------------------------------ 值班轮换

    private static final RowMapper<OpsModel.OnCallRotation> ROTATION_MAPPER = (rs, n) ->
            new OpsModel.OnCallRotation(
                    rs.getObject("id", UUID.class),
                    rs.getString("group_code"),
                    rs.getString("name"),
                    rs.getString("timezone"),
                    rs.getObject("start_date", LocalDate.class),
                    rs.getInt("cycle_days"),
                    stringList(rs, "members"),
                    rs.getObject("handover_time", LocalTime.class),
                    rs.getString("status"),
                    rs.getLong("version"));

    public List<OpsModel.OnCallRotation> listRotations() {
        return plain.query("""
                SELECT id, group_code, name, timezone, start_date, cycle_days, members,
                       handover_time, status, version
                  FROM ops_svc.ops_oncall_rotation ORDER BY group_code
                """, ROTATION_MAPPER);
    }

    public Optional<OpsModel.OnCallRotation> findRotation(UUID id) {
        return plain.query("""
                SELECT id, group_code, name, timezone, start_date, cycle_days, members,
                       handover_time, status, version
                  FROM ops_svc.ops_oncall_rotation WHERE id = ?
                """, ROTATION_MAPPER, id).stream().findFirst();
    }

    public boolean insertRotation(OpsModel.OnCallRotation rotation) {
        return jdbc.update("""
                INSERT INTO ops_svc.ops_oncall_rotation
                    (id, group_code, name, timezone, start_date, cycle_days, members,
                     handover_time, status)
                VALUES (:id, :group, :name, :timezone, :start, :cycle, CAST(:members AS text[]),
                        :handover, :status)
                ON CONFLICT (group_code) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", rotation.id())
                .addValue("group", rotation.groupCode())
                .addValue("name", rotation.name())
                .addValue("timezone", rotation.timezone())
                .addValue("start", rotation.startDate())
                .addValue("cycle", rotation.cycleDays())
                .addValue("members", arrayLiteral(rotation.members()))
                .addValue("handover", rotation.handoverTime())
                .addValue("status", rotation.status())) > 0;
    }

    private static final RowMapper<OpsModel.OnCallShift> SHIFT_MAPPER = (rs, n) ->
            new OpsModel.OnCallShift(
                    rs.getObject("id", UUID.class),
                    rs.getObject("rotation_id", UUID.class),
                    rs.getObject("shift_date", LocalDate.class),
                    rs.getString("primary_user"),
                    rs.getString("secondary_user"),
                    rs.getString("note"));

    public List<OpsModel.OnCallShift> listShifts(UUID rotationId, LocalDate from, LocalDate to) {
        return plain.query("""
                SELECT id, rotation_id, shift_date, primary_user, secondary_user, note
                  FROM ops_svc.ops_oncall_shift
                 WHERE rotation_id = ? AND shift_date BETWEEN ? AND ?
                 ORDER BY shift_date
                """, SHIFT_MAPPER, rotationId, from, to);
    }

    /** 今日值班：groupCode 为空返回全部组。 */
    public List<Map<String, Object>> shiftsOn(LocalDate date, String groupCode) {
        String sql = """
                SELECT r.group_code, r.name, s.shift_date, s.primary_user, s.secondary_user
                  FROM ops_svc.ops_oncall_shift s
                  JOIN ops_svc.ops_oncall_rotation r ON r.id = s.rotation_id
                 WHERE s.shift_date = :date AND r.status = 'ACTIVE'
                """ + (groupCode == null || groupCode.isBlank() ? "" : " AND r.group_code = :group")
                + " ORDER BY r.group_code";
        MapSqlParameterSource params = new MapSqlParameterSource("date", date);
        if (groupCode != null && !groupCode.isBlank()) {
            params.addValue("group", groupCode);
        }
        return jdbc.queryForList(sql, params);
    }

    /** 排班生成：已存在的班次保留（不覆盖人工调整），返回新增数量。 */
    public int generateShift(UUID rotationId, LocalDate date, String primary, String secondary) {
        return jdbc.update("""
                INSERT INTO ops_svc.ops_oncall_shift
                    (id, rotation_id, shift_date, primary_user, secondary_user)
                VALUES (:id, :rotation, :date, :primary, :secondary)
                ON CONFLICT (rotation_id, shift_date) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("rotation", rotationId)
                .addValue("date", date)
                .addValue("primary", primary)
                .addValue("secondary", secondary));
    }

    /** 人工覆盖：有则更新，无则新增。 */
    public void overrideShift(UUID rotationId, LocalDate date, String primary, String secondary,
                              String note) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_oncall_shift
                    (id, rotation_id, shift_date, primary_user, secondary_user, note)
                VALUES (:id, :rotation, :date, :primary, :secondary, :note)
                ON CONFLICT (rotation_id, shift_date) DO UPDATE SET
                    primary_user = EXCLUDED.primary_user,
                    secondary_user = EXCLUDED.secondary_user,
                    note = EXCLUDED.note, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("rotation", rotationId)
                .addValue("date", date)
                .addValue("primary", primary)
                .addValue("secondary", secondary)
                .addValue("note", note));
    }

    // ------------------------------------------------------------------ 重大事件

    public String nextIncidentNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.ops_incident_no_seq')", Long.class);
        return com.medassist.ops.support.TicketNumbers.incident(seq == null ? 1 : seq);
    }

    private static final RowMapper<OpsModel.Incident> INCIDENT_MAPPER = (rs, n) ->
            new OpsModel.Incident(
                    rs.getObject("id", UUID.class),
                    rs.getString("incident_no"),
                    rs.getString("title"),
                    rs.getString("severity"),
                    rs.getString("status"),
                    rs.getString("commander"),
                    instant(rs, "detected_at"),
                    instant(rs, "started_at"),
                    instant(rs, "resolved_at"),
                    instant(rs, "closed_at"),
                    stringList(rs, "affected_services"),
                    rs.getObject("ticket_id", UUID.class),
                    rs.getString("summary"),
                    rs.getString("root_cause"),
                    rs.getString("impact"),
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"),
                    List.of());

    private static final String INCIDENT_COLUMNS = """
            id, incident_no, title, severity, status, commander, detected_at, started_at,
            resolved_at, closed_at, affected_services, ticket_id, summary, root_cause, impact,
            version, created_at, updated_at
            """;

    public Optional<OpsModel.Incident> findIncident(UUID id) {
        return plain.query("SELECT " + INCIDENT_COLUMNS + " FROM ops_svc.ops_incident WHERE id = ?",
                INCIDENT_MAPPER, id).stream().findFirst();
    }

    public List<OpsModel.Incident> listIncidents(String status, String severity, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + INCIDENT_COLUMNS
                + " FROM ops_svc.ops_incident WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = :severity");
            params.addValue("severity", severity);
        }
        sql.append(" ORDER BY detected_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, INCIDENT_MAPPER);
    }

    public long countIncidents(String status, String severity) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ops_svc.ops_incident WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = :severity");
            params.addValue("severity", severity);
        }
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    public void insertIncident(OpsModel.Incident incident) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_incident
                    (id, incident_no, title, severity, status, commander, detected_at, started_at,
                     affected_services, ticket_id, summary, impact)
                VALUES
                    (:id, :no, :title, :severity, :status, :commander, :detected, :started,
                     CAST(:services AS text[]), :ticket, :summary, :impact)
                """, new MapSqlParameterSource()
                .addValue("id", incident.id())
                .addValue("no", incident.incidentNo())
                .addValue("title", incident.title())
                .addValue("severity", incident.severity())
                .addValue("status", incident.status())
                .addValue("commander", incident.commander())
                .addValue("detected", timestamp(incident.detectedAt()))
                .addValue("started", timestamp(incident.startedAt()))
                .addValue("services", arrayLiteral(incident.affectedServices()))
                .addValue("ticket", incident.ticketId())
                .addValue("summary", incident.summary())
                .addValue("impact", incident.impact()));
    }

    private static final java.util.Set<String> MUTABLE_INCIDENT_COLUMNS = java.util.Set.of(
            "title", "severity", "status", "commander", "detected_at", "started_at",
            "resolved_at", "closed_at", "affected_services", "ticket_id", "summary",
            "root_cause", "impact");

    public boolean updateIncidentFields(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!MUTABLE_INCIDENT_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("不允许修改的事件字段：" + column);
            }
            if ("affected_services".equals(column)) {
                @SuppressWarnings("unchecked")
                List<String> services = (List<String>) entry.getValue();
                sets.add("affected_services = CAST(:services AS text[])");
                params.addValue("services", arrayLiteral(services));
            } else {
                sets.add(column + " = :" + column);
                params.addValue(column, jdbcValue(entry.getValue()));
            }
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE ops_svc.ops_incident SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    public void insertIncidentEvent(OpsModel.IncidentEvent event) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_incident_event
                    (incident_id, actor, action, from_status, to_status, note, detail)
                VALUES (:incident, :actor, :action, :from, :to, :note, CAST(:detail AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("incident", event.incidentId())
                .addValue("actor", event.actor())
                .addValue("action", event.action())
                .addValue("from", event.fromStatus())
                .addValue("to", event.toStatus())
                .addValue("note", event.note())
                .addValue("detail", write(event.detail())));
    }

    public List<OpsModel.IncidentEvent> listIncidentEvents(UUID incidentId, int limit) {
        return plain.query("""
                SELECT id, incident_id, ts, actor, action, from_status, to_status, note, detail
                  FROM ops_svc.ops_incident_event
                 WHERE incident_id = ?
                 ORDER BY ts, id LIMIT ?
                """, (rs, n) -> new OpsModel.IncidentEvent(
                rs.getLong("id"),
                rs.getObject("incident_id", UUID.class),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("note"),
                readMap(rs.getString("detail"))), incidentId, limit);
    }

    // ------------------------------------------------------------------ 复盘

    private static final RowMapper<OpsModel.Postmortem> POSTMORTEM_MAPPER = (rs, n) ->
            new OpsModel.Postmortem(
                    rs.getObject("id", UUID.class),
                    rs.getObject("incident_id", UUID.class),
                    rs.getString("author"),
                    rs.getString("status"),
                    readListMap(rs.getString("timeline")),
                    rs.getString("root_cause"),
                    stringList(rs, "contributing_factors"),
                    readListMap(rs.getString("corrective_actions")),
                    rs.getString("approved_by"),
                    instant(rs, "approved_at"),
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    public Optional<OpsModel.Postmortem> findPostmortem(UUID incidentId) {
        return plain.query("""
                SELECT id, incident_id, author, status, timeline, root_cause,
                       contributing_factors, corrective_actions, approved_by, approved_at,
                       version, created_at, updated_at
                  FROM ops_svc.ops_postmortem WHERE incident_id = ?
                """, POSTMORTEM_MAPPER, incidentId).stream().findFirst();
    }

    public void insertPostmortem(OpsModel.Postmortem postmortem) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_postmortem
                    (id, incident_id, author, status, timeline, root_cause,
                     contributing_factors, corrective_actions)
                VALUES (:id, :incident, :author, :status, CAST(:timeline AS jsonb), :rootCause,
                        CAST(:factors AS text[]), CAST(:actions AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", postmortem.id())
                .addValue("incident", postmortem.incidentId())
                .addValue("author", postmortem.author())
                .addValue("status", postmortem.status())
                .addValue("timeline", write(postmortem.timeline()))
                .addValue("rootCause", postmortem.rootCause())
                .addValue("factors", arrayLiteral(postmortem.contributingFactors()))
                .addValue("actions", write(postmortem.correctiveActions())));
    }

    private static final java.util.Set<String> MUTABLE_POSTMORTEM_COLUMNS = java.util.Set.of(
            "status", "timeline", "root_cause", "contributing_factors", "corrective_actions",
            "approved_by", "approved_at");

    public boolean updatePostmortemFields(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!MUTABLE_POSTMORTEM_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("不允许修改的复盘字段：" + column);
            }
            switch (column) {
                case "timeline", "corrective_actions" -> {
                    sets.add(column + " = CAST(:" + column + " AS jsonb)");
                    params.addValue(column, write(entry.getValue()));
                }
                case "contributing_factors" -> {
                    @SuppressWarnings("unchecked")
                    List<String> factors = (List<String>) entry.getValue();
                    sets.add("contributing_factors = CAST(:factors AS text[])");
                    params.addValue("factors", arrayLiteral(factors));
                }
                default -> {
                    sets.add(column + " = :" + column);
                    params.addValue(column, jdbcValue(entry.getValue()));
                }
            }
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE ops_svc.ops_postmortem SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    // ------------------------------------------------------------------ 看板（事件）

    public long countOpenIncidents() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_incident
                 WHERE status NOT IN ('RESOLVED', 'CLOSED')
                """, Long.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> countOpenIncidentsBySeverity() {
        return plain.queryForList("""
                SELECT severity, count(*) AS n FROM ops_svc.ops_incident
                 WHERE status NOT IN ('RESOLVED', 'CLOSED')
                 GROUP BY severity ORDER BY severity
                """);
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

    private static List<Map<String, Object>> readListMap(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return STATIC_JSON.readValue(json, LIST_MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 数组解析失败", e);
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
