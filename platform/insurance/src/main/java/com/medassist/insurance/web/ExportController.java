package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.service.SettlementExportService;
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
 * <p>权限：生成批次与登记回执属"对外报盘"，需要 {@code insurance:settlement:confirm}
 * （医保办/管理员）；查询与下载报文需要 {@code insurance:settlement:read}。
 */
@RestController
@RequestMapping("/insurance/export-batches")
public class ExportController {

    private static final String CONFIRM = "insurance:settlement:confirm";
    private static final String READ = "insurance:settlement:read";

    private final SettlementExportService service;

    public ExportController(SettlementExportService service) {
        this.service = service;
    }

    /** 生成报盘批次（期间内已确认清单）。 */
    @PostMapping
    public ApiResponse<ExportBatchSummary> create(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CONFIRM);
        ExportBatch batch = service.create(from, to, actor);
        return ApiResponse.ok(new ExportBatchSummary(batch.id(), batch.batchNo(),
                batch.periodFrom(), batch.periodTo(), batch.billCount(), batch.totalAmount(),
                batch.insuranceAmount(), batch.selfPayAmount(), batch.payloadHash(),
                batch.status(), batch.receiptNo(), batch.receiptAt(), batch.receiptMessage(),
                batch.createdBy(), batch.version(), batch.createdAt()));
    }

    /** 登记省局回执（ACCEPTED / REJECTED，仅未登记过的批次）。 */
    @PostMapping("/{id}/receipt")
    public ApiResponse<ExportBatchSummary> receipt(
            @PathVariable UUID id,
            @Valid @RequestBody ExportReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CONFIRM);
        ExportBatch batch = service.receipt(id, req, actor);
        return ApiResponse.ok(new ExportBatchSummary(batch.id(), batch.batchNo(),
                batch.periodFrom(), batch.periodTo(), batch.billCount(), batch.totalAmount(),
                batch.insuranceAmount(), batch.selfPayAmount(), batch.payloadHash(),
                batch.status(), batch.receiptNo(), batch.receiptAt(), batch.receiptMessage(),
                batch.createdBy(), batch.version(), batch.createdAt()));
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

    /** 下载报盘 XML（原样返回，服务端另存为 .xml 归档）。 */
    @GetMapping(value = "/{id}/payload", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> payload(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        ExportBatch batch = service.payload(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/xml; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + batch.batchNo() + ".xml\"")
                .body(batch.payload());
    }
}
