package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.OpsModel;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.SlaPolicy;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.domain.OpsModel.TicketEvent;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.support.SlaClock;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工单全生命周期。
 *
 * <p>状态机（只允许这些边，其他一律 409）：
 * <pre>
 * NEW → ASSIGNED → IN_PROGRESS ⇄ PENDING
 *                     ↓
 *                  RESOLVED → CLOSED
 *                     ↓
 *                  REOPENED → IN_PROGRESS
 * 任意未完成 → CANCELLED
 * </pre>
 *
 * <p>三个细节决定这套工单是不是"真能运营"：
 * <ol>
 *   <li>首响时间只在<b>责任人或接单人</b>真正动作时记，报告人自己补充说明不算响应；</li>
 *   <li>挂起会暂停 SLA，恢复时把暂停时长顺延到截止时间，不靠人肉改时间；</li>
 *   <li>解决/关闭时若已超过截止时间，立即补记违约事件，不等定时扫描"补刀"。</li>
 * </ol>
 */
@Service
public class TicketService {

    private static final Set<String> OPEN_STATUSES = Set.of(
            OpsModel.NEW, OpsModel.ASSIGNED, OpsModel.IN_PROGRESS, OpsModel.PENDING,
            OpsModel.REOPENED);

    private final OpsRepository repo;
    private final SlaService slaService;
    private final OpsProperties properties;
    private final Clock clock;

    public TicketService(OpsRepository repo, SlaService slaService, OpsProperties properties,
                         Clock clock) {
        this.repo = repo;
        this.slaService = slaService;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 创建

    public Ticket create(OpsDtos.TicketCreateReq req, String actor, String clientIp, String traceId) {
        requireActor(actor);
        ServiceCatalog service = slaService.requireService(req.serviceCode());
        SlaPolicy policy = slaService.requirePolicy(req.serviceCode(), req.priority());
        Instant now = clock.instant();

        Ticket ticket = new Ticket(UUID.randomUUID(), repo.nextTicketNo(), req.title(),
                req.description(), service.serviceCode(), req.priority(), actor, req.reporterDept(),
                null, service.supportGroup(), OpsModel.NEW,
                req.source() == null ? "PORTAL" : req.source(), policy.id(),
                slaService.responseDue(now, policy), slaService.resolveDue(now, policy),
                null, null, null, null, 0, null, null, 0, null, null,
                req.tags() == null ? List.of() : req.tags(), false, false, 0, now, now,
                List.of(), "ON_TRACK", "ON_TRACK");
        repo.insertTicket(ticket);
        appendEvent(ticket, actor, "CREATE", null, OpsModel.NEW, req.description(),
                Map.of("service", service.serviceCode(), "priority", req.priority(),
                        "responseDueAt", String.valueOf(ticket.responseDueAt()),
                        "resolveDueAt", String.valueOf(ticket.resolveDueAt())));
        audit(actor, "TICKET_CREATE", ticket.ticketNo(),
                Map.of("service", service.serviceCode(), "priority", req.priority()), clientIp, traceId);
        return detail(ticket.id(), null);
    }

    // ------------------------------------------------------------------ 流转

    public Ticket assign(UUID id, OpsDtos.AssignReq req, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireOpen(id);
        if (OpsModel.PENDING.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_PAUSED);
        }
        if (req.assignee() == null || req.assignee().isBlank()) {
            throw new BusinessException(ErrorCode.OPS_TICKET_ASSIGNEE_REQUIRED);
        }
        String group = req.assigneeGroup() == null || req.assigneeGroup().isBlank()
                ? (ticket.assigneeGroup() == null
                    ? slaService.requireService(ticket.serviceCode()).supportGroup()
                    : ticket.assigneeGroup())
                : req.assigneeGroup();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("assignee", req.assignee().trim());
        fields.put("assignee_group", group);
        fields.put("status", OpsModel.ASSIGNED);
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "ASSIGN", ticket.status(), next.status(),
                "派单给 " + req.assignee(), Map.of("assignee", req.assignee(), "group", group));
        audit(actor, "TICKET_ASSIGN", ticket.ticketNo(),
                Map.of("assignee", req.assignee()), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket accept(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireTicket(id);
        requireTransition(ticket, Set.of(OpsModel.ASSIGNED, OpsModel.REOPENED), "接单");
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.IN_PROGRESS);
        if (ticket.assignee() == null || ticket.assignee().isBlank()) {
            fields.put("assignee", actor);
        }
        if (ticket.firstResponseAt() == null) {
            fields.put("first_response_at", now);
        }
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "ACCEPT", ticket.status(), next.status(), "已接单",
                Map.of("firstResponseAt", String.valueOf(next.firstResponseAt())));
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "ACCEPT"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket progress(UUID id, String note, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireTicket(id);
        if (OpsModel.PENDING.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_PAUSED);
        }
        requireTransition(ticket, Set.of(OpsModel.ASSIGNED, OpsModel.IN_PROGRESS,
                OpsModel.REOPENED), "处理");
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.IN_PROGRESS);
        if (ticket.firstResponseAt() == null) {
            fields.put("first_response_at", now);
        }
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "PROGRESS", ticket.status(), next.status(), note, Map.of());
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "PROGRESS"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket comment(UUID id, String note, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireTicket(id);
        if (!OPEN_STATUSES.contains(ticket.status()) && !OpsModel.RESOLVED.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (ticket.firstResponseAt() == null && actor.equals(ticket.assignee())) {
            fields.put("first_response_at", clock.instant());
        }
        Ticket next = fields.isEmpty() ? ticket : update(id, ticket.version(), fields);
        appendEvent(next, actor, "COMMENT", ticket.status(), ticket.status(), note, Map.of());
        audit(actor, "TICKET_COMMENT", ticket.ticketNo(), Map.of(), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket pending(UUID id, String reason, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        requireReason(reason);
        Ticket ticket = requireTicket(id);
        requireTransition(ticket, Set.of(OpsModel.ASSIGNED, OpsModel.IN_PROGRESS,
                OpsModel.REOPENED), "挂起");
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.PENDING);
        fields.put("pending_reason", reason.trim());
        fields.put("sla_paused_at", now);
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "PENDING", ticket.status(), next.status(), reason,
                Map.of("pausedAt", String.valueOf(now)));
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "PENDING"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket resume(UUID id, String note, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireTicket(id);
        if (!OpsModel.PENDING.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID, "只有挂起中的工单可以恢复");
        }
        Instant now = clock.instant();
        long pausedMinutes = ticket.slaPausedAt() == null ? 0
                : Math.max(Duration.between(ticket.slaPausedAt(), now).toMinutes(), 0);
        int totalPaused = (int) Math.min(Integer.MAX_VALUE,
                ticket.slaPausedTotalMinutes() + pausedMinutes);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.IN_PROGRESS);
        fields.put("pending_reason", null);
        fields.put("sla_paused_at", null);
        fields.put("sla_paused_total_minutes", totalPaused);
        if (ticket.firstResponseAt() == null && !ticket.responseBreached()) {
            fields.put("response_due_at", SlaClock.shift(ticket.responseDueAt(), pausedMinutes));
        }
        if (!ticket.resolveBreached()) {
            fields.put("resolve_due_at", SlaClock.shift(ticket.resolveDueAt(), pausedMinutes));
        }
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "RESUME", ticket.status(), next.status(),
                note == null ? "恢复处理" : note,
                Map.of("pausedMinutes", pausedMinutes, "totalPausedMinutes", totalPaused));
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(),
                Map.of("action", "RESUME", "pausedMinutes", pausedMinutes), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket resolve(UUID id, String resolution, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        requireResolution(resolution);
        Ticket ticket = requireTicket(id);
        if (OpsModel.PENDING.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_PAUSED);
        }
        requireTransition(ticket, Set.of(OpsModel.IN_PROGRESS, OpsModel.REOPENED), "解决");
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.RESOLVED);
        fields.put("resolved_at", now);
        fields.put("resolution", resolution.trim());
        if (ticket.firstResponseAt() == null) {
            fields.put("first_response_at", now);
        }
        // 解决时顺手把真实超时补记掉，不依赖定时扫描是否刚好跑过
        boolean responseBreach = ticket.firstResponseAt() == null && !ticket.responseBreached()
                && ticket.responseDueAt() != null && now.isAfter(ticket.responseDueAt());
        boolean resolveBreach = !ticket.resolveBreached()
                && ticket.resolveDueAt() != null && now.isAfter(ticket.resolveDueAt());
        if (responseBreach) {
            fields.put("sla_response_breached", true);
        }
        if (resolveBreach) {
            fields.put("sla_resolve_breached", true);
        }
        Ticket next = update(id, ticket.version(), fields);
        if (responseBreach) {
            appendEvent(next, actor, "SLA_RESPONSE_BREACH", next.status(), next.status(),
                    "首次响应超时（解决时补记）", Map.of("dueAt", String.valueOf(ticket.responseDueAt())));
        }
        if (resolveBreach) {
            appendEvent(next, actor, "SLA_RESOLVE_BREACH", next.status(), next.status(),
                    "解决超时（解决时补记）", Map.of("dueAt", String.valueOf(ticket.resolveDueAt())));
        }
        appendEvent(next, actor, "RESOLVE", ticket.status(), next.status(), resolution, Map.of());
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "RESOLVE"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket close(UUID id, Integer satisfaction, String note, String actor,
                        String clientIp, String traceId) {
        requireActor(actor);
        Ticket ticket = requireTicket(id);
        if (!OpsModel.RESOLVED.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_TRANSITION_INVALID, "只有已解决的工单可以关闭");
        }
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.CLOSED);
        fields.put("closed_at", now);
        if (satisfaction != null) {
            fields.put("satisfaction", satisfaction);
        }
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "CLOSE", ticket.status(), next.status(),
                note == null ? "关闭工单" : note,
                satisfaction == null ? Map.of() : Map.of("satisfaction", satisfaction));
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "CLOSE"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket reopen(UUID id, String reason, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        requireReason(reason);
        Ticket ticket = requireTicket(id);
        requireTransition(ticket, Set.of(OpsModel.RESOLVED, OpsModel.CLOSED), "重开");
        SlaPolicy policy = slaService.requirePolicy(ticket.serviceCode(), ticket.priority());
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.REOPENED);
        fields.put("reopened_at", now);
        fields.put("reopen_count", ticket.reopenCount() + 1);
        fields.put("resolved_at", null);
        fields.put("closed_at", null);
        fields.put("resolution", null);
        fields.put("resolve_due_at", slaService.resolveDue(now, policy));
        fields.put("sla_resolve_breached", false);
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "REOPEN", ticket.status(), next.status(), reason,
                Map.of("reopenCount", next.reopenCount(),
                        "resolveDueAt", String.valueOf(next.resolveDueAt())));
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(),
                Map.of("action", "REOPEN", "count", next.reopenCount()), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket cancel(UUID id, String reason, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        requireReason(reason);
        Ticket ticket = requireTicket(id);
        if (OpsModel.CANCELLED.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID, "工单已取消");
        }
        if (OpsModel.RESOLVED.equals(ticket.status()) || OpsModel.CLOSED.equals(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_TRANSITION_INVALID,
                    "已解决/已关闭的工单不能取消，请使用重开");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", OpsModel.CANCELLED);
        fields.put("sla_paused_at", null);
        fields.put("pending_reason", null);
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "CANCEL", ticket.status(), next.status(), reason, Map.of());
        audit(actor, "TICKET_TRANSITION", ticket.ticketNo(), Map.of("action", "CANCEL"), clientIp, traceId);
        return detail(id, null);
    }

    public Ticket changePriority(UUID id, String priority, String reason, String actor,
                                 String clientIp, String traceId) {
        requireActor(actor);
        requireReason(reason);
        Ticket ticket = requireTicket(id);
        if (!OPEN_STATUSES.contains(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
        SlaPolicy policy = slaService.requirePolicy(ticket.serviceCode(), priority);
        Instant now = clock.instant();
        Instant responseDue = ticket.firstResponseAt() != null ? ticket.responseDueAt()
                : slaService.responseDue(ticket.createdAt(), policy);
        Instant resolveDue = slaService.resolveDue(ticket.createdAt(), policy);
        boolean responseBreached = ticket.firstResponseAt() == null
                && responseDue != null && now.isAfter(responseDue);
        boolean resolveBreached = resolveDue != null && now.isAfter(resolveDue);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("priority", priority);
        fields.put("sla_policy_id", policy.id());
        fields.put("response_due_at", responseDue);
        fields.put("resolve_due_at", resolveDue);
        fields.put("sla_response_breached", responseBreached);
        fields.put("sla_resolve_breached", resolveBreached);
        Ticket next = update(id, ticket.version(), fields);
        appendEvent(next, actor, "PRIORITY_CHANGE", ticket.status(), next.status(), reason,
                Map.of("from", ticket.priority(), "to", priority,
                        "responseDueAt", String.valueOf(responseDue),
                        "resolveDueAt", String.valueOf(resolveDue)));
        audit(actor, "TICKET_PRIORITY", ticket.ticketNo(),
                Map.of("from", ticket.priority(), "to", priority), clientIp, traceId);
        return detail(id, null);
    }

    // ------------------------------------------------------------------ 查询

    public List<Ticket> list(OpsRepository.TicketFilter filter, Integer limit, Integer offset) {
        int size = limit == null ? properties.getPreviewRows()
                : Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        int start = offset == null ? 0 : Math.max(offset, 0);
        Instant now = clock.instant();
        Map<String, SlaPolicy> index = slaService.policyIndex(null);
        return repo.listTickets(filter, size, start).stream()
                .map(ticket -> decorate(ticket, index, now))
                .toList();
    }

    public long count(OpsRepository.TicketFilter filter) {
        return repo.countTickets(filter);
    }

    public Ticket detail(UUID id, Integer eventLimit) {
        Ticket ticket = requireTicket(id);
        int size = eventLimit == null ? properties.getPreviewRows()
                : Math.min(Math.max(eventLimit, 1), 1000);
        List<TicketEvent> events = repo.listEvents(id, size);
        Ticket withEvents = new Ticket(ticket.id(), ticket.ticketNo(), ticket.title(),
                ticket.description(), ticket.serviceCode(), ticket.priority(), ticket.reporter(),
                ticket.reporterDept(), ticket.assignee(), ticket.assigneeGroup(), ticket.status(),
                ticket.source(), ticket.slaPolicyId(), ticket.responseDueAt(),
                ticket.resolveDueAt(), ticket.firstResponseAt(), ticket.resolvedAt(),
                ticket.closedAt(), ticket.reopenedAt(), ticket.reopenCount(), ticket.pendingReason(),
                ticket.slaPausedAt(), ticket.slaPausedTotalMinutes(), ticket.satisfaction(),
                ticket.resolution(), ticket.tags(), ticket.responseBreached(),
                ticket.resolveBreached(), ticket.version(), ticket.createdAt(), ticket.updatedAt(),
                events, ticket.responseState(), ticket.resolveState());
        return decorate(withEvents, slaService.policyIndex(ticket.serviceCode()), clock.instant());
    }

    // ------------------------------------------------------------------ SLA 扫描

    public Map<String, Object> scanSla(String actor, String clientIp, String traceId) {
        requireActor(actor);
        return scan(actor, clientIp, traceId);
    }

    /** 定时任务入口：actor 固定为 system:scheduler，仍会写审计。 */
    public Map<String, Object> scanSlaScheduled() {
        return scan("system:scheduler", "127.0.0.1", null);
    }

    private Map<String, Object> scan(String actor, String clientIp, String traceId) {
        Instant now = clock.instant();
        int response = 0;
        int resolve = 0;
        try {
            List<UUID> candidates = repo.findBreachCandidates(1000);
            for (UUID id : candidates) {
                Ticket ticket = repo.findTicket(id).orElse(null);
                if (ticket == null) {
                    continue;
                }
                if (ticket.firstResponseAt() == null && ticket.responseDueAt() != null
                        && now.isAfter(ticket.responseDueAt()) && repo.markResponseBreached(id)) {
                    appendEvent(ticket, actor, "SLA_RESPONSE_BREACH", ticket.status(), ticket.status(),
                            "首次响应超时", Map.of("dueAt", String.valueOf(ticket.responseDueAt())));
                    response++;
                }
                if (ticket.resolveDueAt() != null && now.isAfter(ticket.resolveDueAt())
                        && repo.markResolveBreached(id)) {
                    appendEvent(ticket, actor, "SLA_RESOLVE_BREACH", ticket.status(), ticket.status(),
                            "解决超时", Map.of("dueAt", String.valueOf(ticket.resolveDueAt())));
                    resolve++;
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scanned", candidates.size());
            summary.put("responseBreached", response);
            summary.put("resolveBreached", resolve);
            summary.put("escalated", escalate(actor));
            audit(actor, "SLA_SCAN", "sla", summary, clientIp, traceId);
            return summary;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new BusinessException(ErrorCode.OPS_SLA_SCAN_FAILED);
        }
    }

    /**
     * 消费 SLA 策略的 {@code escalation_minutes}：超时仍未首响的工单自动升级一档优先级，
     * 并把当日值班人记入事件（通知对象可追溯，但不由系统替人"接单"）。
     *
     * <p>幂等靠事件：同一工单只要已有 ESCALATE 事件就不再升级，重复扫描不会连跳两档。
     * 升级只提优先级、不改截止时间——不能因为拖延而把 SLA 悄悄放宽。
     */
    private int escalate(String actor) {
        int escalated = 0;
        for (UUID id : repo.findEscalationCandidates(1000)) {
            Ticket ticket = repo.findTicket(id).orElse(null);
            if (ticket == null || ticket.firstResponseAt() != null) {
                continue;
            }
            if (repo.hasEvent(id, "ESCALATE")) {
                continue;
            }
            String from = ticket.priority();
            String to = escalatePriority(from);
            if (to.equals(from)) {
                continue;
            }
            if (!repo.updateFields(id, ticket.version(), Map.of("priority", to))) {
                // 并发变更（他人已改优先级）：下一轮再判定，不覆盖别人的动作
                continue;
            }
            String oncall = oncallPrimary(ticket.assigneeGroup());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromPriority", from);
            detail.put("toPriority", to);
            detail.put("assigneeGroup", ticket.assigneeGroup() == null ? "" : ticket.assigneeGroup());
            detail.put("oncallPrimary", oncall);
            detail.put("notified", !oncall.isEmpty());
            appendEvent(ticket, actor, "ESCALATE", ticket.status(), ticket.status(),
                    "超过升级时限未首次响应，自动升级 " + from + "→" + to
                            + (oncall.isEmpty() ? "（未匹配到当日值班人）" : "，通知值班人 " + oncall),
                    detail);
            escalated++;
        }
        return escalated;
    }

    /** 升级一档：P4→P3→P2→P1；P1 已最高，不再变动。 */
    static String escalatePriority(String priority) {
        return switch (priority == null ? "" : priority) {
            case "P4" -> "P3";
            case "P3" -> "P2";
            case "P2" -> "P1";
            default -> priority == null ? "" : priority;
        };
    }

    /** 当日值班人：按工单的支撑组匹配值班组；没有值班表时返回空串（事件里写明未匹配）。 */
    private String oncallPrimary(String groupCode) {
        if (groupCode == null || groupCode.isBlank()) {
            return "";
        }
        var today = com.medassist.common.time.BusinessTime.today();
        for (Map<String, Object> shift : repo.shiftsOn(today, groupCode)) {
            Object primary = shift.get("primary_user");
            if (primary != null && !primary.toString().isBlank()) {
                return primary.toString();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 内部

    private Ticket requireTicket(UUID id) {
        return repo.findTicket(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_TICKET_NOT_FOUND));
    }

    private Ticket requireOpen(UUID id) {
        Ticket ticket = requireTicket(id);
        if (!OPEN_STATUSES.contains(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
        return ticket;
    }

    private Ticket update(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (!repo.updateFields(id, expectedVersion, fields)) {
            throw new BusinessException(ErrorCode.OPS_TICKET_VERSION_CONFLICT);
        }
        return requireTicket(id);
    }

    private void requireTransition(Ticket ticket, Set<String> allowed, String action) {
        if (!allowed.contains(ticket.status())) {
            throw new BusinessException(ErrorCode.OPS_TICKET_TRANSITION_INVALID,
                    "工单当前状态 " + ticket.status() + " 不允许" + action);
        }
    }

    private Ticket decorate(Ticket ticket, Map<String, SlaPolicy> index, Instant now) {
        // 已取消的工单不再参与 SLA 计时：继续显示"剩余 N 小时"会让一线误以为还要处理
        if (OpsModel.CANCELLED.equals(ticket.status())) {
            return ticket.withStates("CANCELLED", "CANCELLED");
        }
        SlaPolicy policy = slaService.policyOf(index, ticket);
        int responseWindow = policy == null ? 0 : policy.responseMinutes();
        int resolveWindow = policy == null ? 0 : policy.resolveMinutes();
        boolean paused = OpsModel.PENDING.equals(ticket.status());
        boolean responseDone = ticket.firstResponseAt() != null;
        boolean resolveDone = ticket.resolvedAt() != null;
        String responseState = SlaClock.state(ticket.responseDueAt(), now,
                ticket.responseBreached(), paused, responseDone, responseWindow);
        String resolveState = SlaClock.state(ticket.resolveDueAt(), now,
                ticket.resolveBreached(), paused, resolveDone, resolveWindow);
        return ticket.withStates(responseState, resolveState);
    }

    private void appendEvent(Ticket ticket, String actor, String action, String from, String to,
                             String note, Map<String, Object> detail) {
        repo.insertEvent(new TicketEvent(0, ticket.id(), clock.instant(), actor, action,
                from, to, note, detail == null ? Map.of() : detail));
    }

    private void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                       String clientIp, String traceId) {
        repo.audit(actor, action, targetRef, detail, clientIp, traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(ErrorCode.OPS_REASON_REQUIRED);
        }
    }

    private static void requireResolution(String resolution) {
        if (resolution == null || resolution.trim().length() < 2) {
            throw new BusinessException(ErrorCode.OPS_TICKET_RESOLUTION_REQUIRED);
        }
    }
}
