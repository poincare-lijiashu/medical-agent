package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.BloodProduct;
import com.medassist.clinical.service.TransfusionService;
import com.medassist.clinical.web.TransfusionDtos.BoardView;
import com.medassist.clinical.web.TransfusionDtos.CancelReq;
import com.medassist.clinical.web.TransfusionDtos.CompleteTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.CreateRequestReq;
import com.medassist.clinical.web.TransfusionDtos.CrossmatchReq;
import com.medassist.clinical.web.TransfusionDtos.IssueReq;
import com.medassist.clinical.web.TransfusionDtos.ObservationReq;
import com.medassist.clinical.web.TransfusionDtos.PrecheckReq;
import com.medassist.clinical.web.TransfusionDtos.ReactionReq;
import com.medassist.clinical.web.TransfusionDtos.RequestSummaryView;
import com.medassist.clinical.web.TransfusionDtos.RequestView;
import com.medassist.clinical.web.TransfusionDtos.StartTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.StatsView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 输血闭环 API（G40）。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code transfusion:request:read/write}——申请查看、开立与取消；</li>
 *   <li>{@code transfusion:crossmatch:manage}——配血；</li>
 *   <li>{@code transfusion:issue:manage}——发血；</li>
 *   <li>{@code transfusion:execute}——双人核对开始/完成、不良反应上报。</li>
 * </ul>
 * 申请与列表中的患者号/姓名按 {@link DataScope} 决定明文或掩码。
 */
@RestController
@RequestMapping("/clinical/transfusion")
public class TransfusionController {

    private static final String READ = "transfusion:request:read";
    private static final String WRITE = "transfusion:request:write";
    private static final String CROSSMATCH = "transfusion:crossmatch:manage";
    private static final String ISSUE = "transfusion:issue:manage";
    private static final String EXECUTE = "transfusion:execute";

    private final TransfusionService service;

    public TransfusionController(TransfusionService service) {
        this.service = service;
    }

    @GetMapping("/products")
    public ApiResponse<List<BloodProduct>> products(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listProducts());
    }

    @GetMapping("/requests")
    public ApiResponse<List<RequestSummaryView>> requests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listRequests(status, from, to, limit, scope.campusFilter())
                .stream()
                .map(s -> PhiMask.transfusionSummary(s, scope))
                .toList());
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<RequestView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.detail(id), scope));
    }

    @PostMapping("/requests")
    public ApiResponse<RequestView> create(
            @Valid @RequestBody CreateRequestReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.createRequest(req, actor), scope));
    }

    @PostMapping("/requests/{id}/crossmatch")
    public ApiResponse<RequestView> crossmatch(
            @PathVariable UUID id,
            @Valid @RequestBody CrossmatchReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, CROSSMATCH);
        return ApiResponse.ok(PhiMask.transfusionRequest(
                service.crossmatch(id, req, actor), scope));
    }

    /** 输血前评估与知情同意（发血前必须具备）。 */
    @PostMapping("/requests/{id}/precheck")
    public ApiResponse<RequestView> precheck(
            @PathVariable UUID id,
            @Valid @RequestBody PrecheckReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.transfusionRequest(
                service.savePrecheck(id, req, actor), scope));
    }

    /** 发血核对（发血/核对双人 + 取血人 + 外观检查）。 */
    @PostMapping("/requests/{id}/issue")
    public ApiResponse<RequestView> issue(
            @PathVariable UUID id,
            @Valid @RequestBody IssueReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ISSUE);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.issue(id, req, actor), scope));
    }

    @PostMapping("/requests/{id}/start")
    public ApiResponse<RequestView> start(
            @PathVariable UUID id,
            @Valid @RequestBody StartTransfusionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.start(id, req, actor), scope));
    }

    @PostMapping("/requests/{id}/cancel")
    public ApiResponse<RequestView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.transfusionRequest(
                service.cancel(id, req.reason(), actor), scope));
    }

    @PostMapping("/records/{id}/complete")
    public ApiResponse<RequestView> complete(
            @PathVariable UUID id,
            @Valid @RequestBody CompleteTransfusionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.complete(id, req, actor), scope));
    }

    @PostMapping("/records/{id}/reaction")
    public ApiResponse<RequestView> reaction(
            @PathVariable UUID id,
            @Valid @RequestBody ReactionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(PhiMask.transfusionRequest(
                service.reportReaction(id, req, actor), scope));
    }

    /** 输注观察（15 分钟观察）：只增不改。 */
    @PostMapping("/records/{id}/observations")
    public ApiResponse<RequestView> observe(
            @PathVariable UUID id,
            @Valid @RequestBody ObservationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(PhiMask.transfusionRequest(service.observe(id, req, actor), scope));
    }

    /** 不良反应闭环：REPORTED → CLOSED。 */
    @PostMapping("/reactions/{id}/close")
    public ApiResponse<RequestView> closeReaction(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(PhiMask.transfusionRequest(
                service.closeReaction(id, actor), scope));
    }

    /** 输血报表：期间质量指标、成分用量、不良反应分布。 */
    @GetMapping("/stats")
    public ApiResponse<StatsView> stats(
            @RequestParam(defaultValue = "30") int days,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.stats(days, actor));
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.board());
    }
}
