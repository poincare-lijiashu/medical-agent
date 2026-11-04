package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ReportDelivery;
import com.medassist.clinical.domain.ClinicalModel.ReportSnapshot;
import com.medassist.clinical.domain.ClinicalModel.ReportSubscription;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReportSubscriptionDtos.DeliveryView;
import com.medassist.clinical.web.ReportSubscriptionDtos.SaveSubscriptionReq;
import com.medassist.clinical.web.ReportSubscriptionDtos.SubscriptionView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 报表订阅与投递（G19）。
 *
 * <p>渠道：
 * <ul>
 *   <li>{@code LOG}——写服务日志（默认、无需外部依赖，便于验证与兜底）；</li>
 *   <li>{@code WEBHOOK}——向订阅配置的 https/http 地址 POST 日报 JSON；
 *       目标域名必须命中 {@code medassist.report-delivery.allowed-hosts}，
 *       否则拒绝创建/投递，避免把服务变成任意地址的 SSRF 跳板。</li>
 * </ul>
 *
 * <p>投递记录 (订阅, 日期, 报表) 唯一：定时任务重跑不会重复推送；失败按 attempts 重试，
 * 重试复用首次固化的 payload，避免跨日口径漂移。
 */
@Service
public class ReportSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(ReportSubscriptionService.class);

    public static final String REPORT_CODE = "DAILY_OPS";
    public static final int MAX_ATTEMPTS = 3;

    private static final int DEFAULT_DELIVERY_LIMIT = 100;
    private static final int MAX_DELIVERY_LIMIT = 200;

    private final ClinicalRepository repo;
    private final ReportSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final RestClient webhookClient;

    public ReportSubscriptionService(ClinicalRepository repo, ReportSnapshotService snapshotService,
                                     ObjectMapper objectMapper,
                                     @Value("${medassist.report-delivery.allowed-hosts:}")
                                     String allowedHosts,
                                     @Value("${medassist.report-delivery.timeout-ms:3000}")
                                     int timeoutMs) {
        this.repo = repo;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.allowedHosts = parseHosts(allowedHosts);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.webhookClient = RestClient.builder().requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- 订阅管理

    public List<SubscriptionView> listSubscriptions() {
        return repo.listReportSubscriptions().stream().map(this::toView).toList();
    }

    public SubscriptionView create(SaveSubscriptionReq req, String actor) {
        String name = requireName(req.name());
        String channel = requireChannel(req.channel());
        String target = validateTarget(channel, req.target());
        boolean enabled = req.enabled() == null || req.enabled();
        UUID id = UUID.randomUUID();
        try {
            repo.insertReportSubscription(new ReportSubscription(id, name, REPORT_CODE, channel,
                    target, enabled, 0L, actor, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "订阅名称已存在，请换一个名称");
        }
        audit(actor, "REPORT_SUBSCRIPTION_CREATE", id.toString(), Map.of(
                "name", name, "channel", channel));
        return repo.findReportSubscription(id).map(this::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订阅创建后读取失败"));
    }

    public SubscriptionView update(UUID id, SaveSubscriptionReq req, String actor) {
        ReportSubscription existing = repo.findReportSubscription(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该报表订阅"));
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "更新订阅必须携带 version");
        }
        String name = requireName(req.name());
        String channel = requireChannel(req.channel());
        String target = validateTarget(channel, req.target());
        boolean enabled = req.enabled() == null ? existing.enabled() : req.enabled();
        boolean ok;
        try {
            ok = repo.updateReportSubscription(id, name, channel, target, enabled, req.version());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "订阅名称已存在，请换一个名称");
        }
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFLICT, "订阅已被他人修改，请刷新后重试");
        }
        audit(actor, "REPORT_SUBSCRIPTION_UPDATE", id.toString(), Map.of(
                "name", name, "channel", channel, "enabled", enabled));
        return repo.findReportSubscription(id).map(this::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订阅更新后读取失败"));
    }

    // ---------------------------------------------------------------- 投递

    /** 手动投递（也用于失败重试）；已成功的投递幂等返回，不重复推送。 */
    public DeliveryView send(UUID subscriptionId, LocalDate date, String actor) {
        ReportSubscription sub = repo.findReportSubscription(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该报表订阅"));
        LocalDate day = date == null ? BusinessTime.today().minusDays(1) : date;
        if (day.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能投递未来日期的报表");
        }
        ensureSnapshot(day, actor);
        String payload = buildPayload(day);
        repo.insertReportDeliveryIfAbsent(UUID.randomUUID(), sub.id(), sub.reportCode(), day,
                payload);
        ReportDelivery delivery = repo.findReportDelivery(sub.id(), sub.reportCode(), day)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投递记录创建失败"));
        if ("SENT".equals(delivery.status())) {
            return toView(delivery);
        }
        audit(actor, "REPORT_DELIVERY_SEND", delivery.id().toString(), Map.of(
                "subscription", sub.name(), "date", day.toString()));
        return toView(deliver(sub, delivery));
    }

    /** 定时任务：为所有启用订阅生成当天投递占位（幂等），返回新建数量。 */
    public int enqueueDaily(LocalDate day, String actor) {
        ensureSnapshot(day, actor);
        String payload = buildPayload(day);
        int created = 0;
        for (ReportSubscription sub : repo.listReportSubscriptions()) {
            if (!sub.enabled()) {
                continue;
            }
            if (repo.insertReportDeliveryIfAbsent(UUID.randomUUID(), sub.id(), sub.reportCode(),
                    day, payload)) {
                created++;
            }
        }
        return created;
    }

    /** 定时任务：重试 PENDING/FAILED（attempts < 3），单轮最多 limit 条。 */
    public int retryPending(int limit) {
        int done = 0;
        for (ReportDelivery delivery : repo.listRetryableReportDeliveries(MAX_ATTEMPTS, limit)) {
            ReportSubscription sub = repo.findReportSubscription(delivery.subscriptionId())
                    .orElse(null);
            if (sub == null || !sub.enabled()) {
                continue;
            }
            ReportDelivery latest = deliver(sub, delivery);
            if ("SENT".equals(latest.status())) {
                done++;
            }
        }
        return done;
    }

    public List<DeliveryView> listDeliveries(String status, Integer limit) {
        int size = limit == null ? DEFAULT_DELIVERY_LIMIT
                : Math.max(1, Math.min(MAX_DELIVERY_LIMIT, limit));
        return repo.listReportDeliveries(status, size).stream().map(this::toView).toList();
    }

    // ---------------------------------------------------------------- 内部

    private ReportDelivery deliver(ReportSubscription sub, ReportDelivery delivery) {
        int attempts = delivery.attempts() + 1;
        String payload = repo.reportDeliveryPayload(delivery.id());
        try {
            if ("WEBHOOK".equals(sub.channel())) {
                postWebhook(sub.target(), payload == null ? buildPayload(delivery.reportDate()) : payload);
            } else {
                log.info("report.delivery.log subscription={} date={} payload={}",
                        sub.name(), delivery.reportDate(), payload);
            }
            repo.markReportDelivery(delivery.id(), "SENT", attempts, null);
        } catch (RuntimeException e) {
            repo.markReportDelivery(delivery.id(), "FAILED", attempts, truncate(e.getMessage()));
            log.warn("report.delivery.failed subscription={} date={} attempts={} reason={}",
                    sub.name(), delivery.reportDate(), attempts, e.getMessage());
        }
        return repo.findReportDelivery(delivery.subscriptionId(), delivery.reportCode(),
                        delivery.reportDate())
                .orElse(delivery);
    }

    private void postWebhook(String target, String payload) {
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("WEBHOOK 订阅缺少目标地址");
        }
        webhookClient.post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private void ensureSnapshot(LocalDate day, String actor) {
        if (repo.listReportSnapshots(day, day, null).isEmpty()) {
            snapshotService.capture(day, actor, "MANUAL");
        }
    }

    /** 日报正文：日期 + 指标集合（指标 detail 解析为 JSON，便于下游直接消费）。 */
    String buildPayload(LocalDate day) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report", REPORT_CODE);
        payload.put("date", day.toString());
        payload.put("generatedAt", Instant.now().toString());
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (ReportSnapshot s : repo.listReportSnapshots(day, day, null)) {
            if (s.detail() == null || s.detail().isBlank()) {
                metrics.put(s.metric(), s.value());
            } else {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("value", s.value());
                detail.put("detail", readJson(s.detail()));
                metrics.put(s.metric(), detail);
            }
        }
        payload.put("metrics", metrics);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "日报内容序列化失败");
        }
    }

    private Object readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException e) {
            return raw;
        }
    }

    private SubscriptionView toView(ReportSubscription s) {
        return new SubscriptionView(s.id(), s.name(), s.reportCode(), s.channel(), s.target(),
                s.enabled(), s.version(), s.createdBy(), s.updatedAt());
    }

    private DeliveryView toView(ReportDelivery d) {
        return new DeliveryView(d.id(), d.subscriptionId(), d.subscriptionName(), d.reportCode(),
                d.reportDate(), d.status(), d.attempts(), d.lastError(), d.createdAt(), d.sentAt());
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "订阅名称不能为空");
        }
        return raw.trim();
    }

    private static String requireChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "渠道不能为空");
        }
        String channel = raw.trim().toUpperCase(Locale.ROOT);
        if (!"LOG".equals(channel) && !"WEBHOOK".equals(channel)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "渠道只支持 LOG 或 WEBHOOK");
        }
        return channel;
    }

    private String validateTarget(String channel, String rawTarget) {
        if ("LOG".equals(channel)) {
            return null;
        }
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "WEBHOOK 渠道必须填写目标地址");
        }
        URI uri;
        try {
            uri = URI.create(rawTarget.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推送地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推送地址必须是 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推送地址缺少合法域名");
        }
        if (allowedHosts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "未配置允许的推送域名（medassist.report-delivery.allowed-hosts），暂不能创建 WEBHOOK 订阅");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "推送域名不在允许列表内：" + host);
        }
        return uri.toString();
    }

    private static Set<String> parseHosts(String raw) {
        Set<String> hosts = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return hosts;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(hosts::add);
        return hosts;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "REPORT_SUBSCRIPTION", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "REPORT_SUBSCRIPTION", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
