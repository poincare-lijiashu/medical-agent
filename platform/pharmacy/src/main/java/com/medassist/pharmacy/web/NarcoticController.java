package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.service.NarcoticService;
import com.medassist.pharmacy.web.NarcoticDtos.AmpouleReturnReq;
import com.medassist.pharmacy.web.NarcoticDtos.GrantPrescriberReq;
import com.medassist.pharmacy.web.NarcoticDtos.RevokePrescriberReq;
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
 * 麻醉药品管理 API（处方权 / 专册 / 空安瓿回收）。
 *
 * <p>权限：查询用 {@code pharmacy:read}，授予/撤销/回收用 {@code pharmacy:narcotic:manage}。
 * 医师端只有开方权限，处方权本身由医务科在此名单中授予——两个权限面刻意分开。
 */
@RestController
@RequestMapping("/pharmacy/narcotic")
public class NarcoticController {

    private static final String READ = "pharmacy:read";
    private static final String MANAGE = "pharmacy:narcotic:manage";

    private final NarcoticService service;

    public NarcoticController(NarcoticService service) {
        this.service = service;
    }

    @GetMapping("/prescribers")
    public ApiResponse<List<NarcoticPrescriber>> prescribers(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.prescribers(enabled, limit));
    }

    @PostMapping("/prescribers")
    public ApiResponse<NarcoticPrescriber> grant(
            @Valid @RequestBody GrantPrescriberReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.grant(req, actor));
    }

    @PostMapping("/prescribers/{prescriber}/revoke")
    public ApiResponse<NarcoticPrescriber> revoke(
            @PathVariable String prescriber, @Valid @RequestBody RevokePrescriberReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.revoke(prescriber, req, actor));
    }

    /** 专册查询（默认最近 7 天；status=PENDING_RETURN 可只看待回收）。 */
    @GetMapping("/register")
    public ApiResponse<List<NarcoticRegister>> register(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.registers(from, to, patientId, status, limit));
    }

    @PostMapping("/register/{registerId}/ampoule-return")
    public ApiResponse<NarcoticRegister> returnAmpoule(
            @PathVariable UUID registerId, @Valid @RequestBody AmpouleReturnReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.returnAmpoule(registerId, req, actor));
    }
}
