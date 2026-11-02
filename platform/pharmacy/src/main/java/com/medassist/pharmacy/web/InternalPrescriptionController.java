package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.PrescriptionService;
import com.medassist.pharmacy.web.PharmacyDtos.CancelFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreateFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.OrderPrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionCancelView;
import com.medassist.pharmacy.web.PharmacyDtos.WardReturnReq;
import com.medassist.pharmacy.web.PharmacyDtos.WardReturnView;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 临床医嘱转处方（内网接口，仅临床域直连调用，不经网关）。
 *
 * <p>安全边界与其余内部接口一致：共享令牌 {@code X-Internal-Token}；未配置令牌时接口不可用。
 * 网关对 {@code /internal/**} 直接 404，公网无法触达。
 *
 * <p>幂等：同一 {@code orderId} 只生成一张处方，重复调用返回已有处方（duplicate=true）。
 */
@RestController
@RequestMapping("/internal/prescriptions")
public class InternalPrescriptionController {

    private final PrescriptionService service;
    private final String token;

    public InternalPrescriptionController(PrescriptionService service,
                                          @Value("${pharmacy.internal-api-token:${pharmacy.internal-token:}}")
                                          String token) {
        this.service = service;
        this.token = token == null ? "" : token;
    }

    @PostMapping
    public OrderPrescriptionView createFromOrder(
            @Valid @RequestBody CreateFromOrderReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.createFromOrder(req, "clinical");
    }

    /** 医嘱撤销/停嘱 → 处方作废（幂等；已发药拒绝）。 */
    @PostMapping("/cancel-by-order")
    public PrescriptionCancelView cancelByOrder(
            @Valid @RequestBody CancelFromOrderReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.cancelFromOrder(req, "clinical");
    }

    /**
     * 病区退药 → 药房退药（按批次分摊 + 自动回冲医嘱金额）。
     *
     * <p>幂等键是 {@code returnId}（与病区退药单号相同）：护士站重试不会重复回补库存。
     */
    @PostMapping("/ward-returns")
    public WardReturnView wardReturn(
            @Valid @RequestBody WardReturnReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.returnWardDrug(req, "clinical");
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
