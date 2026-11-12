package com.medassist.license.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.license.domain.LicenseModel.ChangeLog;
import com.medassist.license.domain.LicenseModel.Edition;
import com.medassist.license.domain.LicenseModel.License;
import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Usage;
import com.medassist.license.service.LicenseService;
import com.medassist.license.web.LicenseDtos.AuditList;
import com.medassist.license.web.LicenseDtos.EditionList;
import com.medassist.license.web.LicenseDtos.EntitlementList;
import com.medassist.license.web.LicenseDtos.ImportReq;
import com.medassist.license.web.LicenseDtos.IssueReq;
import com.medassist.license.web.LicenseDtos.Issued;
import com.medassist.license.web.LicenseDtos.LicenseList;
import com.medassist.license.web.LicenseDtos.RevokeReq;
import com.medassist.license.web.LicenseDtos.StatusView;
import com.medassist.license.web.LicenseDtos.UsageReq;
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
 * 授权与许可 API。
 *
 * <p>权限切分（auth V93）：
 * <ul>
 *   <li>{@code license:read}：授权状态、版本矩阵、模块权益、历史授权与变更审计；</li>
 *   <li>{@code license:manage}：导入授权书、吊销授权、上报用量、签发（厂商构建）。</li>
 * </ul>
 *
 * <p>安全边界：签发接口只在 {@code LICENSE_ISSUANCE_ENABLED=true} 的厂商构建中可用；
 * 院内生产构建必须关闭，否则等于把"给自己发证"的能力交给院方。
 */
@RestController
@RequestMapping("/license")
public class LicenseController {

    private static final String READ = "license:read";
    private static final String MANAGE = "license:manage";

    private final LicenseService service;

    public LicenseController(LicenseService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ApiResponse<StatusView> status(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.status());
    }

    @GetMapping("/entitlements")
    public ApiResponse<EntitlementList> entitlements(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<ModuleEntitlement> items = service.entitlements();
        boolean usable = items.stream().anyMatch(ModuleEntitlement::entitled);
        return ApiResponse.ok(new EntitlementList(usable, items));
    }

    @GetMapping("/editions")
    public ApiResponse<EditionList> editions(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<Edition> items = service.editions();
        return ApiResponse.ok(new EditionList(items));
    }

    @GetMapping("/licenses")
    public ApiResponse<LicenseList> licenses(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<License> items = service.licenses(limit);
        return ApiResponse.ok(new LicenseList(items, items.size()));
    }

    @GetMapping("/audit")
    public ApiResponse<AuditList> audit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<ChangeLog> items = service.audit(limit);
        return ApiResponse.ok(new AuditList(items, items.size()));
    }

    @PostMapping("/import")
    public ApiResponse<License> importLicense(
            @Valid @RequestBody ImportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.importLicense(req.document(), actor, traceId));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<License> revoke(
            @PathVariable UUID id,
            @Valid @RequestBody RevokeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.revoke(id, req.reason(), actor, traceId));
    }

    @PostMapping("/issue")
    public ApiResponse<Issued> issue(
            @Valid @RequestBody IssueReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.issue(req, actor, traceId));
    }

    @PostMapping("/usage")
    public ApiResponse<Usage> captureUsage(
            @Valid @RequestBody UsageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.captureUsage(req, actor, traceId));
    }
}
