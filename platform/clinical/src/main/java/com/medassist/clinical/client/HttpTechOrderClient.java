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

import java.time.Duration;
import java.util.UUID;

/**
 * 医技医嘱转申请客户端（内网直连，不经网关）。
 *
 * <p>与药房客户端同一套边界：4xx 原样带回医技给的中文原因；5xx/网络异常统一 503，
 * 由上层把医嘱转送状态置 FAILED 供重试。
 */
@Component
public class HttpTechOrderClient implements TechOrderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTechOrderClient.class);

    private final RestClient client;
    private final String token;
    private final ObjectMapper objectMapper;

    public HttpTechOrderClient(ClinicalProperties props, InternalCallerSigner signer,
                               ObjectMapper objectMapper) {
        ClinicalProperties.Tech tech = props.tech();
        String configuredToken = tech == null || tech.internalToken() == null
                ? "" : tech.internalToken();
        this.token = configuredToken;
        if (this.token.isBlank()) {
            throw new IllegalStateException(
                    "clinical.tech.internal-token 未配置：请设置 CLINICAL_INTERNAL_TOKEN"
                            + "（须与医技的 INTERNAL_API_TOKEN 一致）");
        }
        String baseUrl = tech.baseUrl() == null || tech.baseUrl().isBlank()
                ? "http://127.0.0.1:8085" : tech.baseUrl();
        Duration timeout = tech.timeout() == null ? Duration.ofSeconds(3) : tech.timeout();
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
    public AppRef createFromOrder(UUID orderId, UUID patientId, UUID visitId, String visitNo,
                                  UUID deptId, String appliedBy, String itemCode, String bodyPart,
                                  String priority, String clinicalDiag, UUID campusId) {
        AppResponse response;
        try {
            response = client.post()
                    .uri("/internal/applications")
                    .header("X-Internal-Token", token)
                    .body(new CreateFromOrderRequest(orderId, patientId, visitId, visitNo, deptId,
                            appliedBy, itemCode, bodyPart, priority, clinicalDiag, campusId))
                    .retrieve()
                    .body(AppResponse.class);
        } catch (HttpClientErrorException e) {
            String reason = extractMessage(e);
            log.warn("clinical.tech_app_rejected orderId={} status={} reason={}",
                    orderId, e.getStatusCode().value(), reason);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        } catch (RestClientException e) {
            log.warn("clinical.tech_unavailable orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "医技服务暂不可用，申请未生成（医嘱转送已标记失败，可重试）");
        }
        if (response == null || response.applicationId() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "医技返回为空，申请未生成");
        }
        return new AppRef(response.applicationId(), response.applicationNo(), response.duplicate(),
                response.category(), response.itemName());
    }

    @Override
    public CancelRef cancelByOrder(UUID orderId, String reason, String actor) {
        CancelResponse response;
        try {
            response = client.post()
                    .uri("/internal/applications/cancel-by-order")
                    .header("X-Internal-Token", token)
                    .body(new CancelFromOrderRequest(orderId, reason, actor))
                    .retrieve()
                    .body(CancelResponse.class);
        } catch (HttpClientErrorException e) {
            String message = extractMessage(e);
            log.warn("clinical.tech_app_cancel_rejected orderId={} status={} reason={}",
                    orderId, e.getStatusCode().value(), message);
            ErrorCode code = e.getStatusCode().value() == 409
                    ? ErrorCode.CONFLICT : ErrorCode.VALIDATION_FAILED;
            throw new BusinessException(code, message);
        } catch (RestClientException e) {
            log.warn("clinical.tech_unavailable_cancel orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "医技服务暂不可用，申请未作废（医嘱撤销已中止）");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "医技返回为空，申请未作废");
        }
        return new CancelRef(response.applicationId(), response.status(), response.cancelled());
    }

    /** 提取平台标准错误体里的 message；解析失败时退回状态描述。 */
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
        return "医技拒绝了该申请（HTTP " + e.getStatusCode().value() + "），请核对项目与患者";
    }

    // ---------------------------------------------------------------- 报文

    record CreateFromOrderRequest(UUID orderId, UUID patientId, UUID visitId, String visitNo,
                                  UUID deptId, String appliedBy, String itemCode, String bodyPart,
                                  String priority, String clinicalDiag, UUID campusId) {
    }

    record AppResponse(UUID applicationId, String applicationNo, UUID orderId, boolean duplicate,
                       String category, String itemName, String status) {
    }

    record CancelFromOrderRequest(UUID orderId, String reason, String actor) {
    }

    record CancelResponse(UUID applicationId, String applicationNo, String status,
                          boolean cancelled) {
    }
}
