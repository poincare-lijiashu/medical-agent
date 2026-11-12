package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.HookTokens;
import com.medassist.ops.domain.OpsIntegrationModel.AlertSource;
import com.medassist.ops.domain.OpsIntegrationModel.AlertState;
import com.medassist.ops.domain.OpsIntegrationModel.HookEvent;
import com.medassist.ops.domain.OpsIntegrationModel.SourceCreated;
import com.medassist.ops.domain.OpsModel;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.repo.AlertIntegrationRepository;
import com.medassist.ops.repo.AlertIntegrationRepository.StoredSource;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import com.medassist.ops.web.OpsIntegrationDtos.AlertItem;
import com.medassist.ops.web.OpsIntegrationDtos.AlertSourceReq;
import com.medassist.ops.web.OpsIntegrationDtos.AlertmanagerPayload;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 监控告警接入（§276）。
 *
 * <p>把"告警 → 工单"的机械动作交给系统，但保留人的处理权：
 * <ul>
 *   <li>firing：按 fingerprint 建单；重复通知只在工单上追加说明（可节流），不重复建单；</li>
 *   <li>resolved：按接入源配置自动接单并解决；工单在人工接单前已恢复则取消并说明；</li>
 *   <li>所有入站都留一条 hook_event，响应摘要告诉接入方"建了几单、忽略了几条"。</li>
 * </ul>
 */
@Service
public class AlertIngestService {

    /** 默认 severity → 优先级；接入源可覆盖，值为 IGNORE 表示只留痕不建单。 */
    private static final Map<String, String> DEFAULT_SEVERITY_MAP = Map.of(
            "critical", "P1",
            "warning", "P2",
            "info", "P3");
    private static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4", "IGNORE");
    private static final String FALLBACK_SERVICE = "infra-monitoring";
    private static final int MAX_TITLE = 200;

    private final AlertIntegrationRepository repo;
    private final OpsRepository opsRepo;
    private final TicketService ticketService;

    public AlertIngestService(AlertIntegrationRepository repo, OpsRepository opsRepo,
                              TicketService ticketService) {
        this.repo = repo;
        this.opsRepo = opsRepo;
        this.ticketService = ticketService;
    }

    // ------------------------------------------------------------------ 接入源管理

    public List<AlertSource> listSources() {
        return repo.listSources();
    }

    public List<HookEvent> listEvents(String sourceCode, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return repo.listEvents(blankToNull(sourceCode), size);
    }

    public SourceCreated createSource(AlertSourceReq req, String actor, String clientIp,
                                      String traceId) {
        requireActor(actor);
        if (repo.findSource(req.code()).isPresent()) {
            throw new BusinessException(ErrorCode.OPS_INTEGRATION_PAYLOAD_INVALID,
                    "接入源编码已存在：" + req.code());
        }
        String defaultService = resolveDefaultService(req.defaultService());
        String token = HookTokens.generate("oa");
        AlertSource source = new AlertSource(UUID.randomUUID(), req.code(), req.name(),
                req.kind() == null ? "ALERTMANAGER" : req.kind(),
                HookTokens.hint(token), defaultService, sanitizeSeverity(req.severityMap()),
                req.autoResolve() == null || req.autoResolve(),
                req.repeatCommentMinutes() == null ? 60 : req.repeatCommentMinutes(),
                "ACTIVE", null, actor, 0, Instant.now(), Instant.now());
        repo.insertSource(source, HookTokens.hash(token));
        opsRepo.audit(actor, "INTEGRATION_SOURCE_CREATE", source.code(),
                Map.of("kind", source.kind(), "autoResolve", source.autoResolve()),
                clientIp, traceId);
        return new SourceCreated(source, token);
    }

    public SourceCreated rotate(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        AlertSource source = repo.findSourceMeta(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_INTEGRATION_SOURCE_NOT_FOUND));
        String token = HookTokens.generate("oa");
        repo.updateToken(id, HookTokens.hash(token), HookTokens.hint(token));
        opsRepo.audit(actor, "INTEGRATION_SOURCE_ROTATE", source.code(), Map.of(), clientIp, traceId);
        return new SourceCreated(source, token);
    }

    public AlertSource disable(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        AlertSource source = repo.findSourceMeta(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_INTEGRATION_SOURCE_NOT_FOUND));
        repo.disable(id);
        opsRepo.audit(actor, "INTEGRATION_SOURCE_DISABLE", source.code(), Map.of(), clientIp, traceId);
        return repo.findSourceMeta(id).orElse(source);
    }

    // ------------------------------------------------------------------ 入站

    /**
     * 处理一次 Alertmanager 通知。
     *
     * @return 处理摘要（received/created/commented/resolved/ignored/deduped/failed）
     */
    public Map<String, Object> ingest(String sourceCode, String authorization,
                                      AlertmanagerPayload payload, String clientIp,
                                      String traceId) {
        StoredSource stored = repo.findSource(sourceCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_INTEGRATION_TOKEN_INVALID));
        String token = HookTokens.bearer(authorization);
        if (!"ACTIVE".equals(stored.source().status())
                || !HookTokens.matches(token, stored.tokenHash())) {
            // 不区分"源不存在 / 已停用 / 令牌错"，避免帮助攻击者枚举接入源
            throw new BusinessException(ErrorCode.OPS_INTEGRATION_TOKEN_INVALID);
        }
        if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_INTEGRATION_PAYLOAD_INVALID);
        }
        repo.touchUsed(sourceCode);

        AlertSource source = stored.source();
        String actor = "integration:" + sourceCode;
        Map<String, Object> summary = new LinkedHashMap<>();
        int received = payload.alerts().size();
        int created = 0;
        int commented = 0;
        int resolved = 0;
        int ignored = 0;
        int deduped = 0;
        int failed = 0;

        for (AlertItem alert : payload.alerts()) {
            String action = processAlert(source, actor, alert, clientIp, traceId);
            switch (action) {
                case "CREATED" -> created++;
                case "COMMENTED" -> commented++;
                case "RESOLVED" -> resolved++;
                case "FAILED" -> failed++;
                case "IGNORED" -> ignored++;
                default -> deduped++;
            }
        }
        summary.put("source", sourceCode);
        summary.put("received", received);
        summary.put("created", created);
        summary.put("commented", commented);
        summary.put("resolved", resolved);
        summary.put("ignored", ignored);
        summary.put("deduped", deduped);
        summary.put("failed", failed);
        return summary;
    }

    private String processAlert(AlertSource source, String actor, AlertItem alert,
                                String clientIp, String traceId) {
        String status = alert.status() == null ? "" : alert.status().toLowerCase(Locale.ROOT);
        if (!status.equals("firing") && !status.equals("resolved")) {
            throw new BusinessException(ErrorCode.OPS_INTEGRATION_PAYLOAD_INVALID,
                    "告警状态必须是 firing 或 resolved");
        }
        Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
        Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();
        String alertname = labels.getOrDefault("alertname", "unnamed-alert");
        String severity = labels.getOrDefault("severity", "warning").toLowerCase(Locale.ROOT);
        String fingerprint = alert.fingerprint() != null && !alert.fingerprint().isBlank()
                ? alert.fingerprint()
                : deriveFingerprint(source.code(), alertname, labels, alert.startsAt());
        String title = title(alertname, annotations.get("summary"), severity);
        Instant startsAt = parseInstant(alert.startsAt(), Instant.now());
        Map<String, Object> payload = alertPayload(alert, labels, annotations);

        return status.equals("firing")
                ? onFiring(source, actor, alert, labels, annotations, alertname, severity,
                        fingerprint, title, startsAt, payload, clientIp, traceId)
                : onResolved(source, actor, alert, alertname, fingerprint, title, payload,
                        clientIp, traceId);
    }

    private String onFiring(AlertSource source, String actor, AlertItem alert,
                            Map<String, String> labels, Map<String, String> annotations,
                            String alertname, String severity, String fingerprint, String title,
                            Instant startsAt, Map<String, Object> payload,
                            String clientIp, String traceId) {
        AlertState existing = repo.findState(source.code(), fingerprint).orElse(null);
        Instant now = Instant.now();
        String priority = priorityOf(source, severity);

        if ("IGNORE".equals(priority)) {
            if (existing != null && "IGNORED".equals(existing.status())) {
                return "DEDUPED";
            }
            repo.upsertState(new AlertState(source.code(), fingerprint, "IGNORED", alertname,
                    severity, null, null, title, null, startsAt, now, null, objectMap(labels),
                    objectMap(annotations), 0));
            repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "IGNORED", null,
                    "severity=" + severity + " 配置为忽略", payload, traceId), traceId);
            return "IGNORED";
        }

        if (existing != null && "FIRING".equals(existing.status())) {
            boolean throttle = source.repeatCommentMinutes() > 0 && existing.lastSeenAt() != null
                    && Duration.between(existing.lastSeenAt(), now).toMinutes()
                    < source.repeatCommentMinutes();
            repo.upsertState(new AlertState(source.code(), fingerprint, "FIRING", alertname,
                    severity, priority, existing.serviceCode(), title, existing.ticketId(),
                    startsAt, now, null, objectMap(labels), objectMap(annotations),
                    existing.version() + 1));
            if (throttle || existing.ticketId() == null) {
                return "DEDUPED";
            }
            try {
                ticketService.comment(existing.ticketId(), "监控告警持续：" + title, actor,
                        clientIp, traceId);
                repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint,
                        "TICKET_COMMENTED", existing.ticketId(), "重复通知，已追加说明",
                        payload, traceId), traceId);
                return "COMMENTED";
            } catch (RuntimeException e) {
                repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "FAILED",
                        existing.ticketId(), "重复通知写工单失败：" + e.getMessage(), payload,
                        traceId), traceId);
                return "FAILED";
            }
        }

        String serviceCode = resolveService(source, labels);
        try {
            Ticket ticket = ticketService.create(new OpsDtos.TicketCreateReq(
                            title, description(labels, annotations, alert), serviceCode, priority,
                            "监控平台", "MONITOR", tags(alertname, severity, labels)),
                    actor, clientIp, traceId);
            repo.upsertState(new AlertState(source.code(), fingerprint, "FIRING", alertname,
                    severity, priority, serviceCode, title, ticket.id(), startsAt, now, null,
                    objectMap(labels), objectMap(annotations), 0));
            repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "TICKET_CREATED",
                    ticket.id(), ticket.ticketNo() + " / " + priority + " / " + serviceCode,
                    payload, traceId), traceId);
            return "CREATED";
        } catch (RuntimeException e) {
            repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "FAILED", null,
                    "建单失败：" + e.getMessage(), payload, traceId), traceId);
            return "FAILED";
        }
    }

    private String onResolved(AlertSource source, String actor, AlertItem alert, String alertname,
                              String fingerprint, String title, Map<String, Object> payload,
                              String clientIp, String traceId) {
        Instant now = Instant.now();
        AlertState existing = repo.findState(source.code(), fingerprint).orElse(null);
        if (existing == null || "RESOLVED".equals(existing.status())) {
            repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "DEDUPED", null,
                    "未匹配到 firing 记录（或已恢复），忽略", payload, traceId), traceId);
            return "DEDUPED";
        }
        if ("IGNORED".equals(existing.status())) {
            repo.upsertState(new AlertState(source.code(), fingerprint, "RESOLVED", alertname,
                    existing.severity(), existing.priority(), existing.serviceCode(), title, null,
                    existing.startsAt(), now, now, existing.labels(), existing.annotations(),
                    existing.version() + 1));
            return "IGNORED";
        }

        boolean resolvedOk = true;
        if (source.autoResolve() && existing.ticketId() != null) {
            try {
                closeTicket(existing.ticketId(), title, actor, clientIp, traceId);
            } catch (RuntimeException e) {
                resolvedOk = false;
                repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "FAILED",
                        existing.ticketId(), "自动解决失败：" + e.getMessage(), payload, traceId),
                        traceId);
            }
        }
        repo.upsertState(new AlertState(source.code(), fingerprint, "RESOLVED", alertname,
                existing.severity(), existing.priority(), existing.serviceCode(), title,
                existing.ticketId(), existing.startsAt(), now, now, existing.labels(),
                existing.annotations(), existing.version() + 1));
        if (resolvedOk) {
            repo.insertEvent(new HookEvent(0, now, source.code(), fingerprint, "TICKET_RESOLVED",
                    existing.ticketId(), source.autoResolve() ? "告警恢复，已自动解决工单"
                            : "告警恢复（接入源关闭自动解决）", payload, traceId), traceId);
            return "RESOLVED";
        }
        return "FAILED";
    }

    /**
     * 告警恢复时把工单推进到终态。
     *
     * <p>工单状态机不允许"新建 → 解决"或"已派单 → 解决"，因此这里按状态补齐前置动作；
     * 人工接单前已恢复的工单直接取消，避免占用一线队列。
     */
    private void closeTicket(UUID ticketId, String title, String actor, String clientIp,
                             String traceId) {
        Ticket ticket = ticketService.detail(ticketId, 1);
        String status = ticket.status();
        if (OpsModel.CLOSED.equals(status) || OpsModel.CANCELLED.equals(status)) {
            return;
        }
        if (OpsModel.NEW.equals(status)) {
            ticketService.cancel(ticketId, "监控告警在人工接单前已恢复，系统自动取消", actor,
                    clientIp, traceId);
            return;
        }
        if (OpsModel.ASSIGNED.equals(status)) {
            ticketService.accept(ticketId, actor, clientIp, traceId);
        } else if (OpsModel.PENDING.equals(status)) {
            ticketService.resume(ticketId, "监控告警已恢复", actor, clientIp, traceId);
        } else if (OpsModel.REOPENED.equals(status)) {
            ticketService.accept(ticketId, actor, clientIp, traceId);
        }
        ticketService.resolve(ticketId, "监控告警已恢复：" + title, actor, clientIp, traceId);
    }

    // ------------------------------------------------------------------ 映射辅助

    private String resolveService(AlertSource source, Map<String, String> labels) {
        for (String key : List.of("service", "app", "job", "component")) {
            String candidate = labels.get(key);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            ServiceCatalog catalog = opsRepo.findService(normalized).orElse(null);
            if (catalog != null && "ACTIVE".equals(catalog.status())) {
                return catalog.serviceCode();
            }
        }
        if (source.defaultService() != null && opsRepo.findService(source.defaultService())
                .filter(s -> "ACTIVE".equals(s.status())).isPresent()) {
            return source.defaultService();
        }
        if (opsRepo.findService(FALLBACK_SERVICE)
                .filter(s -> "ACTIVE".equals(s.status())).isPresent()) {
            return FALLBACK_SERVICE;
        }
        throw new BusinessException(ErrorCode.OPS_INTEGRATION_PAYLOAD_INVALID,
                "无法从告警标签映射到服务目录，请在接入源配置默认服务");
    }

    private String priorityOf(AlertSource source, String severity) {
        String mapped = source.severityMap().getOrDefault(severity,
                DEFAULT_SEVERITY_MAP.getOrDefault(severity, "P3"));
        String normalized = mapped == null ? "P3" : mapped.trim().toUpperCase(Locale.ROOT);
        return PRIORITIES.contains(normalized) ? normalized : "P3";
    }

    private String resolveDefaultService(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return opsRepo.findService(code.trim())
                .filter(s -> "ACTIVE".equals(s.status()))
                .map(ServiceCatalog::serviceCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_INTEGRATION_PAYLOAD_INVALID,
                        "默认服务不存在或已停用：" + code));
    }

    private static Map<String, String> sanitizeSeverity(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>(DEFAULT_SEVERITY_MAP);
        if (input == null) {
            return result;
        }
        input.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (PRIORITIES.contains(normalized)) {
                result.put(key.trim().toLowerCase(Locale.ROOT), normalized);
            }
        });
        return result;
    }

    private static String title(String alertname, String summary, String severity) {
        String text = "[" + severity.toUpperCase(Locale.ROOT) + "] " + alertname
                + (summary == null || summary.isBlank() ? "" : "：" + summary.trim());
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE);
    }

    private static String description(Map<String, String> labels, Map<String, String> annotations,
                                      AlertItem alert) {
        StringBuilder sb = new StringBuilder();
        String detail = annotations.get("description");
        if (detail != null && !detail.isBlank()) {
            sb.append(detail.trim());
        }
        if (alert.generatorURL() != null && !alert.generatorURL().isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("来源：").append(alert.generatorURL().trim());
        }
        if (!labels.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("标签：").append(labels);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static List<String> tags(String alertname, String severity,
                                     Map<String, String> labels) {
        List<String> tags = new ArrayList<>();
        tags.add(alertname);
        tags.add(severity);
        String instance = labels.get("instance");
        if (instance != null && !instance.isBlank()) {
            tags.add(instance.length() > 64 ? instance.substring(0, 64) : instance);
        }
        return tags;
    }

    private static Map<String, Object> alertPayload(AlertItem alert, Map<String, String> labels,
                                                    Map<String, String> annotations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", alert.status());
        payload.put("labels", labels);
        payload.put("annotations", annotations);
        payload.put("startsAt", alert.startsAt());
        payload.put("endsAt", alert.endsAt());
        payload.put("generatorURL", alert.generatorURL());
        return payload;
    }

    private static String deriveFingerprint(String source, String alertname,
                                            Map<String, String> labels, String startsAt) {
        String raw = source + "|" + alertname + "|" + labels.getOrDefault("instance", "")
                + "|" + (startsAt == null ? "" : startsAt);
        return "fp-" + HookTokens.hash(raw).substring(0, 24);
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            Instant parsed = Instant.parse(value);
            // Alertmanager 用 0001-01-01 表示"未结束"，不能当成真实时间
            return parsed.isBefore(Instant.parse("2000-01-01T00:00:00Z")) ? fallback : parsed;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Object> objectMap(Map<String, String> value) {
        return new LinkedHashMap<>(value == null ? Map.of() : value);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }
}
