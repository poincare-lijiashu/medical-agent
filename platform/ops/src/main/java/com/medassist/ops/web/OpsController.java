package com.medassist.ops.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.ops.domain.OpsModel.AuditLog;
import com.medassist.ops.domain.OpsModel.Calendar;
import com.medassist.ops.domain.OpsModel.Incident;
import com.medassist.ops.domain.OpsModel.OnCallRotation;
import com.medassist.ops.domain.OpsModel.OnCallShift;
import com.medassist.ops.domain.OpsModel.Postmortem;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.SlaPolicy;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.service.IncidentService;
import com.medassist.ops.service.OpsDashboardService;
import com.medassist.ops.service.OnCallService;
import com.medassist.ops.service.SlaService;
import com.medassist.ops.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运营支撑 API。
 *
 * <p>权限切分（auth V89）：
 * <ul>
 *   <li>{@code ops:read}：服务目录/SLA/工单/看板查询；</li>
 *   <li>{@code ops:ticket:create}：建单；</li>
 *   <li>{@code ops:ticket:write}：派单/接单/处理/挂起/解决/关闭/重开/取消/评论；</li>
 *   <li>{@code ops:sla:manage}：服务目录、SLA 策略与营业日历（对外承诺，不能一线随手改）；</li>
 *   <li>{@code ops:audit:read}：运营审计。</li>
 * </ul>
 *
 * <p>报告人来自 {@code X-Actor}，不接受请求体自报身份。
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private static final String READ = "ops:read";
    private static final String TICKET_CREATE = "ops:ticket:create";
    private static final String TICKET_WRITE = "ops:ticket:write";
    private static final String SLA_MANAGE = "ops:sla:manage";
    private static final String INCIDENT_WRITE = "ops:incident:write";
    private static final String ONCALL_MANAGE = "ops:oncall:manage";
    private static final String AUDIT_READ = "ops:audit:read";

    private final SlaService slaService;
    private final TicketService ticketService;
    private final OpsDashboardService dashboardService;
    private final OnCallService onCallService;
    private final IncidentService incidentService;
    private final OpsRepository repo;

    public OpsController(SlaService slaService, TicketService ticketService,
                         OpsDashboardService dashboardService, OnCallService onCallService,
                         IncidentService incidentService, OpsRepository repo) {
        this.slaService = slaService;
        this.ticketService = ticketService;
        this.dashboardService = dashboardService;
        this.onCallService = onCallService;
        this.incidentService = incidentService;
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 服务目录/SLA/日历

    @GetMapping("/services")
    public ApiResponse<List<ServiceCatalog>> services(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(slaService.listServices(onlyActive));
    }

    @PostMapping("/services")
    public ApiResponse<ServiceCatalog> upsertService(
            @Valid @RequestBody OpsDtos.ServiceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, SLA_MANAGE);
        return ApiResponse.ok(slaService.upsertService(req, actor, clientIp, traceId));
    }

    @GetMapping("/calendars")
    public ApiResponse<List<Calendar>> calendars(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(slaService.listCalendars());
    }

    @PostMapping("/calendars")
    public ApiResponse<Calendar> upsertCalendar(
            @Valid @RequestBody OpsDtos.CalendarReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, SLA_MANAGE);
        return ApiResponse.ok(slaService.upsertCalendar(req, actor, clientIp, traceId));
    }

    @GetMapping("/sla/policies")
    public ApiResponse<List<SlaPolicy>> policies(
            @RequestParam(required = false) String serviceCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(slaService.listPolicies(serviceCode));
    }

    @PostMapping("/sla/policies")
    public ApiResponse<SlaPolicy> upsertPolicy(
            @Valid @RequestBody OpsDtos.SlaPolicyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, SLA_MANAGE);
        return ApiResponse.ok(slaService.upsertPolicy(req, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 工单

    @PostMapping("/tickets")
    public ApiResponse<Ticket> createTicket(
            @Valid @RequestBody OpsDtos.TicketCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_CREATE);
        return ApiResponse.ok(ticketService.create(req, actor, clientIp, traceId));
    }

    @GetMapping("/tickets")
    public ApiResponse<PageResult<Ticket>> tickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        OpsRepository.TicketFilter filter = new OpsRepository.TicketFilter(
                status, priority, serviceCode, assignee, keyword, overdue);
        long total = ticketService.count(filter);
        List<Ticket> items = ticketService.list(filter, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @GetMapping("/tickets/{id}")
    public ApiResponse<Ticket> ticket(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer eventLimit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(ticketService.detail(id, eventLimit));
    }

    @PostMapping("/tickets/{id}/assign")
    public ApiResponse<Ticket> assign(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.AssignReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.assign(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/accept")
    public ApiResponse<Ticket> accept(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.accept(id, actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/progress")
    public ApiResponse<Ticket> progress(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.NoteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.progress(id, req.note(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/comments")
    public ApiResponse<Ticket> comment(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.NoteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.comment(id, req.note(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/pending")
    public ApiResponse<Ticket> pending(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.PendingReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.pending(id, req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/resume")
    public ApiResponse<Ticket> resume(
            @PathVariable UUID id,
            @RequestBody(required = false) OpsDtos.NoteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.resume(id, req == null ? null : req.note(),
                actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/resolve")
    public ApiResponse<Ticket> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ResolveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.resolve(id, req.resolution(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/close")
    public ApiResponse<Ticket> close(
            @PathVariable UUID id,
            @RequestBody(required = false) OpsDtos.CloseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.close(id,
                req == null ? null : req.satisfaction(), req == null ? null : req.note(),
                actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/reopen")
    public ApiResponse<Ticket> reopen(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ReopenReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.reopen(id, req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/cancel")
    public ApiResponse<Ticket> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.CancelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.cancel(id, req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/tickets/{id}/priority")
    public ApiResponse<Ticket> priority(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.PriorityReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.changePriority(id, req.priority(), req.reason(),
                actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 值班

    @GetMapping("/oncall/rotations")
    public ApiResponse<List<OnCallRotation>> rotations(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(onCallService.listRotations());
    }

    @PostMapping("/oncall/rotations")
    public ApiResponse<OnCallRotation> createRotation(
            @Valid @RequestBody OpsDtos.RotationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, ONCALL_MANAGE);
        return ApiResponse.ok(onCallService.createRotation(req, actor, clientIp, traceId));
    }

    @PostMapping("/oncall/rotations/{id}/generate")
    public ApiResponse<Map<String, Object>> generateShifts(
            @PathVariable UUID id,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, ONCALL_MANAGE);
        return ApiResponse.ok(onCallService.generate(id, from, to, actor, clientIp, traceId));
    }

    @GetMapping("/oncall/rotations/{id}/shifts")
    public ApiResponse<List<OnCallShift>> shifts(
            @PathVariable UUID id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(onCallService.shifts(id, from, to));
    }

    @GetMapping("/oncall/today")
    public ApiResponse<Map<String, Object>> onCallToday(
            @RequestParam(required = false) String groupCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(onCallService.today(groupCode));
    }

    @PutMapping("/oncall/shifts/{rotationId}/{date}")
    public ApiResponse<Map<String, Object>> overrideShift(
            @PathVariable UUID rotationId,
            @PathVariable LocalDate date,
            @Valid @RequestBody OpsDtos.ShiftOverrideReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, ONCALL_MANAGE);
        onCallService.overrideShift(rotationId, date, req, actor, clientIp, traceId);
        return ApiResponse.ok(Map.of("ok", true));
    }

    // ------------------------------------------------------------------ 重大事件

    @GetMapping("/incidents")
    public ApiResponse<PageResult<Incident>> incidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = incidentService.count(status, severity);
        List<Incident> items = incidentService.list(status, severity, safeSize,
                (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @PostMapping("/incidents")
    public ApiResponse<Incident> createIncident(
            @Valid @RequestBody OpsDtos.IncidentCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.create(req, actor, clientIp, traceId));
    }

    @GetMapping("/incidents/{id}")
    public ApiResponse<Incident> incident(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer eventLimit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(incidentService.detail(id, eventLimit));
    }

    @PostMapping("/incidents/{id}/status")
    public ApiResponse<Incident> incidentStatus(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.IncidentStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.changeStatus(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/incidents/{id}/commander")
    public ApiResponse<Incident> incidentCommander(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.CommanderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.changeCommander(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/incidents/{id}/comments")
    public ApiResponse<Incident> incidentComment(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.IncidentCommentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.comment(id, req.note(), actor, clientIp, traceId));
    }

    @GetMapping("/incidents/{id}/postmortem")
    public ApiResponse<Postmortem> postmortem(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(incidentService.postmortem(id));
    }

    @PostMapping("/incidents/{id}/postmortem")
    public ApiResponse<Postmortem> createPostmortem(
            @PathVariable UUID id,
            @RequestBody OpsDtos.PostmortemReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.createPostmortem(id, req, actor, clientIp, traceId));
    }

    @PutMapping("/incidents/{id}/postmortem")
    public ApiResponse<Postmortem> updatePostmortem(
            @PathVariable UUID id,
            @RequestBody OpsDtos.PostmortemReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.updatePostmortem(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/incidents/{id}/postmortem/submit")
    public ApiResponse<Postmortem> submitPostmortem(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.submitPostmortem(id, actor, clientIp, traceId));
    }

    @PostMapping("/incidents/{id}/postmortem/approve")
    public ApiResponse<Postmortem> approvePostmortem(
            @PathVariable UUID id,
            @RequestBody(required = false) OpsDtos.PostmortemApproveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, INCIDENT_WRITE);
        return ApiResponse.ok(incidentService.approvePostmortem(id, req, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 看板/SLA 扫描/审计

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(
            @RequestParam(required = false) Integer trendDays,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        Map<String, Object> data = new java.util.LinkedHashMap<>(dashboardService.dashboard());
        if (trendDays != null) {
            data.put("trend", dashboardService.trend(trendDays));
        }
        return ApiResponse.ok(data);
    }

    /** 手动触发超时扫描（定时任务之外，便于上线后立即核对一次）。 */
    @PostMapping("/sla/scan")
    public ApiResponse<Map<String, Object>> scanSla(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, TICKET_WRITE);
        return ApiResponse.ok(ticketService.scanSla(actor, clientIp, traceId));
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, AUDIT_READ);
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(repo.listAudit(size));
    }
}
