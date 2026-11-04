package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.service.ReportTemplateService;
import com.medassist.tech.web.DeviceDtos.CreateReportTemplateReq;
import com.medassist.tech.web.DeviceDtos.TemplateView;
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
 * 结构化报告模板 API（G35）。
 *
 * <p>查看 {@code tech:report-template:read}；创建/发布/停用 {@code tech:report-template:manage}。
 */
@RestController
@RequestMapping("/tech/report-templates")
public class ReportTemplateController {

    private static final String READ = "tech:report-template:read";
    private static final String MANAGE = "tech:report-template:manage";

    private final ReportTemplateService service;

    public ReportTemplateController(ReportTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ReportTemplate>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, modality, limit));
    }

    /** 报告编辑器只允许选择已发布模板。 */
    @GetMapping("/published")
    public ApiResponse<List<ReportTemplate>> published(
            @RequestParam(required = false) String modality,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.published(modality));
    }

    @GetMapping("/{id}")
    public ApiResponse<TemplateView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<TemplateView> create(
            @Valid @RequestBody CreateReportTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<TemplateView> publish(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.publish(id, actor));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<TemplateView> disable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.disable(id, actor));
    }
}
