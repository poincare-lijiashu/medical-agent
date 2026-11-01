package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.service.ApplicationService;
import com.medassist.tech.web.TechDtos.ApplicationCancelView;
import com.medassist.tech.web.TechDtos.CancelFromOrderReq;
import com.medassist.tech.web.TechDtos.CreateFromOrderReq;
import com.medassist.tech.web.TechDtos.OrderApplicationView;
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
 * 临床医嘱转检查/检验申请（内网接口，仅临床域直连调用，不经网关）。
 *
 * <p>安全边界与其它内部接口一致：共享令牌 {@code X-Internal-Token}；未配置令牌时接口不可用；
 * 网关对 {@code /internal/**} 直接 404。
 *
 * <p>幂等：同一 {@code orderId} 只生成一张申请，重复调用返回已有申请（duplicate=true）。
 */
@RestController
@RequestMapping("/internal/applications")
public class InternalApplicationController {

    private final ApplicationService service;
    private final String token;

    public InternalApplicationController(ApplicationService service,
                                         @Value("${tech.internal-api-token:${tech.internal-token:}}")
                                         String token) {
        this.service = service;
        this.token = token == null ? "" : token;
    }

    @PostMapping
    public OrderApplicationView createFromOrder(
            @Valid @RequestBody CreateFromOrderReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.createFromOrder(req, "clinical");
    }

    /** 医嘱撤销/停嘱 → 申请作废（幂等；已采集/执行中拒绝）。 */
    @PostMapping("/cancel-by-order")
    public ApplicationCancelView cancelByOrder(
            @Valid @RequestBody CancelFromOrderReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        return service.cancelFromOrder(req, "clinical");
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
