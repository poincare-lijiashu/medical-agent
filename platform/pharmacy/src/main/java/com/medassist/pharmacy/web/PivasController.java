package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.PivasOrder;
import com.medassist.pharmacy.service.PivasService;
import com.medassist.pharmacy.web.PivasDtos.CheckPivasReq;
import com.medassist.pharmacy.web.PivasDtos.CreatePivasOrderReq;
import com.medassist.pharmacy.web.PivasDtos.PivasSummary;
import com.medassist.pharmacy.web.PivasDtos.PivasView;
import com.medassist.pharmacy.web.PivasDtos.RejectPivasReq;
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
 * 静配中心 API（G34）。
 *
 * <p>权限三分离：查看 {@code pharmacy:pivas:read}、
 * 配置 {@code pharmacy:pivas:prepare}、复核 {@code pharmacy:pivas:check}。
 * 复核服务端强制"复核人 ≠ 配置人"。
 */
@RestController
@RequestMapping("/pharmacy/pivas")
public class PivasController {

    private static final String READ = "pharmacy:pivas:read";
    private static final String PREPARE = "pharmacy:pivas:prepare";
    private static final String CHECK = "pharmacy:pivas:check";

    private final PivasService service;

    public PivasController(PivasService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public ApiResponse<List<PivasOrder>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, limit));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<PivasView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 成品输液追溯：按标签号反查配置/复核人与批次耗用。 */
    @GetMapping("/trace")
    public ApiResponse<PivasView> trace(
            @RequestParam String bagNo,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.trace(bagNo));
    }

    @GetMapping("/summary")
    public ApiResponse<PivasSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    @PostMapping("/orders")
    public ApiResponse<PivasView> create(
            @Valid @RequestBody CreatePivasOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PREPARE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PostMapping("/orders/{id}/prepare")
    public ApiResponse<PivasView> prepare(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PREPARE);
        return ApiResponse.ok(service.prepare(id, actor));
    }

    /** 复核：approve=true 生成成品标签；false 驳回并回补批次库存（必须写原因）。 */
    @PostMapping("/orders/{id}/check")
    public ApiResponse<PivasView> check(
            @PathVariable UUID id,
            @Valid @RequestBody CheckPivasReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CHECK);
        return ApiResponse.ok(service.check(id, req, actor));
    }

    @PostMapping("/orders/{id}/reject")
    public ApiResponse<PivasView> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectPivasReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CHECK);
        return ApiResponse.ok(service.reject(id, req, actor));
    }
}
