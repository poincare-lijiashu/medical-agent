package com.medassist.clinical.web;

import com.medassist.clinical.service.ReportSubscriptionService;
import com.medassist.clinical.web.ReportSubscriptionDtos.DeliveryListView;
import com.medassist.clinical.web.ReportSubscriptionDtos.DeliveryView;
import com.medassist.clinical.web.ReportSubscriptionDtos.RecipientGroupView;
import com.medassist.clinical.web.ReportSubscriptionDtos.SaveGroupMembersReq;
import com.medassist.clinical.web.ReportSubscriptionDtos.SaveRecipientGroupReq;
import com.medassist.clinical.web.ReportSubscriptionDtos.SaveSubscriptionReq;
import com.medassist.clinical.web.ReportSubscriptionDtos.SubscriptionView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 报表订阅与投递 API（G19）。
 *
 * <p>读：管理/质控；写（订阅管理、手动补发）：仅管理员。
 */
@RestController
@RequestMapping("/clinical/reports")
public class ReportSubscriptionController {

    private static final String READ = "clinical:report:subscription:read";
    private static final String MANAGE = "clinical:report:subscription:manage";

    private final ReportSubscriptionService service;

    public ReportSubscriptionController(ReportSubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/subscriptions")
    public ApiResponse<List<SubscriptionView>> listSubscriptions(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listSubscriptions());
    }

    @PostMapping("/subscriptions")
    public ApiResponse<SubscriptionView> create(
            @Valid @RequestBody SaveSubscriptionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/subscriptions/{id}")
    public ApiResponse<SubscriptionView> update(
            @PathVariable UUID id,
            @Valid @RequestBody SaveSubscriptionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.update(id, req, actor));
    }

    /** 手动投递/补发指定日期（缺省昨天）；已成功的投递幂等返回。 */
    @PostMapping("/subscriptions/{id}/send")
    public ApiResponse<DeliveryView> send(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.send(id, date, actor));
    }

    @GetMapping("/deliveries")
    public ApiResponse<DeliveryListView> listDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(new DeliveryListView(service.listDeliveries(status, limit)));
    }

    // ---------------------------------------------------------------- 收件人分组（G25）

    @GetMapping("/recipient-groups")
    public ApiResponse<List<RecipientGroupView>> listRecipientGroups(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listRecipientGroups());
    }

    @PostMapping("/recipient-groups")
    public ApiResponse<RecipientGroupView> createRecipientGroup(
            @Valid @RequestBody SaveRecipientGroupReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createRecipientGroup(req, actor));
    }

    @PutMapping("/recipient-groups/{id}")
    public ApiResponse<RecipientGroupView> updateRecipientGroup(
            @PathVariable UUID id,
            @Valid @RequestBody SaveRecipientGroupReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updateRecipientGroup(id, req, actor));
    }

    /** 整组替换成员（事务内删旧插新）。 */
    @PutMapping("/recipient-groups/{id}/members")
    public ApiResponse<RecipientGroupView> replaceRecipientGroupMembers(
            @PathVariable UUID id,
            @Valid @RequestBody SaveGroupMembersReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.replaceRecipientGroupMembers(id, req, actor));
    }
}
