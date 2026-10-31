package com.medassist.clinical.web;

import com.medassist.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务自检端点（仅内网，经网关时被 {@code /api/v2/*&#47;internal/**} 拒绝规则拦下）。
 */
@RestController
@RequestMapping("/internal")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "platform-clinical",
                "status", "UP"
        ));
    }
}
