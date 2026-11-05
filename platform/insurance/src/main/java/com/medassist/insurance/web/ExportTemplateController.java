package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.service.ExportTemplateService;
import com.medassist.insurance.web.InsuranceDtos.CreateExportTemplateReq;
import com.medassist.insurance.web.InsuranceDtos.ExportTemplateView;
import com.medassist.insurance.web.InsuranceDtos.UpdateExportTemplateReq;
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
 * 省局报盘报文模板（G85）。
 *
 * <p>权限分层：
 * <ul>
 *   <li>查看模板（字段映射/版本/生效状态）：{@code insurance:export:template:read}；</li>
 *   <li>新建/修改/发布/停用：{@code insurance:export:template:manage}（医保办、管理员）。</li>
 * </ul>
 * 模板决定整批报文口径，误改影响所有批次，因此写操作与查看分离。
 */
@RestController
@RequestMapping("/insurance/export/templates")
public class ExportTemplateController {

    private static final String MANAGE = "insurance:export:template:manage";
    private static final String READ = "insurance:export:template:read";

    private final ExportTemplateService service;
    private final InsuranceMetrics metrics;

    public ExportTemplateController(ExportTemplateService service, InsuranceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping
    public ApiResponse<List<ExportTemplateView>> list(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExportTemplateView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<ExportTemplateView> create(
            @Valid @RequestBody CreateExportTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        try {
            ExportTemplateView view = service.create(req, actor);
            metrics.exportAction("template_create", true);
            return ApiResponse.ok(view);
        } catch (BusinessException e) {
            metrics.exportAction("template_create", false);
            throw e;
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<ExportTemplateView> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateExportTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        try {
            ExportTemplateView view = service.update(id, req, actor);
            metrics.exportAction("template_update", true);
            return ApiResponse.ok(view);
        } catch (BusinessException e) {
            metrics.exportAction("template_update", false);
            throw e;
        }
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<ExportTemplateView> publish(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        try {
            ExportTemplateView view = service.publish(id, actor);
            metrics.exportAction("template_publish", true);
            return ApiResponse.ok(view);
        } catch (BusinessException e) {
            metrics.exportAction("template_publish", false);
            throw e;
        }
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<ExportTemplateView> disable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        try {
            ExportTemplateView view = service.disable(id, actor);
            metrics.exportAction("template_disable", true);
            return ApiResponse.ok(view);
        } catch (BusinessException e) {
            metrics.exportAction("template_disable", false);
            throw e;
        }
    }
}
