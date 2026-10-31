package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.service.SettlementExportService;
import com.medassist.insurance.web.InsuranceDtos.CancelExportReq;
import com.medassist.insurance.web.InsuranceDtos.ExportBatchSummary;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
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
 * 医保结算清单导出批次与省局回执（N5 第二批 B）。
 *
 * <p>权限分层：
 * <ul>
 *   <li>生成批次 / 登记回执 / 撤销批次：{@code insurance:settlement:confirm}（医保办、管理员）；</li>
 *   <li>批次列表与摘要（不含报文）：{@code insurance:settlement:read}；</li>
 *   <li>报文下载（含批量患者号与项目明细）：{@code insurance:export:download}（仅医保办、管理员），
 *       且动作写审计（第四轮 MF-02）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/insurance/export-batches")
public class ExportController {

    private static final String CONFIRM = "insurance:settlement:confirm";
    private static final String READ = "insurance:settlement:read";
    private static final String DOWNLOAD = "insurance:export:download";

    private final SettlementExportService service;
    private final InsuranceMetrics metrics;

    public ExportController(SettlementExportService service, InsuranceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** 生成报盘批次（期间内**尚未报盘**的已确认清单）。 */
    @PostMapping
    public ApiResponse<ExportBatchSummary> create(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, CONFIRM);
        try {
            SettlementExportService.ExportCreateResult result = service.create(from, to, actor);
            metrics.exportAction("create", true);
            return ApiResponse.ok(service.summary(result.batch(),
                    result.excludedAlreadyExported(), result.remainingPending()));
        } catch (BusinessException e) {
            metrics.exportAction("create", false);
            throw e;
        }
    }

    /** 登记省局回执（ACCEPTED / REJECTED，仅未登记过的批次）。 */
    @PostMapping("/{id}/receipt")
    public ApiResponse<ExportBatchSummary> receipt(
            @PathVariable UUID id,
            @Valid @RequestBody ExportReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, CONFIRM);
        try {
            ExportBatchSummary summary = service.summary(service.receipt(id, req, actor), 0, 0);
            metrics.exportAction("receipt", true);
            return ApiResponse.ok(summary);
        } catch (BusinessException e) {
            metrics.exportAction("receipt", false);
            throw e;
        }
    }

    /** 撤销未回执的批次（GENERATED → CANCELLED），撤销后清单可在新批次重报。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<ExportBatchSummary> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelExportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, CONFIRM);
        try {
            ExportBatchSummary summary = service.summary(service.cancel(id, req.reason(), actor), 0, 0);
            metrics.exportAction("cancel", true);
            return ApiResponse.ok(summary);
        } catch (BusinessException e) {
            metrics.exportAction("cancel", false);
            throw e;
        }
    }

    @GetMapping
    public ApiResponse<List<ExportBatchSummary>> list(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExportBatchSummary> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary(id));
    }

    /** 下载报盘 XML（原样返回，服务端另存为 .xml 归档）；含批量 PHI，独立权限 + 审计。 */
    @GetMapping(value = "/{id}/payload", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> payload(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, DOWNLOAD);
        try {
            ExportBatch batch = service.payload(id, actor);
            metrics.exportAction("download", true);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/xml; charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + batch.batchNo() + ".xml\"")
                    .body(batch.payload());
        } catch (BusinessException e) {
            metrics.exportAction("download", false);
            throw e;
        }
    }
}
