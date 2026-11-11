package com.medassist.clinical.web;

import com.medassist.clinical.service.IntegrationMessageService;
import com.medassist.clinical.web.IntegrationDtos.IntegrationEnqueueReq;
import com.medassist.clinical.web.IntegrationDtos.IntegrationMessageView;
import com.medassist.clinical.web.IntegrationDtos.IntegrationSummary;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 集成消息台账 API（第 257 批）。
 *
 * <p>为什么需要这个页面/接口：接口失败如果只写日志，业务方永远不知道"昨天那批
 * 检验结果到底传没传过去"。台账让失败可见、可重投、可按业务号对账。
 *
 * <p>权限：查看 {@code integration:message:read}；重投 {@code integration:message:retry}。
 * 这两个权限默认只授予 ADMIN 与集成运维角色。
 */
@RestController
@RequestMapping("/clinical/integration")
public class IntegrationController {

    private static final String READ = "integration:message:read";
    private static final String RETRY = "integration:message:retry";

    private final IntegrationMessageService service;

    public IntegrationController(IntegrationMessageService service) {
        this.service = service;
    }

    /** 台账列表（按创建时间倒序）。 */
    @GetMapping("/messages")
    public ApiResponse<List<IntegrationMessageView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String system,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, system, limit));
    }

    /** 状态汇总：待发/失败/终止/已确认，以及按对端系统分组的待处理量。 */
    @GetMapping("/messages/summary")
    public ApiResponse<IntegrationSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    /**
     * 登记一条出站消息。
     *
     * <p>业务侧可以在本地事务里调用（落库即成功），不需要等待对端响应；
     * 真正的发送由 worker 按退避策略执行。
     */
    @PostMapping("/messages")
    public ApiResponse<IntegrationMessageView> enqueue(
            @Valid @RequestBody IntegrationEnqueueReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, RETRY);
        return ApiResponse.ok(service.enqueue(req, actor));
    }

    /** 人工重投（仅 FAILED/DEAD；ACKED 禁止重发）。 */
    @PostMapping("/messages/{id}/retry")
    public ApiResponse<IntegrationMessageView> retry(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, RETRY);
        return ApiResponse.ok(service.retry(id, actor));
    }
}
