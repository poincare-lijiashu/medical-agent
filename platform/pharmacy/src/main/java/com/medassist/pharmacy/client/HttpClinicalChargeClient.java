package com.medassist.pharmacy.client;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;
import com.medassist.pharmacy.config.PharmacyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 临床收费域内网客户端（发药计费 / 退药冲减）。
 *
 * <p>与 EMPI 客户端同一套边界：内网直连 + 共享令牌 + 调用方签名（开关打开时自动加签）。
 * 失败一律抛 {@link ErrorCode#UPSTREAM_UNAVAILABLE}，由上层把发药单置为可重试状态，
 * 绝不"静默不记账"。
 */
@Component
public class HttpClinicalChargeClient implements ClinicalChargeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClinicalChargeClient.class);

    private final RestClient client;
    private final String token;

    public HttpClinicalChargeClient(PharmacyProperties props, InternalCallerSigner signer) {
        PharmacyProperties.Clinical clinical = props.clinical();
        this.token = clinical == null || clinical.internalToken() == null ? "" : clinical.internalToken();
        if (this.token.isBlank()) {
            throw new IllegalStateException(
                    "pharmacy.clinical.internal-token 未配置：请设置 PHARMACY_INTERNAL_TOKEN（须与 clinical 的 INTERNAL_API_TOKEN 一致）");
        }
        String baseUrl = clinical.baseUrl() == null || clinical.baseUrl().isBlank()
                ? "http://127.0.0.1:8083" : clinical.baseUrl();
        Duration timeout = clinical.timeout() == null ? Duration.ofSeconds(3) : clinical.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer))
                .build();
    }

    @Override
    public Charged charge(UUID visitId, UUID dispenseId, List<Line> lines, String actor) {
        ChargeResponse response;
        try {
            response = client.post()
                    .uri("/internal/pharmacy-charges")
                    .header("X-Internal-Token", token)
                    .body(new ChargeRequest(visitId, dispenseId, actor, lines))
                    .retrieve()
                    .body(ChargeResponse.class);
        } catch (RestClientException e) {
            log.warn("pharmacy.clinical_charge_failed visitId={} dispenseId={} err={}",
                    visitId, dispenseId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "收费服务暂不可用，发药费用未记账（已标记待重试）");
        }
        if (response == null || response.chargeNo() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "收费服务返回为空，发药费用未记账");
        }
        return new Charged(response.chargeNo(), response.chargeTotal());
    }

    @Override
    public Refunded refund(UUID dispenseItemId, BigDecimal cumulativeRefundAmount,
                           String reason, String actor) {
        RefundResponse response;
        try {
            response = client.post()
                    .uri("/internal/pharmacy-refunds")
                    .header("X-Internal-Token", token)
                    .body(new RefundRequest(dispenseItemId, cumulativeRefundAmount, reason, actor))
                    .retrieve()
                    .body(RefundResponse.class);
        } catch (RestClientException e) {
            log.warn("pharmacy.clinical_refund_failed dispenseItemId={} amount={} err={}",
                    dispenseItemId, cumulativeRefundAmount, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "收费服务暂不可用，退药冲减未同步（已标记待重试）");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "收费服务返回为空，退药冲减未同步");
        }
        return new Refunded(response.pendingAmount() == null ? BigDecimal.ZERO : response.pendingAmount());
    }

    @Override
    public OrderRefunded refundOrder(UUID orderId, UUID dispenseItemId, BigDecimal itemAmount,
                                     BigDecimal cumulativeRefundAmount, String reason, String actor) {
        OrderRefundResponse response;
        try {
            response = client.post()
                    .uri("/internal/pharmacy-order-refunds")
                    .header("X-Internal-Token", token)
                    .body(new OrderRefundRequest(orderId, dispenseItemId, itemAmount,
                            cumulativeRefundAmount, reason, actor))
                    .retrieve()
                    .body(OrderRefundResponse.class);
        } catch (RestClientException e) {
            log.warn("pharmacy.clinical_order_refund_failed orderId={} dispenseItemId={} amount={} err={}",
                    orderId, dispenseItemId, cumulativeRefundAmount, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "收费服务暂不可用，医嘱退药冲减未同步（已标记待重试）");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "收费服务返回为空，医嘱退药冲减未同步");
        }
        return new OrderRefunded(
                response.appliedAmount() == null ? BigDecimal.ZERO : response.appliedAmount(),
                response.pendingAmount() == null ? BigDecimal.ZERO : response.pendingAmount(),
                response.doneAmount() == null ? BigDecimal.ZERO : response.doneAmount(),
                Boolean.TRUE.equals(response.orderCancelled()));
    }

    // ---------------------------------------------------------------- 报文

    record ChargeRequest(UUID visitId, UUID dispenseId, String actor, List<Line> items) {
    }

    record ChargeResponse(UUID chargeId, String chargeNo, Integer createdItems,
                          BigDecimal addedAmount, BigDecimal chargeTotal) {
    }

    record RefundRequest(UUID dispenseItemId, BigDecimal cumulativeRefundAmount, String reason, String actor) {
    }

    record RefundResponse(UUID dispenseItemId, BigDecimal refundedAmount, BigDecimal pendingAmount,
                          BigDecimal doneAmount) {
    }

    record OrderRefundRequest(UUID orderId, UUID dispenseItemId, BigDecimal itemAmount,
                              BigDecimal cumulativeRefundAmount, String reason, String actor) {
    }

    record OrderRefundResponse(UUID dispenseItemId, UUID orderId, UUID visitId, BigDecimal amount,
                               BigDecimal appliedAmount, BigDecimal pendingAmount,
                               BigDecimal doneAmount, Boolean billSettled, Boolean orderCancelled) {
    }
}
