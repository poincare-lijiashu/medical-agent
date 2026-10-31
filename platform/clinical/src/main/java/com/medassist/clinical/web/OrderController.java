package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.service.OrderService;
import com.medassist.clinical.web.ClinicalDtos.PlaceOrdersReq;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 医嘱 API（按就诊维度组织，符合"一次就诊一个医嘱册"的临床习惯）。
 */
@RestController
@RequestMapping("/clinical/visits/{visitId}/orders")
public class OrderController {

    private static final String ORDER_CREATE = "clinical:order:create";
    private static final String VISIT_READ = "clinical:visit:read";

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<List<VisitOrder>> place(
            @PathVariable UUID visitId,
            @Valid @RequestBody PlaceOrdersReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ORDER_CREATE);
        return ApiResponse.ok(service.place(visitId, req.items(), actor));
    }

    @GetMapping
    public ApiResponse<List<VisitOrder>> list(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.list(visitId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<VisitOrder> cancel(
            @PathVariable UUID visitId,
            @PathVariable UUID orderId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ORDER_CREATE);
        return ApiResponse.ok(service.cancel(visitId, orderId, actor));
    }
}
