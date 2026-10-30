package com.medassist.auth.web;

import com.medassist.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务自检端点（仅内网，供网关与 K8s 探针使用）。
 */
@RestController
@RequestMapping("/internal")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "platform-auth",
                "status", "UP"
        ));
    }
}
