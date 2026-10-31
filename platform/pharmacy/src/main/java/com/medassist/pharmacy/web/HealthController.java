package com.medassist.pharmacy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部健康检查（仅供集群探针/网关使用，不承载业务）。
 *
 * <p>网关对 {@code /api/v2/*&#47;internal/**} 一律 404，因此该端点不会经网关外泄。
 */
@RestController
@RequestMapping("/internal")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "platform-pharmacy");
    }
}
