package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 药房医嘱转处方客户端（内网直连，不经网关）。
 *
 * <p>与 EMPI 客户端同一套边界：共享令牌 + 调用方签名（开关打开时自动加签）。
 * 失败分两类：4xx 是"这张处方本身有问题"（如审方 BLOCK、药品停用），
 * 原样把药房给的中文原因带回去；5xx/网络异常是"对方暂时不可用"，统一 503 文案，
 * 由上层把医嘱转送状态置 FAILED 供重试。
 */
@Component
public class HttpPharmacyOrderClient implements PharmacyOrderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPharmacyOrderClient.class);

    private final RestClient client;
    private final String token;
    private final ObjectMapper objectMapper;

    public HttpPharmacyOrderClient(ClinicalProperties props, InternalCallerSigner signer,
                                   ObjectMapper objectMapper) {
        ClinicalProperties.Pharmacy pharmacy = props.pharmacy();
        String configuredToken = pharmacy == null || pharmacy.internalToken() == null
                ? "" : pharmacy.internalToken();
        this.token = configuredToken;
        if (this.token.isBlank()) {
            throw new IllegalStateException(
                    "clinical.pharmacy.internal-token 未配置：请设置 CLINICAL_INTERNAL_TOKEN"
                            + "（须与药房的 INTERNAL_API_TOKEN 一致）");
        }
        String baseUrl = pharmacy.baseUrl() == null || pharmacy.baseUrl().isBlank()
                ? "http://127.0.0.1:8084" : pharmacy.baseUrl();
        Duration timeout = pharmacy.timeout() == null ? Duration.ofSeconds(3) : pharmacy.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public RxRef createFromOrder(UUID orderId, UUID patientId, UUID visitId, String visitNo,
                                 UUID deptId, String prescriber, List<Line> items) {
        RxResponse response;
        try {
            response = client.post()
                    .uri("/internal/prescriptions")
                    .header("X-Internal-Token", token)
                    .body(new CreateFromOrderRequest(orderId, patientId, visitId, visitNo, deptId,
                            prescriber, items))
                    .retrieve()
                    .body(RxResponse.class);
        } catch (HttpClientErrorException e) {
            String reason = extractMessage(e);
            log.warn("clinical.pharmacy_rx_rejected orderId={} status={} reason={}",
                    orderId, e.getStatusCode().value(), reason);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        } catch (RestClientException e) {
            log.warn("clinical.pharmacy_unavailable orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "药房服务暂不可用，处方未生成（医嘱转送已标记失败，可重试）");
        }
        if (response == null || response.prescriptionId() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "药房返回为空，处方未生成");
        }
        return new RxRef(response.prescriptionId(), response.prescriptionNo(), response.duplicate(),
                response.totalAmount() == null ? BigDecimal.ZERO : response.totalAmount());
    }

    /**
     * 提取平台标准错误体里的 message（{@code {"code":42200,"message":"..."}}）。
     *
     * <p>解析失败时退回 HTTP 状态描述——绝不要把整段 HTML/堆栈透给医护人员。
     */
    private String extractMessage(HttpClientErrorException e) {
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                String message = objectMapper.readTree(body).path("message").asText("");
                if (!message.isBlank()) {
                    return message;
                }
            }
        } catch (RuntimeException ignored) {
            // 落到下面的兜底文案
        }
        return "药房拒绝了该处方（HTTP " + e.getStatusCode().value() + "），请核对药品与用法";
    }

    // ---------------------------------------------------------------- 报文

    record CreateFromOrderRequest(UUID orderId, UUID patientId, UUID visitId, String visitNo,
                                  UUID deptId, String prescriber, List<Line> items) {
    }

    record RxResponse(UUID prescriptionId, String prescriptionNo, UUID orderId, boolean duplicate,
                      BigDecimal totalAmount, String status) {
    }
}
