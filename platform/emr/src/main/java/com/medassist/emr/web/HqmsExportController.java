package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.HqmsExportBatch;
import com.medassist.emr.service.HqmsExportService;
import com.medassist.emr.web.EmrDtos.CancelHqmsExportReq;
import com.medassist.emr.web.EmrDtos.HqmsExportBatchSummary;
import com.medassist.emr.web.EmrDtos.HqmsReceiptReq;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 病案首页 HQMS 上报批次。
 *
 * <p>权限分层：
 * <ul>
 *   <li>生成批次 / 登记回执 / 撤销 / 列表：{@code emr:hqms:export}（病案质控、管理员）；</li>
 *   <li>报文下载（含整批患者姓名与诊断）：{@code emr:hqms:download}，动作写审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/emr/hqms/export-batches")
public class HqmsExportController {

    private static final String EXPORT = "emr:hqms:export";
    private static final String DOWNLOAD = "emr:hqms:download";

    private final HqmsExportService service;

    public HqmsExportController(HqmsExportService service) {
        this.service = service;
    }

    /** 生成上报批次（期间内尚未上报的已归档首页）。 */
    @PostMapping
    public ApiResponse<HqmsExportBatchSummary> create(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, EXPORT);
        HqmsExportService.CreateResult result = service.create(from, to, actor);
        return ApiResponse.ok(service.summary(result.batch(),
                result.excludedAlreadyExported(), result.remainingPending()));
    }

    /** 登记上级主管部门回执（ACCEPTED / REJECTED，仅未登记过的批次）。 */
    @PostMapping("/{id}/receipt")
    public ApiResponse<HqmsExportBatchSummary> receipt(
            @PathVariable UUID id,
            @Valid @RequestBody HqmsReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, EXPORT);
        return ApiResponse.ok(service.summary(service.receipt(id, req, actor), 0, 0));
    }

    /** 撤销未回执的批次，撤销后首页可在新批次重报。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<HqmsExportBatchSummary> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelHqmsExportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, EXPORT);
        return ApiResponse.ok(service.summary(service.cancel(id, req.reason(), actor), 0, 0));
    }

    @GetMapping
    public ApiResponse<List<HqmsExportBatchSummary>> list(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, EXPORT);
        return ApiResponse.ok(service.list(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<HqmsExportBatchSummary> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, EXPORT);
        return ApiResponse.ok(service.summary(id));
    }

    /** 下载上报 XML；整批含患者姓名与诊断，独立权限 + 审计。 */
    @GetMapping(value = "/{id}/payload", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> payload(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, DOWNLOAD);
        HqmsExportBatch batch = service.payload(id, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/xml; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + batch.batchNo() + ".xml\"")
                .body(batch.payload());
    }
}
