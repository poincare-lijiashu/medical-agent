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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /** 需要 HTTP 目标地址的渠道：WEBHOOK 发原始 JSON，其余发对应机器人消息格式。 */
    private static final Set<String> HTTP_CHANNELS =
            new LinkedHashSet<>(List.of("WEBHOOK", "DINGTALK", "WECOM", "FEISHU"));
    private static final Set<String> ALL_CHANNELS =
            new LinkedHashSet<>(List.of("LOG", "WEBHOOK", "DINGTALK", "WECOM", "FEISHU", "EMAIL"));

    /** 邮件收件人上限：超过后单封邮件容易被网关判为群发垃圾邮件。 */
    private static final int MAX_EMAIL_RECIPIENTS = 10;

    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    /** 指标中文名：邮件正文与 CSV 附件用，未知指标回退编码本身。 */
    private static final Map<String, String> METRIC_LABELS = Map.ofEntries(
            Map.entry("OUTPATIENT_PAID_AMOUNT", "门诊实收（元）"),
            Map.entry("OUTPATIENT_VISIT_COUNT", "门诊人次"),
            Map.entry("INPATIENT_ADMITTED_COUNT", "入院人数"),
            Map.entry("INPATIENT_DISCHARGED_COUNT", "出院人数"),
            Map.entry("INPATIENT_IN_HOSPITAL_COUNT", "在院人数"),
            Map.entry("BED_OCCUPANCY_PERCENT", "床位使用率（%）"),
            Map.entry("DISPENSE_COUNT", "发药笔数"),
            Map.entry("DISPENSE_AMOUNT", "发药金额（元）"),
            Map.entry("ISSUE_COUNT", "出库单数"),
            Map.entry("ISSUE_ITEM_QUANTITY", "出库件数"),
            Map.entry("ISSUE_AMOUNT", "出库金额（元）"));

    private static final int DEFAULT_DELIVERY_LIMIT = 100;
    private static final int MAX_DELIVERY_LIMIT = 200;

    private final ClinicalRepository repo;
    private final ReportSnapshotService snapshotService;
    private final ReportEmailSender emailSender;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final RestClient webhookClient;

    public ReportSubscriptionService(ClinicalRepository repo, ReportSnapshotService snapshotService,
                                     ReportEmailSender emailSender, ObjectMapper objectMapper,
                                     @Value("${medassist.report-delivery.allowed-hosts:}")
                                     String allowedHosts,
                                     @Value("${medassist.report-delivery.timeout-ms:3000}")
                                     int timeoutMs) {
        this.repo = repo;
        this.snapshotService = snapshotService;
        this.emailSender = emailSender;
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
        ensureChannelReady(channel);
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
        ensureChannelReady(channel);
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
        LocalDate day = delivery.reportDate();
        try {
            if ("EMAIL".equals(sub.channel())) {
                String raw = payload == null ? buildPayload(day) : payload;
                emailSender.send(sub.target(), buildEmailSubject(day),
                        buildEmailHtml(day, raw), emailAttachmentName(day),
                        buildEmailCsv(day, raw));
            } else if (HTTP_CHANNELS.contains(sub.channel())) {
                postChannel(sub.channel(), sub.target(),
                        payload == null ? buildPayload(day) : payload, day);
            } else {
                log.info("report.delivery.log subscription={} date={} payload={}",
                        sub.name(), day, payload);
            }
            repo.markReportDelivery(delivery.id(), "SENT", attempts, null);
        } catch (RuntimeException e) {
            repo.markReportDelivery(delivery.id(), "FAILED", attempts, truncate(e.getMessage()));
            log.warn("report.delivery.failed subscription={} date={} attempts={} reason={}",
                    sub.name(), day, attempts, e.getMessage());
        }
        return repo.findReportDelivery(delivery.subscriptionId(), delivery.reportCode(),
                        delivery.reportDate())
                .orElse(delivery);
    }

    private void postChannel(String channel, String target, String rawPayload, LocalDate day) {
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("该渠道缺少目标地址");
        }
        String body = buildChannelBody(channel, day, rawPayload);
        String response = webhookClient.post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        validateChannelResponse(channel, response);
    }

    /** 按渠道生成请求体：WEBHOOK 透传日报 JSON，IM 转成机器人消息格式。 */
    String buildChannelBody(String channel, LocalDate day, String rawPayload) {
        if ("WEBHOOK".equals(channel)) {
            return rawPayload;
        }
        String text = buildMessageText(day, rawPayload);
        Map<String, Object> body = new LinkedHashMap<>();
        switch (channel) {
            case "DINGTALK" -> {
                body.put("msgtype", "markdown");
                body.put("markdown", Map.of("title", "运营日报 " + day, "text", text));
            }
            case "WECOM" -> {
                body.put("msgtype", "markdown");
                body.put("markdown", Map.of("content", text));
            }
            case "FEISHU" -> {
                body.put("msg_type", "text");
                body.put("content", Map.of("text", text));
            }
            default -> throw new IllegalStateException("未知渠道：" + channel);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new IllegalStateException("机器人消息序列化失败");
        }
    }

    /** 人可读日报正文（企业 IM 不支持表格，用分行要点）。 */
    String buildMessageText(LocalDate day, String rawPayload) {
        JsonNode metrics;
        try {
            metrics = objectMapper.readTree(rawPayload).path("metrics");
        } catch (JacksonException e) {
            throw new IllegalStateException("日报内容解析失败");
        }
        StringBuilder text = new StringBuilder("### 运营日报 ").append(day);
        if (hasMetric(metrics, "OUTPATIENT_PAID_AMOUNT")) {
            text.append("\n- 门诊实收：¥").append(metricText(metrics, "OUTPATIENT_PAID_AMOUNT", "0"))
                    .append("（挂号 ").append(metricText(metrics, "OUTPATIENT_VISIT_COUNT", "0"))
                    .append(" 人次）");
        }
        if (hasMetric(metrics, "INPATIENT_ADMITTED_COUNT")) {
            text.append("\n- 住院：入院 ").append(metricText(metrics, "INPATIENT_ADMITTED_COUNT", "0"))
                    .append(" / 出院 ").append(metricText(metrics, "INPATIENT_DISCHARGED_COUNT", "0"))
                    .append(" / 在院 ").append(metricText(metrics, "INPATIENT_IN_HOSPITAL_COUNT", "0"))
                    .append(" 人（床位使用率 ")
                    .append(metricText(metrics, "BED_OCCUPANCY_PERCENT", "0")).append("%）");
        }
        if (hasMetric(metrics, "DISPENSE_COUNT")) {
            text.append("\n- 药事：发药 ").append(metricText(metrics, "DISPENSE_COUNT", "0"))
                    .append(" 笔 / ¥").append(metricText(metrics, "DISPENSE_AMOUNT", "0"));
        }
        if (hasMetric(metrics, "ISSUE_COUNT")) {
            text.append("\n- 物资：出库 ").append(metricText(metrics, "ISSUE_COUNT", "0"))
                    .append(" 单 / ").append(metricText(metrics, "ISSUE_ITEM_QUANTITY", "0"))
                    .append(" 件 / ¥").append(metricText(metrics, "ISSUE_AMOUNT", "0"));
        }
        return text.append("\n").toString();
    }

    /** 指标是否存在：缺失的域不写"0"，避免把"没采集"误报成"业务量为 0"。 */
    private static boolean hasMetric(JsonNode metrics, String key) {
        JsonNode node = metrics.path(key);
        return !node.isMissingNode() && !node.isNull();
    }

    private static String metricText(JsonNode metrics, String key, String fallback) {
        JsonNode node = metrics.path(key);
        if (node.isObject()) {
            node = node.path("value");
        }
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        // 快照金额/比例以 numeric 存储，去掉 4.00 → 4、0.00 → 0，IM 正文更易读
        return node.isNumber() ? node.decimalValue().stripTrailingZeros().toPlainString()
                : node.asText();
    }

    /** IM 机器人 HTTP 200 仍可能业务失败：解析 errcode/code 并转成投递失败。 */
    private void validateChannelResponse(String channel, String response) {
        if (!HTTP_CHANNELS.contains(channel) || "WEBHOOK".equals(channel)) {
            return;
        }
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("IM 返回空响应");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(response);
        } catch (JacksonException e) {
            throw new IllegalStateException("IM 响应不是合法 JSON");
        }
        String codeField = "FEISHU".equals(channel) ? "code" : "errcode";
        int code = node.path(codeField).asInt(-1);
        if (code != 0) {
            String message = node.path("errmsg").asText(node.path("msg").asText("未知错误"));
            throw new IllegalStateException("IM 返回错误 " + code + "：" + message);
        }
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
        if (!ALL_CHANNELS.contains(channel)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "渠道只支持 LOG / WEBHOOK / DINGTALK / WECOM / FEISHU / EMAIL");
        }
        return channel;
    }

    /** 渠道前置条件：EMAIL 必须先配置 SMTP，否则订阅建了也发不出去。 */
    private void ensureChannelReady(String channel) {
        if ("EMAIL".equals(channel) && !emailSender.configured()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置邮件服务器（medassist.report-email.host/from），暂不能创建 EMAIL 订阅");
        }
    }

    private String validateTarget(String channel, String rawTarget) {
        if ("EMAIL".equals(channel)) {
            return validateEmailTarget(rawTarget);
        }
        if (!HTTP_CHANNELS.contains(channel)) {
            return null;
        }
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该渠道必须填写目标地址");
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
                    "未配置允许的推送域名（medassist.report-delivery.allowed-hosts），暂不能创建 WEBHOOK/IM 订阅");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "推送域名不在允许列表内：" + host);
        }
        return uri.toString();
    }

    /** 校验并规范化邮件收件人（逗号/分号分隔，最多 10 个）。 */
    private static String validateEmailTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "EMAIL 渠道必须填写收件人地址");
        }
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        for (String part : rawTarget.split("[;,]")) {
            String email = part.trim();
            if (email.isEmpty()) {
                continue;
            }
            if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "收件人邮箱格式不正确：" + email);
            }
            recipients.add(email);
        }
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "EMAIL 渠道必须填写收件人地址");
        }
        if (recipients.size() > MAX_EMAIL_RECIPIENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单封邮件收件人不得超过 " + MAX_EMAIL_RECIPIENTS + " 个");
        }
        String joined = String.join(",", recipients);
        if (joined.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "收件人地址总长度不得超过 512 字符");
        }
        return joined;
    }

    String buildEmailSubject(LocalDate day) {
        return "运营日报 " + day;
    }

    String emailAttachmentName(LocalDate day) {
        return "daily-ops-" + day + ".csv";
    }

    /** 邮件 HTML 正文：指标表格，缺失的域不写 0（与 IM 正文口径一致）。 */
    String buildEmailHtml(LocalDate day, String rawPayload) {
        List<MetricRow> rows = metricRows(rawPayload);
        StringBuilder html = new StringBuilder(1024);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"></head>")
                .append("<body style=\"margin:0;padding:24px;background:#f7f8fa;")
                .append("font-family:Arial,'Microsoft YaHei',sans-serif;color:#1f2937\">")
                .append("<div style=\"max-width:820px;margin:0 auto;background:#fff;")
                .append("border:1px solid #e5e7eb;border-radius:8px;padding:24px\">")
                .append("<h2 style=\"margin:0 0 8px;font-size:20px\">运营日报 ")
                .append(escapeHtml(day.toString())).append("</h2>")
                .append("<p style=\"margin:0 0 16px;color:#6b7280;font-size:13px\">共 ")
                .append(rows.size())
                .append(" 项指标；未采集到的业务域不会写成 0。附件为同口径 CSV。</p>")
                .append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">")
                .append("<thead><tr style=\"background:#f3f4f6\">")
                .append("<th style=\"text-align:left;padding:8px;border:1px solid #e5e7eb\">指标</th>")
                .append("<th style=\"text-align:right;padding:8px;border:1px solid #e5e7eb\">数值</th>")
                .append("<th style=\"text-align:left;padding:8px;border:1px solid #e5e7eb\">明细</th>")
                .append("</tr></thead><tbody>");
        for (MetricRow row : rows) {
            html.append("<tr>")
                    .append("<td style=\"padding:8px;border:1px solid #e5e7eb\">")
                    .append(escapeHtml(row.label()))
                    .append("<div style=\"color:#9ca3af;font-size:11px\">")
                    .append(escapeHtml(row.code())).append("</div></td>")
                    .append("<td style=\"padding:8px;border:1px solid #e5e7eb;text-align:right;")
                    .append("font-family:Consolas,monospace\">")
                    .append(escapeHtml(row.value())).append("</td>")
                    .append("<td style=\"padding:8px;border:1px solid #e5e7eb;color:#6b7280\">")
                    .append(escapeHtml(row.detail())).append("</td></tr>");
        }
        html.append("</tbody></table>")
                .append("<p style=\"margin:16px 0 0;color:#9ca3af;font-size:12px\">")
                .append("本邮件由 MedAssist 运营日报自动投递；如需调整订阅请联系系统管理员。</p>")
                .append("</div></body></html>");
        return html.toString();
    }

    /** 邮件附件 CSV：UTF-8 BOM + 逗号分隔，Excel 可直接打开。 */
    byte[] buildEmailCsv(LocalDate day, String rawPayload) {
        List<MetricRow> rows = metricRows(rawPayload);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("日期,指标编码,指标名称,数值,明细\r\n");
        for (MetricRow row : rows) {
            csv.append(csvField(day.toString())).append(',')
                    .append(csvField(row.code())).append(',')
                    .append(csvField(row.label())).append(',')
                    .append(csvField(row.value())).append(',')
                    .append(csvField(row.detail())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record MetricRow(String code, String label, String value, String detail) {
    }

    private List<MetricRow> metricRows(String rawPayload) {
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(rawPayload, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("日报内容解析失败");
        }
        Object rawMetrics = body.get("metrics");
        if (!(rawMetrics instanceof Map<?, ?> metrics)) {
            return List.of();
        }
        List<MetricRow> rows = new ArrayList<>();
        for (Map.Entry<?, ?> entry : metrics.entrySet()) {
            String code = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            String number;
            String detail = "";
            if (value instanceof Map<?, ?> obj) {
                number = scalar(obj.get("value"));
                if (obj.get("detail") != null) {
                    detail = writeJson(obj.get("detail"));
                }
            } else {
                number = scalar(value);
            }
            rows.add(new MetricRow(code, METRIC_LABELS.getOrDefault(code, code), number, detail));
        }
        return rows;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return number.toString();
            }
        }
        return String.valueOf(value);
    }

    private static String escapeHtml(String raw) {
        return raw == null ? "" : raw.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String csvField(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
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
