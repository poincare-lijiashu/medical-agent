package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
                                 UUID deptId, String prescriber, LocalDate supplyDate,
                                 UUID campusId, List<Line> items) {
        return createFromOrderWithBilling(orderId, patientId, visitId, visitNo, deptId,
                prescriber, supplyDate, "ORDER", campusId, items);
    }

    @Override
    public RxRef createFromOrderWithBilling(UUID orderId, UUID patientId, UUID visitId,
                                            String visitNo, UUID deptId, String prescriber,
                                            LocalDate supplyDate, String billingMode,
                                            UUID campusId, List<Line> items) {
        RxResponse response;
        try {
            // 用 Spring 托管的 ObjectMapper 显式序列化：RestClient 默认转换器不保证注册
            // JavaTimeModule，LocalDate 会被写成数组，药房侧直接 400（真实踩坑）。
            String payload = objectMapper.writeValueAsString(new CreateFromOrderRequest(
                    orderId, patientId, visitId, visitNo, deptId, prescriber, supplyDate,
                    billingMode, campusId, items));
            response = client.post()
                    .uri("/internal/prescriptions")
                    .header("X-Internal-Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(RxResponse.class);
        } catch (JacksonException e) {
            log.warn("clinical.pharmacy_rx_serialize_failed orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "处方报文序列化失败，请稍后重试");
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

    @Override
    public CancelRef cancelByOrder(UUID orderId, String reason, String actor) {
        CancelResponse response;
        try {
            response = client.post()
                    .uri("/internal/prescriptions/cancel-by-order")
                    .header("X-Internal-Token", token)
                    .body(new CancelFromOrderRequest(orderId, reason, actor))
                    .retrieve()
                    .body(CancelResponse.class);
        } catch (HttpClientErrorException e) {
            String message = extractMessage(e);
            log.warn("clinical.pharmacy_rx_cancel_rejected orderId={} status={} reason={}",
                    orderId, e.getStatusCode().value(), message);
            // 409（如已发药）要保持"冲突"语义，医护端提示走退药流程
            ErrorCode code = e.getStatusCode().value() == 409
                    ? ErrorCode.CONFLICT : ErrorCode.VALIDATION_FAILED;
            throw new BusinessException(code, message);
        } catch (RestClientException e) {
            log.warn("clinical.pharmacy_unavailable_cancel orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "药房服务暂不可用，处方未作废（医嘱撤销已中止）");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "药房返回为空，处方未作废");
        }
        return new CancelRef(response.prescriptionId(), response.status(), response.cancelled());
    }

    @Override
    public PrescriptionStatus prescriptionStatus(UUID prescriptionId) {
        StatusResponse response;
        try {
            response = client.get()
                    .uri("/internal/prescriptions/{id}", prescriptionId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(StatusResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "药房未找到该处方");
        } catch (RestClientException e) {
            log.warn("clinical.pharmacy_status_unavailable prescriptionId={} err={}",
                    prescriptionId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "药房服务暂不可用，无法同步发药状态");
        }
        if (response == null || response.prescriptionId() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "药房返回为空");
        }
        return new PrescriptionStatus(response.prescriptionId(), response.prescriptionNo(),
                response.status(), response.billingMode(),
                response.totalAmount() == null ? BigDecimal.ZERO : response.totalAmount());
    }

    @Override
    public WardReturnRef returnWardDrug(UUID orderId, LocalDate supplyDate, String drugCode,
                                        int quantity, String reason, UUID returnId, String actor) {
        WardReturnResponse response;
        try {
            String payload = objectMapper.writeValueAsString(new WardReturnRequest(orderId,
                    supplyDate, drugCode, quantity, reason, returnId, actor));
            response = client.post()
                    .uri("/internal/prescriptions/ward-returns")
                    .header("X-Internal-Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(WardReturnResponse.class);
        } catch (JacksonException e) {
            log.warn("clinical.pharmacy_ward_return_serialize_failed orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "退药报文序列化失败，请稍后重试");
        } catch (HttpClientErrorException e) {
            String message = extractMessage(e);
            log.warn("clinical.pharmacy_ward_return_rejected orderId={} status={} reason={}",
                    orderId, e.getStatusCode().value(), message);
            // 409 = 并发/状态冲突，其余 4xx = 业务校验（可退数量不足、处方不存在等）
            throw new BusinessException(e.getStatusCode().value() == 409
                    ? ErrorCode.CONFLICT : ErrorCode.VALIDATION_FAILED, message);
        } catch (RestClientException e) {
            log.warn("clinical.pharmacy_unavailable_ward_return orderId={} err={}", orderId,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "药房服务暂不可用，退药未完成（库存与费用均未回冲，可重试）");
        }
        if (response == null || response.dispenseId() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "药房返回为空，退药未完成");
        }
        return new WardReturnRef(response.dispenseId(), response.returnedQuantity(),
                response.remainingReturnable());
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
                                  UUID deptId, String prescriber, LocalDate supplyDate,
                                  String billingMode, UUID campusId, List<Line> items) {
    }

    record StatusResponse(UUID prescriptionId, String prescriptionNo, String status,
                          String billingMode, BigDecimal totalAmount) {
    }

    record RxResponse(UUID prescriptionId, String prescriptionNo, UUID orderId, boolean duplicate,
                      BigDecimal totalAmount, String status) {
    }

    record CancelFromOrderRequest(UUID orderId, String reason, String actor) {
    }

    record CancelResponse(UUID prescriptionId, String prescriptionNo, String status,
                          boolean cancelled) {
    }

    record WardReturnRequest(UUID orderId, LocalDate supplyDate, String drugCode, int quantity,
                             String reason, UUID returnId, String actor) {
    }

    record WardReturnResponse(UUID dispenseId, String drugCode, int returnedQuantity,
                              int remainingReturnable) {
    }
}
