package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.service.TemplateService;
import com.medassist.emr.web.EmrDtos.CreateTemplateReq;
import com.medassist.emr.web.EmrDtos.TemplateView;
import com.medassist.emr.web.EmrDtos.ToggleTemplateReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 病历模板 API。
 *
 * <p>权限：{@code emr:template:manage}（病案室/管理员）；查询需 {@code emr:record:read}。
 */
@RestController
@RequestMapping("/emr/templates")
public class TemplateController {

    private static final String MANAGE = "emr:template:manage";
    private static final String READ = "emr:record:read";

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<TemplateView> create(
            @Valid @RequestBody CreateTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<Template>> list(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) Boolean enabled,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(docType, enabled));
    }

    @GetMapping("/{templateCode}")
    public ApiResponse<TemplateView> detail(
            @PathVariable String templateCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(templateCode));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<TemplateView> toggle(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.toggle(id, req.enabled(), req.version(), actor));
    }
}
