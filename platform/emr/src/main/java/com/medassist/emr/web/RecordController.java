package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.ChangeRequest;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordHit;
import com.medassist.emr.service.RecordService;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.ChangeRequestReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.ReviewReq;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SignReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 病历书写 API。
 *
 * <p>状态机：DRAFT →（提交+质控）→ SUBMITTED →（签名）→ SIGNED →（归档）→ ARCHIVED；
 * SIGNED 状态可修订，修订后回到 SUBMITTED 并清空签名。
 */
@RestController
@RequestMapping("/emr/records")
public class RecordController {

    private static final String CREATE = "emr:record:create";
    private static final String READ = "emr:record:read";
    private static final String SEARCH = "emr:record:search";
    private static final String SUBMIT = "emr:record:submit";
    private static final String SIGN = "emr:record:sign";
    private static final String ARCHIVE = "emr:record:archive";
    private static final String AMEND = "emr:record:amend";
    private static final String APPROVE = "emr:record:approve";

    private final RecordService service;

    public RecordController(RecordService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<RecordView> create(
            @Valid @RequestBody CreateRecordReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<MedicalRecord>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID visitId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, patientId, visitId, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecordView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** CDR 检索：关键词 + 患者/文书类型/状态/建档日期组合过滤；检索行为留痕。 */
    @GetMapping("/search")
    public ApiResponse<List<RecordHit>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, SEARCH);
        return ApiResponse.ok(service.search(q, patientId, docType, status, from, to, limit, actor));
    }

    /** 打印视图（HTML，浏览器打印 → 另存为 PDF）；未归档带水印，打印行为留痕。 */
    @GetMapping(value = "/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> print(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, READ);
        // 整页 PHI：禁脚本、禁外链、禁缓存，避免病历被浏览器/代理留存
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store, no-cache, must-revalidate, private")
                .header("Pragma", "no-cache")
                .body(service.printHtml(id, actor));
    }

    /** 保存草稿（版本号必填）。 */
    @PutMapping("/{id}")
    public ApiResponse<RecordView> save(
            @PathVariable UUID id,
            @Valid @RequestBody SaveContentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.save(id, req, actor));
    }

    /** 提交（自动跑质控，FATAL 项未通过返回 409 与明细）。 */
    @PostMapping("/{id}/submit")
    public ApiResponse<SubmitResult> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, SUBMIT);
        return ApiResponse.ok(service.submit(id, actor));
    }

    /** 提交前预览质控（不落库）。 */
    @GetMapping("/{id}/qc")
    public ApiResponse<QcReport> previewQc(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.previewQc(id));
    }

    /** 签名（三级：AUTHOR → ATTENDING → CHIEF，缺省为书写医师）。 */
    @PostMapping("/{id}/sign")
    public ApiResponse<RecordView> sign(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SignReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, SIGN);
        return ApiResponse.ok(service.sign(id, req == null ? null : req.level(), actor));
    }

    /** 归档（终态）。 */
    @PostMapping("/{id}/archive")
    public ApiResponse<RecordView> archive(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ARCHIVE);
        return ApiResponse.ok(service.archive(id, actor));
    }

    /** 修订（签名后修改，留痕并需重新签名）。 */
    @PostMapping("/{id}/amend")
    public ApiResponse<RecordView> amend(
            @PathVariable UUID id,
            @Valid @RequestBody AmendReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, AMEND);
        return ApiResponse.ok(service.amend(id, req, actor));
    }

    /** 归档后病案修改申请。 */
    @PostMapping("/{id}/change-requests")
    public ApiResponse<ChangeRequest> requestChange(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeRequestReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, AMEND);
        return ApiResponse.ok(service.requestChange(id, req, actor));
    }

    @GetMapping("/{id}/change-requests")
    public ApiResponse<List<ChangeRequest>> listChanges(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listChanges(id));
    }

    /** 审批通过：应用修改内容，revision+1 后必须重新签名与归档。 */
    @PostMapping("/{id}/change-requests/{requestId}/approve")
    public ApiResponse<RecordView> approveChange(
            @PathVariable UUID id,
            @PathVariable UUID requestId,
            @RequestBody(required = false) ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.approveChange(id, requestId, req, actor));
    }

    /** 审批驳回：病历保持归档不变。 */
    @PostMapping("/{id}/change-requests/{requestId}/reject")
    public ApiResponse<ChangeRequest> rejectChange(
            @PathVariable UUID id,
            @PathVariable UUID requestId,
            @RequestBody(required = false) ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.rejectChange(id, requestId, req, actor));
    }

    /** 校验内容是否被篡改（重算哈希与签名哈希比对）。 */
    @GetMapping("/{id}/verify")
    public ApiResponse<HashVerify> verify(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.verify(id));
    }
}
