package com.medassist.ops.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.ops.domain.GoLiveModel.Report;
import com.medassist.ops.service.GoLiveCockpitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 上线指挥台 API（§302）。
 *
 * <p>只读聚合，不写业务数据；权限 {@code ops:golive:read}。
 * 报告可直接作为上线评审证据包导出（前端生成 JSON 文件）。
 */
@RestController
@RequestMapping("/ops/golive")
public class GoLiveCockpitController {

    private static final String READ = "ops:golive:read";

    private final GoLiveCockpitService service;

    public GoLiveCockpitController(GoLiveCockpitService service) {
        this.service = service;
    }

    @GetMapping("/cockpit")
    public ApiResponse<Report> cockpit(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String componentCode,
            @RequestParam(defaultValue = "PROD") String environment,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.build(projectId, componentCode, environment));
    }

    /** 证据包与驾驶舱同源：保证页面看到的与导出的一致。 */
    @GetMapping("/evidence")
    public ApiResponse<Report> evidence(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String componentCode,
            @RequestParam(defaultValue = "PROD") String environment,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.build(projectId, componentCode, environment));
    }
}
