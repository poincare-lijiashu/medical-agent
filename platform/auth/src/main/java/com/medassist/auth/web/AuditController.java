package com.medassist.auth.web;

import com.medassist.auth.service.AuditService;
import com.medassist.auth.web.dto.AuditActionOption;
import com.medassist.auth.web.dto.AuditEventResp;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 审计查询 API（跨域汇聚）。
 *
 * <p>经网关访问为 {@code GET /api/v2/audit/events}（网关 StripPrefix=3 → {@code /events}）。
 * 需要 {@code audit:log:read} 权限点（ADMIN / QC 默认持有）。
 */
@RestController
@RequestMapping("/events")
public class AuditController {

    private static final String READ = "audit:log:read";

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AuditEventResp>> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.search(actor, action, targetType, targetId, from, to,
                page, size));
    }

    /**
     * 动作字典（第 243 批）：筛选下拉用，避免运维在页面上猜动作名。
     *
     * <p>与 `/events` 同一时间窗口径；只回原始 action 与次数（中文名由前端翻译）。
     */
    @GetMapping("/actions")
    public ApiResponse<List<AuditActionOption>> actions(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.actionCatalog(from, to, limit));
    }
}
