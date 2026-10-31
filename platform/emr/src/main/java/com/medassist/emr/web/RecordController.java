package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.service.RecordService;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
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
    private static final String SUBMIT = "emr:record:submit";
    private static final String SIGN = "emr:record:sign";
    private static final String ARCHIVE = "emr:record:archive";
    private static final String AMEND = "emr:record:amend";

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

    /** 签名（记录签名人与内容 SHA-256）。 */
    @PostMapping("/{id}/sign")
    public ApiResponse<RecordView> sign(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, SIGN);
        return ApiResponse.ok(service.sign(id, actor));
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

    /** 校验内容是否被篡改（重算哈希与签名哈希比对）。 */
    @GetMapping("/{id}/verify")
    public ApiResponse<HashVerify> verify(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.verify(id));
    }
}
