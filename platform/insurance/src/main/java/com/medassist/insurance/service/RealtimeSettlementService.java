package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.RealtimeSettlementView;
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
 * 医保实时结算接口骨架（G22）。
 *
 * <p>在已确认结算清单之上建立对外实时交易适配层：
 * <ul>
 *   <li>只允许 {@code CONFIRMED} 清单提交（草稿/作废单不出账）；</li>
 *   <li>同一清单最多一笔在途/成功的 {@code SETTLE}：换请求号也不能重复出账，
 *       失败只能复用原交易重试（{@code ux_realtime_settle_once} + 服务层前置校验）；</li>
 *   <li>投递先抢占（{@code PENDING/FAILED → DELIVERING}）再外呼省局，并发 submit/retry
 *       只有一个线程真正出账；结果回写失败显式报冲突，不静默丢弃；</li>
 *   <li>请求体 HMAC-SHA256 签名（{@code X-Signature: sha256=hex}），密钥由部署配置注入；</li>
 *   <li>平台地址域名必须命中 {@code insurance.realtime.allowed-hosts}，避免 SSRF；</li>
 *   <li>平台 HTTP 200 仍解析业务码，非 0 记 FAILED 并保留原因，可手动重试；</li>
 *   <li>失败重试有上限，超过后必须人工介入，避免无休止打爆省局接口。</li>
 * </ul>
 */
@Service
public class RealtimeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSettlementService.class);

    private static final String PLATFORM = "PROVINCE";

    /** 单笔交易最多尝试次数：超过后必须人工核对，避免无限重试。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 留档响应报文上限，避免把省局长报文整段塞进库。 */
    private static final int MAX_RESPONSE_BYTES = 8000;

    private final InsuranceRepository repo;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String secret;
    private final Set<String> allowedHosts;
    private final RestClient client;

    public RealtimeSettlementService(InsuranceRepository repo, ObjectMapper objectMapper,
                                     @Value("${insurance.realtime.base-url:}") String baseUrl,
                                     @Value("${insurance.realtime.secret:}") String secret,
                                     @Value("${insurance.realtime.allowed-hosts:}") String allowedHosts,
                                     @Value("${insurance.realtime.timeout-ms:3000}") int timeoutMs) {
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

    /** 提交（或幂等重提）实时结算；同一清单只允许一笔 SETTLE，换请求号也不能重复出账。 */
    public RealtimeSettlementView submit(UUID billId, String requestId, String bizType, String actor) {
        String normalizedRequestId = requestId == null ? "" : requestId.trim();
        if (normalizedRequestId.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求号不能为空");
        }
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.CONFIRMED.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有已确认的结算清单可以提交实时结算，当前：" + bill.status());
        }
        String normalizedBiz = normalizeBizType(bizType);
        RealtimeSettlement settlement = RealtimeSettlement.SETTLE.equals(normalizedBiz)
                ? resolveSettleTransaction(bill, normalizedRequestId, actor)
                : resolveTransaction(bill, normalizedRequestId, normalizedBiz, actor);
        if (RealtimeSettlement.SETTLED.equals(settlement.status())) {
            return toView(settlement, false);
        }
        String endpoint = requireEndpoint();
        requireSecret();
        return toView(deliver(settlement, endpoint, actor), true);
    }

    /** 失败交易手动重试；已成功交易幂等返回，不重复出账。 */
    public RealtimeSettlementView retry(UUID id, String actor) {
        RealtimeSettlement settlement = repo.findRealtimeById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_REALTIME_NOT_FOUND));
        if (RealtimeSettlement.SETTLED.equals(settlement.status())) {
            return toView(settlement, true);
        }
        SettlementBill bill = repo.findBillById(settlement.billId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.CONFIRMED.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "清单已不是已确认状态，不能重试实时结算，当前：" + bill.status());
        }
        String endpoint = requireEndpoint();
        requireSecret();
        return toView(deliver(settlement, endpoint, actor), false);
    }

    public RealtimeSettlementView detail(UUID id) {
        return repo.findRealtimeById(id)
                .map(r -> toView(r, true))
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_REALTIME_NOT_FOUND));
    }

    public List<RealtimeSettlementView> list(UUID billId, String status, Integer limit) {
        return list(billId, status, limit, java.util.Set.of());
    }

    /** 交易列表（G48 按清单院区收敛；空集合表示不限院区）。 */
    public List<RealtimeSettlementView> list(UUID billId, String status, Integer limit,
                                             java.util.Set<UUID> campusIds) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        String normalized = (status == null || status.isBlank())
                ? null : status.trim().toUpperCase(Locale.ROOT);
        return repo.listRealtime(billId, normalized, size, InsuranceService.campusCsv(campusIds)).stream()
                .map(r -> toView(r, false)).toList();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析（或创建）SETTLE 交易：同一清单最多一笔，换请求号也不新建。
     *
     * <p>已成功 → 幂等返回该笔；在途/失败且请求号不同 → 409，要求对原交易重试
     * （否则换号即可绕过 {@link #MAX_ATTEMPTS} 并二次出账）。
     */
    private RealtimeSettlement resolveSettleTransaction(SettlementBill bill, String requestId,
                                                        String actor) {
        var existing = repo.findSettleByBill(bill.id());
        if (existing.isPresent()) {
            RealtimeSettlement current = existing.get();
            if (current.requestId().equals(requestId)) {
                return current;
            }
            if (RealtimeSettlement.SETTLED.equals(current.status())) {
                return current;
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该清单已有实时结算交易（请求号 " + current.requestId() + "，状态 "
                            + current.status() + "），同一清单只能有一笔 SETTLE，请对该交易执行重试");
        }
        return createTransaction(bill, requestId, RealtimeSettlement.SETTLE, actor);
    }

    /** 预结算等非 SETTLE 交易：同一清单 + 同一请求号幂等。 */
    private RealtimeSettlement resolveTransaction(SettlementBill bill, String requestId,
                                                  String bizType, String actor) {
        return repo.findRealtimeByBillAndRequest(bill.id(), requestId)
                .orElseGet(() -> createTransaction(bill, requestId, bizType, actor));
    }

    private RealtimeSettlement createTransaction(SettlementBill bill, String requestId,
                                                 String bizType, String actor) {
        String endpoint = requireEndpoint();
        requireSecret();
        RealtimeSettlement candidate = new RealtimeSettlement(UUID.randomUUID(), bill.id(),
                bill.visitId(), requestId, bizType, PLATFORM, RealtimeSettlement.PENDING, 0,
                null, null, buildPayload(bill, requestId, bizType), null, actor,
                java.time.Instant.now(), null, 0L);
        if (repo.insertRealtime(candidate)) {
            return repo.findRealtimeById(candidate.id()).orElse(candidate);
        }
        // 并发：同清单同请求号，或同清单已有一笔在途/成功的 SETTLE
        RealtimeSettlement current = RealtimeSettlement.SETTLE.equals(bizType)
                ? repo.findSettleByBill(bill.id()).orElse(null)
                : repo.findRealtimeByBillAndRequest(bill.id(), requestId).orElse(null);
        if (current == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "实时结算交易创建失败");
        }
        if (!current.requestId().equals(requestId)
                && !RealtimeSettlement.SETTLED.equals(current.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该清单已有实时结算交易（请求号 " + current.requestId() + "，状态 "
                            + current.status() + "），同一清单只能有一笔 SETTLE，请对该交易执行重试");
        }
        return current;
    }

    /**
     * 投递一次并回写结果。
     *
     * <p>先抢占（PENDING/FAILED → DELIVERING）再外呼：并发 submit/retry 只有一个线程
     * 真正打省局接口，其余按库内最新状态返回；回写带"仍在投递态 + 版本一致"双重条件，
     * 写回失败必须显式暴露为冲突，不能把"省局已出账、本地失败"静默吞掉。
     *
     * <p>平台调用失败不抛业务异常：交易落 FAILED 并保留原因，由前端提示人工重试；
     * 只有配置错误（未配地址/域名越权/签名密钥缺失）才直接拒绝提交。
     */
    private RealtimeSettlement deliver(RealtimeSettlement settlement, String endpoint, String actor) {
        RealtimeSettlement current = repo.findRealtimeById(settlement.id()).orElse(settlement);
        if (RealtimeSettlement.SETTLED.equals(current.status())) {
            return current;
        }
        if (current.attempts() >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "实时结算已尝试 " + current.attempts() + " 次，超过上限，请人工核对后再处理");
        }
        if (!repo.claimRealtime(current.id(), current.version())) {
            // 另一线程正在投递，或已写入结果：不重复外呼，按库内最新状态返回
            log.info("insurance.realtime.claim_skipped billId={} requestId={} status={}",
                    current.billId(), current.requestId(), current.status());
            return repo.findRealtimeById(current.id()).orElse(current);
        }
        // 抢占成功：attempts 已在库内 +1，本次回写用抢占后的版本号
        int attempts = current.attempts() + 1;
        long claimedVersion = current.version() + 1;
        String status;
        String externalNo = null;
        String error = null;
        String responsePayload = null;
        try {
            String signature = sign(current.requestPayload());
            String response = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", "sha256=" + signature)
                    .body(current.requestPayload())
                    .retrieve()
                    .body(String.class);
            responsePayload = truncate(response, MAX_RESPONSE_BYTES);
            externalNo = validateResponse(response);
            status = RealtimeSettlement.SETTLED;
            log.info("insurance.realtime.settled billId={} requestId={} externalNo={}",
                    current.billId(), current.requestId(), externalNo);
        } catch (RuntimeException e) {
            status = RealtimeSettlement.FAILED;
            error = truncate(e.getMessage(), 500);
            log.warn("insurance.realtime.failed billId={} requestId={} attempts={} reason={}",
                    current.billId(), current.requestId(), attempts, e.getMessage());
        }
        boolean written = repo.markRealtime(current.id(), status, attempts, externalNo, error,
                responsePayload, claimedVersion);
        audit(current, actor, status, externalNo, attempts);
        RealtimeSettlement latest = repo.findRealtimeById(current.id()).orElse(current);
        if (!written && !RealtimeSettlement.SETTLED.equals(latest.status())) {
            // 抢占被超时接管、结果已被他人覆盖：省局可能已出账，必须让人工看到冲突
            throw new BusinessException(ErrorCode.CONFLICT,
                    "实时结算结果回写冲突，请刷新后按库内最新状态处理，切勿直接重复提交");
        }
        return latest;
    }

    private String buildPayload(SettlementBill bill, String requestId, String bizType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", PLATFORM);
        body.put("bizType", bizType);
        body.put("requestId", requestId);
        body.put("billNo", bill.billNo());
        body.put("visitId", bill.visitId().toString());
        body.put("patientNo", bill.patientNo());
        body.put("serviceDate", bill.serviceDate() == null ? null : bill.serviceDate().toString());
        body.put("totalAmount", bill.totalAmount());
        body.put("insuranceAmount", bill.insuranceAmount());
        body.put("selfPayAmount", bill.selfPayAmount());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "实时结算报文序列化失败");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("实时结算签名失败：" + e.getMessage());
        }
    }

    /** 平台 HTTP 200 也可能是业务失败：解析 code/success 并返回省局交易号。 */
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
            externalNo = node.path("settlementNo").asText(null);
        }
        if (externalNo == null || externalNo.isBlank()) {
            externalNo = node.path("data").path("externalNo").asText(null);
        }
        return externalNo == null || externalNo.isBlank() ? null : externalNo;
    }

    private String requireEndpoint() {
        if (baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置医保实时结算平台地址（insurance.realtime.base-url），暂不能提交");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "医保实时结算平台地址格式不正确");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "医保实时结算平台地址缺少合法域名");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "医保实时结算平台域名不在允许列表内：" + host);
        }
        return baseUrl;
    }

    private void requireSecret() {
        if (secret.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置医保实时结算密钥（insurance.realtime.secret），暂不能提交");
        }
    }

    private static String normalizeBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return RealtimeSettlement.SETTLE;
        }
        String normalized = bizType.trim().toUpperCase(Locale.ROOT);
        if (!RealtimeSettlement.PRE_SETTLE.equals(normalized)
                && !RealtimeSettlement.SETTLE.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "业务类型只支持 PRE_SETTLE 或 SETTLE");
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

    private static String truncate(String message, int max) {
        if (message == null) {
            return null;
        }
        return message.length() <= max ? message : message.substring(0, max);
    }

    /** @param withResponse 是否返回报文原文（列表/幂等场景不返回，避免大字段刷屏） */
    private RealtimeSettlementView toView(RealtimeSettlement r, boolean withResponse) {
        return new RealtimeSettlementView(r.id(), r.billId(), r.visitId(), r.requestId(),
                r.bizType(), r.platform(), r.status(), r.attempts(), r.externalNo(), r.lastError(),
                withResponse ? r.responsePayload() : null, r.createdBy(), r.createdAt(),
                r.settledAt(), r.version());
    }

    private void audit(RealtimeSettlement settlement, String actor, String status,
                       String externalNo, int attempts) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("billId", settlement.billId().toString());
        detail.put("requestId", settlement.requestId());
        detail.put("bizType", settlement.bizType());
        detail.put("status", status);
        detail.put("attempts", attempts);
        detail.put("externalNo", externalNo == null ? "" : externalNo);
        try {
            repo.logChange(actor, "REALTIME_" + settlement.bizType(),
                    "REALTIME_SETTLEMENT", settlement.id().toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, "REALTIME_" + settlement.bizType(),
                    "REALTIME_SETTLEMENT", settlement.id().toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
