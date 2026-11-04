package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPush;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 电子票据推送适配层（G21）。
 *
 * <p>把院内已开具的有效票据推送到财政/第三方电子票据平台：
 * <ul>
 *   <li>只允许 {@code ISSUED} 票据（作废票不推送）；</li>
 *   <li>同一票据同一平台唯一，已成功再次调用幂等返回；失败可重试；</li>
 *   <li>请求体 HMAC-SHA256 签名（{@code X-Signature: sha256=hex}），密钥由部署配置注入；</li>
 *   <li>平台地址域名必须命中 {@code medassist.receipt-push.allowed-hosts}，避免 SSRF；</li>
 *   <li>平台 HTTP 200 仍解析业务码，非 0 记为失败并保留原因。</li>
 * </ul>
 */
@Service
public class ReceiptPushService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPushService.class);
    private static final String PLATFORM = "FINANCE";

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String secret;
    private final Set<String> allowedHosts;
    private final RestClient client;

    public ReceiptPushService(ClinicalRepository repo, ObjectMapper objectMapper,
                              @Value("${medassist.receipt-push.base-url:}") String baseUrl,
                              @Value("${medassist.receipt-push.secret:}") String secret,
                              @Value("${medassist.receipt-push.allowed-hosts:}") String allowedHosts,
                              @Value("${medassist.receipt-push.timeout-ms:3000}") int timeoutMs) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.allowedHosts = parseHosts(allowedHosts);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 推送（或重试）指定票据；已成功的推送幂等返回。 */
    public ReceiptPushView push(UUID receiptId, String actor) {
        ChargeReceipt receipt = repo.findReceipt(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
        if (!ChargeReceipt.ISSUED.equals(receipt.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有有效票据可以推送电子票据");
        }
        String endpoint = requireEndpoint();
        String payload = buildPayload(receipt);
        repo.insertReceiptPushIfAbsent(UUID.randomUUID(), receiptId, PLATFORM, payload);
        ReceiptPush push = repo.findReceiptPush(receiptId, PLATFORM)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "票据推送记录创建失败"));
        if ("SENT".equals(push.status())) {
            return toView(push);
        }
        ReceiptPush latest = deliver(push, endpoint, payload);
        audit(actor, "RECEIPT_PUSH", receipt.id().toString(), Map.of(
                "receiptNo", receipt.receiptNo(), "status", latest.status(),
                "externalNo", latest.externalNo() == null ? "" : latest.externalNo()));
        return toView(latest);
    }

    public List<ReceiptPushView> listPushes(UUID receiptId) {
        return repo.listReceiptPushes(receiptId).stream().map(this::toView).toList();
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
            log.info("clinical.receipt_push.sent receiptId={} externalNo={}",
                    push.receiptId(), externalNo);
        } catch (RuntimeException e) {
            repo.markReceiptPush(push.id(), "FAILED", attempts, null, truncate(e.getMessage()));
            log.warn("clinical.receipt_push.failed receiptId={} attempts={} reason={}",
                    push.receiptId(), attempts, e.getMessage());
        }
        return repo.findReceiptPush(push.receiptId(), push.platform()).orElse(push);
    }

    private String buildPayload(ChargeReceipt receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", PLATFORM);
        body.put("receiptNo", receipt.receiptNo());
        body.put("chargeId", receipt.chargeId().toString());
        body.put("amount", receipt.amount());
        body.put("paymentMethod", receipt.paymentMethod());
        body.put("issuedAt", receipt.issuedAt() == null ? null : receipt.issuedAt().toString());
        body.put("issuedBy", receipt.issuedBy());
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
        return new ReceiptPushView(p.id(), p.receiptId(), p.platform(), p.status(), p.attempts(),
                p.externalNo(), p.lastError(), p.pushedAt());
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "CHARGE_RECEIPT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "CHARGE_RECEIPT", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
