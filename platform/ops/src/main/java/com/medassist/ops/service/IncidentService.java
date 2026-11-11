package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.OpsModel.Incident;
import com.medassist.ops.domain.OpsModel.IncidentEvent;
import com.medassist.ops.domain.OpsModel.Postmortem;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 重大事件（Incident）与复盘（Postmortem）。
 *
 * <p>事件与工单是两级：工单是"一个请求"，事件是"一次影响面"。
 * 一个 P1 故障可能同时产生十几个工单，但只有一个事件；复盘针对事件，而不是针对某个工单。
 *
 * <p>状态机：OPEN → MITIGATING ⇄ MONITORING → RESOLVED → CLOSED；RESOLVED/CLOSED 可 REOPENED。
 * 复盘必须经过 DRAFT → REVIEWED → APPROVED，作者不能审批自己的复盘。
 */
@Service
public class IncidentService {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "OPEN", Set.of("MITIGATING", "MONITORING", "RESOLVED"),
            "MITIGATING", Set.of("OPEN", "MONITORING", "RESOLVED"),
            "MONITORING", Set.of("MITIGATING", "RESOLVED"),
            "RESOLVED", Set.of("CLOSED", "REOPENED"),
            "CLOSED", Set.of("REOPENED"),
            "REOPENED", Set.of("MITIGATING", "RESOLVED"));

    private final OpsRepository repo;
    private final SlaService slaService;
    private final OpsProperties properties;
    private final Clock clock;

    public IncidentService(OpsRepository repo, SlaService slaService, OpsProperties properties,
                           Clock clock) {
        this.repo = repo;
        this.slaService = slaService;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 事件

    public Incident create(OpsDtos.IncidentCreateReq req, String actor,
                           String clientIp, String traceId) {
        requireActor(actor);
        if (req.affectedServices() == null || req.affectedServices().isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_INVALID, "影响服务不能为空");
        }
        // 影响服务必须是目录里的真实服务，避免写"临床"这种无法派单的模糊名称
        req.affectedServices().forEach(slaService::requireService);
        if (req.ticketId() != null && repo.findTicket(req.ticketId()).isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_INVALID, "关联工单不存在");
        }
        Instant now = clock.instant();
        Incident incident = new Incident(UUID.randomUUID(), repo.nextIncidentNo(), req.title(),
                req.severity(), "OPEN",
                req.commander() == null || req.commander().isBlank() ? actor : req.commander(),
                now, now, null, null, req.affectedServices(), req.ticketId(), req.summary(),
                null, req.impact(), 0, now, now, List.of());
        repo.insertIncident(incident);
        appendEvent(incident, actor, "CREATE", null, "OPEN", req.summary(),
                Map.of("severity", req.severity(), "services", req.affectedServices()));
        audit(actor, "INCIDENT_CREATE", incident.incidentNo(),
                Map.of("severity", req.severity(), "services", req.affectedServices()),
                clientIp, traceId);
        return detail(incident.id(), null);
    }

    public List<Incident> list(String status, String severity, Integer limit, Integer offset) {
        int size = limit == null ? properties.getPreviewRows()
                : Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        int start = offset == null ? 0 : Math.max(offset, 0);
        return repo.listIncidents(status, severity, size, start);
    }

    public long count(String status, String severity) {
        return repo.countIncidents(status, severity);
    }

    public Incident detail(UUID id, Integer eventLimit) {
        Incident incident = requireIncident(id);
        int size = eventLimit == null ? properties.getPreviewRows()
                : Math.min(Math.max(eventLimit, 1), 1000);
        return new Incident(incident.id(), incident.incidentNo(), incident.title(),
                incident.severity(), incident.status(), incident.commander(),
                incident.detectedAt(), incident.startedAt(), incident.resolvedAt(),
                incident.closedAt(), incident.affectedServices(), incident.ticketId(),
                incident.summary(), incident.rootCause(), incident.impact(), incident.version(),
                incident.createdAt(), incident.updatedAt(), repo.listIncidentEvents(id, size));
    }

    public Incident changeStatus(UUID id, OpsDtos.IncidentStatusReq req, String actor,
                                 String clientIp, String traceId) {
        requireActor(actor);
        Incident incident = requireIncident(id);
        String target = req.status();
        if (target.equals(incident.status())) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_STATUS_INVALID, "状态未变化");
        }
        Set<String> allowed = ALLOWED.getOrDefault(incident.status(), Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_TRANSITION_INVALID,
                    "事件不能从 " + incident.status() + " 直接变为 " + target);
        }
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", target);
        if ("RESOLVED".equals(target)) {
            fields.put("resolved_at", now);
        } else if ("CLOSED".equals(target)) {
            fields.put("resolved_at", incident.resolvedAt() == null ? now : incident.resolvedAt());
            fields.put("closed_at", now);
        } else if ("REOPENED".equals(target)) {
            fields.put("resolved_at", null);
            fields.put("closed_at", null);
        }
        Incident next = update(id, incident.version(), fields);
        appendEvent(next, actor, "STATUS_CHANGE", incident.status(), target, req.note(), Map.of());
        audit(actor, "INCIDENT_STATUS", incident.incidentNo(),
                Map.of("from", incident.status(), "to", target), clientIp, traceId);
        return detail(id, null);
    }

    public Incident changeCommander(UUID id, OpsDtos.CommanderReq req, String actor,
                                    String clientIp, String traceId) {
        requireActor(actor);
        Incident incident = requireIncident(id);
        requireReason(req.reason());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("commander", req.commander().trim());
        Incident next = update(id, incident.version(), fields);
        appendEvent(next, actor, "COMMANDER", incident.status(), incident.status(), req.reason(),
                Map.of("from", incident.commander(), "to", req.commander()));
        audit(actor, "INCIDENT_COMMANDER", incident.incidentNo(),
                Map.of("from", incident.commander(), "to", req.commander()), clientIp, traceId);
        return detail(id, null);
    }

    public Incident comment(UUID id, String note, String actor,
                            String clientIp, String traceId) {
        requireActor(actor);
        Incident incident = requireIncident(id);
        appendEvent(incident, actor, "COMMENT", incident.status(), incident.status(), note, Map.of());
        audit(actor, "INCIDENT_COMMENT", incident.incidentNo(), Map.of(), clientIp, traceId);
        return detail(id, null);
    }

    // ------------------------------------------------------------------ 复盘

    public Postmortem createPostmortem(UUID incidentId, OpsDtos.PostmortemReq req, String actor,
                                       String clientIp, String traceId) {
        requireActor(actor);
        Incident incident = requireIncident(incidentId);
        if (!"RESOLVED".equals(incident.status()) && !"CLOSED".equals(incident.status())) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_STATUS_INVALID,
                    "只有已解决/已关闭的事件可以写复盘");
        }
        if (repo.findPostmortem(incidentId).isPresent()) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID, "该事件已存在复盘");
        }
        Postmortem postmortem = new Postmortem(UUID.randomUUID(), incidentId, actor, "DRAFT",
                req.timeline() == null ? List.of() : req.timeline(), req.rootCause(),
                req.contributingFactors() == null ? List.of() : req.contributingFactors(),
                req.correctiveActions() == null ? List.of() : req.correctiveActions(),
                null, null, 0, null, null);
        repo.insertPostmortem(postmortem);
        appendEvent(incident, actor, "ACTION", incident.status(), incident.status(),
                "创建复盘草稿", Map.of("postmortemAuthor", actor));
        audit(actor, "POSTMORTEM_DRAFT", incident.incidentNo(), Map.of(), clientIp, traceId);
        return requirePostmortem(incidentId);
    }

    public Postmortem updatePostmortem(UUID incidentId, OpsDtos.PostmortemReq req, String actor,
                                       String clientIp, String traceId) {
        requireActor(actor);
        Postmortem postmortem = requirePostmortem(incidentId);
        if (!"DRAFT".equals(postmortem.status())) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "只有草稿状态可以修改");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timeline", req.timeline() == null ? List.of() : req.timeline());
        fields.put("root_cause", req.rootCause());
        fields.put("contributing_factors",
                req.contributingFactors() == null ? List.of() : req.contributingFactors());
        fields.put("corrective_actions",
                req.correctiveActions() == null ? List.of() : req.correctiveActions());
        if (!repo.updatePostmortemFields(postmortem.id(), postmortem.version(), fields)) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "复盘已被他人修改，请刷新后重试");
        }
        audit(actor, "POSTMORTEM_DRAFT", requireIncident(incidentId).incidentNo(),
                Map.of("action", "UPDATE"), clientIp, traceId);
        return requirePostmortem(incidentId);
    }

    public Postmortem submitPostmortem(UUID incidentId, String actor,
                                       String clientIp, String traceId) {
        requireActor(actor);
        Postmortem postmortem = requirePostmortem(incidentId);
        if (!"DRAFT".equals(postmortem.status())) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "只有草稿可以提交复核");
        }
        if (postmortem.rootCause() == null || postmortem.rootCause().isBlank()
                || postmortem.correctiveActions() == null || postmortem.correctiveActions().isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_INCOMPLETE);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "REVIEWED");
        if (!repo.updatePostmortemFields(postmortem.id(), postmortem.version(), fields)) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "复盘已被他人修改，请刷新后重试");
        }
        Incident incident = requireIncident(incidentId);
        appendEvent(incident, actor, "ACTION", incident.status(), incident.status(),
                "提交复盘复核", Map.of("postmortemStatus", "REVIEWED"));
        audit(actor, "POSTMORTEM_SUBMIT", incident.incidentNo(), Map.of(), clientIp, traceId);
        return requirePostmortem(incidentId);
    }

    public Postmortem approvePostmortem(UUID incidentId, OpsDtos.PostmortemApproveReq req,
                                        String actor, String clientIp, String traceId) {
        requireActor(actor);
        Postmortem postmortem = requirePostmortem(incidentId);
        if (!"REVIEWED".equals(postmortem.status())) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "只有已提交复核的复盘可以审批");
        }
        if (actor.equals(postmortem.author())) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_SELF_REVIEW);
        }
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "APPROVED");
        fields.put("approved_by", actor);
        fields.put("approved_at", now);
        if (!repo.updatePostmortemFields(postmortem.id(), postmortem.version(), fields)) {
            throw new BusinessException(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID,
                    "复盘已被他人修改，请刷新后重试");
        }
        Incident incident = requireIncident(incidentId);
        String note = req == null || req.note() == null || req.note().isBlank()
                ? "复盘审批通过" : req.note();
        appendEvent(incident, actor, "ACTION", incident.status(), incident.status(), note,
                Map.of("postmortemStatus", "APPROVED"));
        audit(actor, "POSTMORTEM_APPROVE", incident.incidentNo(),
                Map.of("author", postmortem.author()), clientIp, traceId);
        return requirePostmortem(incidentId);
    }

    public Postmortem postmortem(UUID incidentId) {
        requireIncident(incidentId);
        return requirePostmortem(incidentId);
    }

    // ------------------------------------------------------------------ 内部

    public Incident requireIncident(UUID id) {
        return repo.findIncident(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_INCIDENT_NOT_FOUND));
    }

    public Postmortem requirePostmortem(UUID incidentId) {
        return repo.findPostmortem(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_POSTMORTEM_NOT_FOUND));
    }

    private Incident update(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (!repo.updateIncidentFields(id, expectedVersion, fields)) {
            throw new BusinessException(ErrorCode.OPS_INCIDENT_STATUS_INVALID,
                    "事件已被他人更新，请刷新后重试");
        }
        return requireIncident(id);
    }

    private void appendEvent(Incident incident, String actor, String action, String from,
                             String to, String note, Map<String, Object> detail) {
        repo.insertIncidentEvent(new IncidentEvent(0, incident.id(), clock.instant(), actor,
                action, from, to, note, detail == null ? Map.of() : detail));
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
}
