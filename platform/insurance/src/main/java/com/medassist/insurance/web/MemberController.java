package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.service.InsuranceService;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
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

/** 参保登记（本地快照；按 patientId UPSERT）。 */
@RestController
@RequestMapping("/insurance/members")
public class MemberController {

    private static final String MANAGE = "insurance:member:manage";
    private static final String READ = "insurance:settlement:read";

    private final InsuranceService service;

    public MemberController(InsuranceService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Member> register(
            @Valid @RequestBody MemberReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.registerMember(req, actor));
    }

    @GetMapping("/{patientId}")
    public ApiResponse<Member> detail(
            @PathVariable UUID patientId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.member(patientId));
    }

    /** 参保变更历史（追加式，最新在前）：待遇参数与启停状态逐条可追溯。 */
    @GetMapping("/{patientId}/history")
    public ApiResponse<List<MemberHistory>> history(
            @PathVariable UUID patientId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.memberHistory(patientId, limit));
    }
}
