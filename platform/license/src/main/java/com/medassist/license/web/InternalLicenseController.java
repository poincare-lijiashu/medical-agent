package com.medassist.license.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.license.service.LicenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 授权摘要内部接口（§284 开通编排专用）。
 *
 * <p>为什么单独开在 {@code /internal/**}：
 * <ul>
 *   <li>网关默认拒绝 {@code /internal/**}（既不下发也不转发），院方与外部无法访问；</li>
 *   <li>控制面（auth）在开通时只读一次"该实例有没有可用授权"，不需要转发授权书；</li>
 *   <li>即使 internal-auth 滤镜在本地被关闭，这里仍显式校验共享内部令牌——fail-closed。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/license")
public class InternalLicenseController {

    private final LicenseService service;
    private final String internalToken;

    public InternalLicenseController(
            LicenseService service,
            @Value("${INTERNAL_API_TOKEN:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @GetMapping("/summary")
    public ApiResponse<LicenseDtos.Summary> summary(
            @RequestParam String instanceCode,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireInternalToken(provided);
        return ApiResponse.ok(service.summaryOf(instanceCode));
    }

    private void requireInternalToken(String provided) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "本部署未配置内部令牌，授权摘要接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部令牌无效");
        }
    }
}
