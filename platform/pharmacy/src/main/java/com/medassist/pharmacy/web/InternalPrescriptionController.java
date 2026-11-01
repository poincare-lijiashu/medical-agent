package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.PrescriptionService;
import com.medassist.pharmacy.web.PharmacyDtos.CreateFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.OrderPrescriptionView;
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
