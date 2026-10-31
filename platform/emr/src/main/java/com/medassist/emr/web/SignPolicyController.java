package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.SignPolicy;
import com.medassist.emr.service.SignPolicyService;
import com.medassist.emr.web.EmrDtos.SignPolicyReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 归档签名策略 API（N-04）。
 *
 * <p>权限：配置需 {@code emr:policy:manage}（病案质控/管理员，见 auth V19）；
 * 查询需 {@code emr:record:read}（医师需知道归档为何被拦截）。
 */
@RestController
@RequestMapping("/emr/sign-policies")
public class SignPolicyController {

    private static final String MANAGE = "emr:policy:manage";
    private static final String READ = "emr:record:read";

    private final SignPolicyService service;

    public SignPolicyController(SignPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SignPolicy>> list(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list());
    }

    /** 新增或修改某文书类型的归档签名级别（已存在时必须带 version，CAS）。 */
    @PutMapping("/{docType}")
    public ApiResponse<SignPolicy> upsert(
            @PathVariable String docType,
            @Valid @RequestBody SignPolicyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsert(docType, req.requiredLevel(), req.version(), actor));
    }
}
