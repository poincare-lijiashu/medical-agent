package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.OrderRefund;
import com.medassist.clinical.service.OrderRefundService;
import com.medassist.clinical.service.PharmacyChargeService;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeReq;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeResult;
import com.medassist.clinical.web.PharmacyChargeDtos.CompleteRefundReq;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundReq;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundView;
import com.medassist.clinical.web.PharmacyChargeDtos.RefundReq;
import com.medassist.clinical.web.PharmacyChargeDtos.RefundView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * 药房发药费用联动（内网接口，仅药房域直连调用，不经网关）。
 *
 * <p>安全边界与其余内部接口一致：共享令牌 {@code X-Internal-Token}；未配置令牌时接口不可用。
 * 上线 {@code internal-paths-signature-required=true} 后还会叠加调用方签名（pharmacy）。
 */
@RestController
@RequestMapping("/internal")
public class InternalPharmacyChargeController {

    private final PharmacyChargeService service;
    private final OrderRefundService orderRefundService;
    private final String token;

    public InternalPharmacyChargeController(PharmacyChargeService service,
                                            OrderRefundService orderRefundService,
                                            @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                            String token) {
        this.service = service;
        this.orderRefundService = orderRefundService;
        this.token = token == null ? "" : token;
    }

    /** 发药计费（幂等：同一 dispenseItemId 只记一次）。 */
    @PostMapping("/pharmacy-charges")
    public ChargeResult charge(@RequestBody ChargeReq req,
                               @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.charge(req.visitId(), req.dispenseId(), req.items(), req.actor());
    }

    /** 退药冲减（幂等：传累计已退金额）。 */
    @PostMapping("/pharmacy-refunds")
    public RefundView refund(@RequestBody RefundReq req,
                             @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.refund(req.dispenseItemId(), req.cumulativeRefundAmount(), req.reason(), req.actor());
    }

    /** 待财务退费列表。 */
    @GetMapping("/pharmacy-refunds/pending")
    public List<RefundView> pending(@RequestParam(required = false) Integer limit,
                                    @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.pendingRefunds(limit == null ? 50 : limit);
    }

    /** 财务确认退费（待退费 → 已退费）。 */
    @PostMapping("/pharmacy-refunds/{dispenseItemId}/complete")
    public RefundView complete(@PathVariable UUID dispenseItemId,
                               @RequestBody CompleteRefundReq req,
                               @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.completeRefund(dispenseItemId, req.amount(), req.actor());
    }

    // ---------------------------------------------------------------- 医嘱计费模式退药冲减（B3）

    /** 医嘱退药冲减（幂等）：未计费记 applied、未结算写负向明细、已结算登记待退费。 */
    @PostMapping("/pharmacy-order-refunds")
    public OrderRefundView refundOrder(@RequestBody OrderRefundReq req,
                                       @RequestHeader(value = "X-Internal-Token", required = false)
                                       String provided) {
        requireToken(provided);
        return orderRefundService.refund(req);
    }

    /** 待财务退费的医嘱退药列表。 */
    @GetMapping("/pharmacy-order-refunds/pending")
    public List<OrderRefund> pendingOrderRefunds(@RequestParam(required = false) Integer limit,
                                                 @RequestHeader(value = "X-Internal-Token", required = false)
                                                 String provided) {
        requireToken(provided);
        return orderRefundService.pendingRefunds(limit == null ? 50 : limit);
    }

    /** 财务确认医嘱退药退费。 */
    @PostMapping("/pharmacy-order-refunds/{dispenseItemId}/complete")
    public OrderRefundView completeOrderRefund(@PathVariable UUID dispenseItemId,
                                               @RequestBody CompleteRefundReq req,
                                               @RequestHeader(value = "X-Internal-Token", required = false)
                                               String provided) {
        requireToken(provided);
        return orderRefundService.completeRefund(dispenseItemId, req.amount(), req.actor());
    }

    private void requireToken(String provided) {
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
    }
}
