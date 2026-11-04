package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPush;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPushLedger;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPushSummary;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushSummaryView;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 电子票据厂商报文适配层（G21 / G24）。
 *
 * <p>把院内票据推送到财政/第三方电子票据平台：
 * <ul>
 *   <li>只有 {@code ISSUED} 票据可开具推送；{@code VOID} 票据只能红冲，且必须有成功的原开具报文；</li>
 *   <li>同一票据同一平台同一报文类型唯一（ISSUE / RED_FLUSH），重复调用幂等返回；</li>
 *   <li>报文按厂商模板生成：vendor / templateVersion / messageType / 医疗机构代码 / 票面快照；</li>
 *   <li>请求体 HMAC-SHA256 签名（{@code X-Signature: sha256=hex}），密钥由部署配置注入；</li>
 *   <li>平台地址域名必须命中 {@code medassist.receipt-push.allowed-hosts}，避免 SSRF；</li>
 *   <li>平台 HTTP 200 仍解析业务码，非 0 记为失败并保留原因；失败可手动重试。</li>
 * </ul>
 */
@Service
public class ReceiptPushService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPushService.class);
    private static final String PLATFORM = "FINANCE";
    private static final int MAX_LEDGER_LIMIT = 200;
    private static final int MAX_LEDGER_DAYS = 92;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String secret;
    private final Set<String> allowedHosts;
    private final String vendor;
    private final String templateVersion;
    private final String hospitalCode;
    private final RestClient client;

    public ReceiptPushService(ClinicalRepository repo, ObjectMapper objectMapper,
                              @Value("${medassist.receipt-push.base-url:}") String baseUrl,
                              @Value("${medassist.receipt-push.secret:}") String secret,
                              @Value("${medassist.receipt-push.allowed-hosts:}") String allowedHosts,
                              @Value("${medassist.receipt-push.timeout-ms:3000}") int timeoutMs,
                              @Value("${medassist.receipt-push.vendor:FINANCE}") String vendor,
                              @Value("${medassist.receipt-push.template-version:1.0}")
                              String templateVersion,
                              @Value("${medassist.receipt-push.hospital-code:}")
                              String hospitalCode) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.allowedHosts = parseHosts(allowedHosts);
        this.vendor = (vendor == null || vendor.isBlank()) ? "FINANCE" : vendor.trim();
        this.templateVersion = (templateVersion == null || templateVersion.isBlank())
                ? "1.0" : templateVersion.trim();
        this.hospitalCode = hospitalCode == null ? "" : hospitalCode.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 开具推送（或重试）；已成功则幂等返回。 */
    public ReceiptPushView push(UUID receiptId, String actor) {
        ChargeReceipt receipt = repo.findReceipt(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
        if (!ChargeReceipt.ISSUED.equals(receipt.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有有效票据可以推送电子票据；已作废票据请走红冲");
        }
        String endpoint = requireEndpoint();
        String payload = buildIssuePayload(receipt);
        repo.insertReceiptPushIfAbsent(UUID.randomUUID(), receiptId, PLATFORM, vendor,
                ReceiptPush.ISSUE, templateVersion, null, payload);
        ReceiptPush push = repo.findReceiptPush(receiptId, PLATFORM, ReceiptPush.ISSUE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "票据推送记录创建失败"));
        if ("SENT".equals(push.status())) {
            return toView(push);
        }
        ReceiptPush latest = deliver(push, endpoint, payload);
        audit(actor, "RECEIPT_PUSH", receipt, latest, null);
        return toView(latest);
    }

    /**
     * 红冲：作废票据对原开具报文做反向推送。
     *
     * <p>前置条件：票据已作废 + 原开具报文已成功；红冲报文携带原平台票据号与红冲原因。
     * 已成功的红冲幂等返回，失败可重试。
     */
    public ReceiptPushView redFlush(UUID receiptId, String reason, String actor) {
        ChargeReceipt receipt = repo.findReceipt(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
        if (!ChargeReceipt.VOID.equals(receipt.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已作废票据可以红冲电子票据；有效票据请先作废");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "红冲必须填写原因");
        }
        ReceiptPush original = repo.findReceiptPush(receiptId, PLATFORM, ReceiptPush.ISSUE)
                .filter(p -> "SENT".equals(p.status()) && p.externalNo() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该票据没有成功的原开具报文，无法红冲"));
        String endpoint = requireEndpoint();
        String payload = buildRedFlushPayload(receipt, original, normalizedReason);
        repo.insertReceiptPushIfAbsent(UUID.randomUUID(), receiptId, PLATFORM, vendor,
                ReceiptPush.RED_FLUSH, templateVersion, original.id(), payload);
        ReceiptPush redFlush = repo.findReceiptPush(receiptId, PLATFORM, ReceiptPush.RED_FLUSH)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "红冲记录创建失败"));
        if ("SENT".equals(redFlush.status())) {
            return toView(redFlush);
        }
        ReceiptPush latest = deliver(redFlush, endpoint, payload);
        audit(actor, "RECEIPT_RED_FLUSH", receipt, latest, normalizedReason);
        return toView(latest);
    }

    public List<ReceiptPushView> listPushes(UUID receiptId) {
        return repo.listReceiptPushes(receiptId).stream().map(this::toView).toList();
    }

    /** 对账台账：默认最近 7 天；区间最长 92 天，未来日期拒绝。 */
    public List<ReceiptPushLedger> listLedger(LocalDate from, LocalDate to, String status,
                                              String messageType, String vendorFilter,
                                              Integer limit) {
        LocalDate[] range = resolveRange(from, to);
        String normalizedStatus = normalizeFilter(status, Set.of("PENDING", "SENT", "FAILED"));
        String normalizedType = normalizeFilter(messageType,
                Set.of(ReceiptPush.ISSUE, ReceiptPush.RED_FLUSH));
        String normalizedVendor = (vendorFilter == null || vendorFilter.isBlank())
                ? null : vendorFilter.trim().toUpperCase(Locale.ROOT);
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_LEDGER_LIMIT);
        return repo.listReceiptPushLedger(startOf(range[0]), startOf(range[1].plusDays(1)),
                normalizedStatus, normalizedType, normalizedVendor, size);
    }

    public ReceiptPushSummaryView summary(LocalDate from, LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        ReceiptPushSummary summary = repo.summarizeReceiptPushes(
                startOf(range[0]), startOf(range[1].plusDays(1)));
        return new ReceiptPushSummaryView(summary.total(), summary.sent(), summary.failed(),
                summary.pending(), summary.redFlushCount(), summary.sentAmount());
    }

    // ---------------------------------------------------------------- 内部

    private ReceiptPush deliver(ReceiptPush push, String endpoint, String payload) {
        int attempts = push.attempts() + 1;
        try {
            String signature = sign(payload);
            String response = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", "sha256=" + signature)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            String externalNo = validateResponse(response);
            repo.markReceiptPush(push.id(), "SENT", attempts, externalNo, null);
            log.info("clinical.receipt_push.sent receiptId={} type={} externalNo={}",
                    push.receiptId(), push.messageType(), externalNo);
        } catch (RuntimeException e) {
            repo.markReceiptPush(push.id(), "FAILED", attempts, null, truncate(e.getMessage()));
            log.warn("clinical.receipt_push.failed receiptId={} type={} attempts={} reason={}",
                    push.receiptId(), push.messageType(), attempts, e.getMessage());
        }
        return repo.findReceiptPush(push.receiptId(), push.platform(), push.messageType())
                .orElse(push);
    }

    /** 厂商开具报文模板（包级可见，便于单测直接断言报文要素）。 */
    String buildIssuePayload(ChargeReceipt receipt) {
        Map<String, Object> body = envelope(receipt, ReceiptPush.ISSUE);
        body.put("receipt", receiptNode(receipt));
        return write(body);
    }

    /** 厂商红冲报文模板：必须携带原平台票据号与红冲原因。 */
    String buildRedFlushPayload(ChargeReceipt receipt, ReceiptPush original, String reason) {
        Map<String, Object> body = envelope(receipt, ReceiptPush.RED_FLUSH);
        body.put("originalExternalNo", original.externalNo());
        body.put("originalReceiptNo", receipt.receiptNo());
        body.put("redFlushReason", reason);
        body.put("amount", receipt.amount());
        body.put("paymentMethod", receipt.paymentMethod());
        body.put("receipt", receiptNode(receipt));
        return write(body);
    }

    private Map<String, Object> envelope(ChargeReceipt receipt, String messageType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendor", vendor);
        body.put("templateVersion", templateVersion);
        body.put("messageType", messageType);
        body.put("platform", PLATFORM);
        if (!hospitalCode.isEmpty()) {
            body.put("hospitalCode", hospitalCode);
        }
        body.put("bizNo", receipt.receiptNo());
        body.put("operator", receipt.issuedBy());
        body.put("occurredAt", Instant.now().toString());
        return body;
    }

    private Map<String, Object> receiptNode(ChargeReceipt receipt) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("receiptNo", receipt.receiptNo());
        node.put("chargeId", receipt.chargeId().toString());
        node.put("amount", receipt.amount());
        node.put("paymentMethod", receipt.paymentMethod());
        node.put("issuedAt", receipt.issuedAt() == null ? null : receipt.issuedAt().toString());
        node.put("issuedBy", receipt.issuedBy());
        node.put("receiptStatus", receipt.status());
        return node;
    }

    private String write(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "票据推送内容序列化失败");
        }
    }

    private String sign(String payload) {
        if (secret.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置电子票据推送密钥（medassist.receipt-push.secret），暂不能推送");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("电子票据签名失败：" + e.getMessage());
        }
    }

    /** 平台返回 HTTP 200 也可能是业务失败：解析 code/success 并返回平台票据号。 */
    private String validateResponse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("平台返回空响应");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(response);
        } catch (JacksonException e) {
            throw new IllegalStateException("平台响应不是合法 JSON");
        }
        int code = node.path("code").asInt(0);
        boolean success = node.path("success").asBoolean(true);
        if (code != 0 || !success) {
            String message = node.path("message").asText(node.path("msg").asText("平台拒绝"));
            throw new IllegalStateException("平台返回错误 " + code + "：" + message);
        }
        String externalNo = node.path("externalNo").asText(null);
        if (externalNo == null || externalNo.isBlank()) {
            externalNo = node.path("invoiceNo").asText(null);
        }
        if (externalNo == null || externalNo.isBlank()) {
            externalNo = node.path("data").path("externalNo").asText(null);
        }
        return externalNo == null || externalNo.isBlank() ? null : externalNo;
    }

    private String requireEndpoint() {
        if (baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置电子票据平台地址（medassist.receipt-push.base-url），暂不能推送");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "电子票据平台地址格式不正确");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "电子票据平台地址缺少合法域名");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "电子票据平台域名不在允许列表内：" + host);
        }
        return baseUrl;
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? BusinessTime.today() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (end.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的票据推送");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "起始日期不能晚于截止日期");
        }
        if (start.plusDays(MAX_LEDGER_DAYS).isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "查询区间不得超过 " + MAX_LEDGER_DAYS + " 天");
        }
        return new LocalDate[]{start, end};
    }

    private static java.time.OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
    }

    private static String normalizeFilter(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "筛选值不合法：" + raw);
        }
        return normalized;
    }

    private static Set<String> parseHosts(String raw) {
        Set<String> hosts = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return hosts;
        }
        for (String part : raw.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private ReceiptPushView toView(ReceiptPush p) {
        return new ReceiptPushView(p.id(), p.receiptId(), p.platform(), p.vendor(),
                p.messageType(), p.templateVersion(), p.originalPushId(), p.status(),
                p.attempts(), p.externalNo(), p.lastError(), p.pushedAt());
    }

    private void audit(String actor, String action, ChargeReceipt receipt, ReceiptPush push,
                       String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receiptNo", receipt.receiptNo());
        detail.put("receiptStatus", receipt.status());
        detail.put("messageType", push.messageType());
        detail.put("vendor", push.vendor());
        detail.put("status", push.status());
        detail.put("externalNo", push.externalNo() == null ? "" : push.externalNo());
        if (reason != null) {
            detail.put("reason", reason);
        }
        try {
            repo.logChange(actor, action, "CHARGE_RECEIPT", receipt.id().toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "CHARGE_RECEIPT", receipt.id().toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
