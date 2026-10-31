package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.WorklistItem;
import com.medassist.tech.service.PacsService;
import com.medassist.tech.web.PacsDtos.RegisterStudyReq;
import com.medassist.tech.web.PacsDtos.UpdateStudyStatusReq;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 影像（PACS）集成 API。
 *
 * <p>权限：查询 {@code tech:pacs:read}（技师/医师/管理员）；登记与状态回传
 * {@code tech:pacs:write}（技师/管理员，PACS 对接账号使用 TECHNICIAN）。
 *
 * <p>写操作要求 {@code X-Actor} 必填（网关漏注入直接 400），保证登记/回传都能追责。
 */
@RestController
@RequestMapping("/tech/pacs")
public class PacsController {

    private static final String READ = "tech:pacs:read";
    private static final String WRITE = "tech:pacs:write";

    private final PacsService service;

    public PacsController(PacsService service) {
        this.service = service;
    }

    /** 设备 Worklist：默认当天、全部设备类型；按优先级（STAT→URGENT→ROUTINE）排序。 */
    @GetMapping("/worklist")
    public ApiResponse<List<WorklistItem>> worklist(
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.worklist(modality, date, limit));
    }

    @GetMapping("/studies/{studyInstanceUid}")
    public ApiResponse<PacsStudy> byUid(
            @PathVariable String studyInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.byUid(studyInstanceUid));
    }

    @GetMapping("/applications/{applicationId}")
    public ApiResponse<PacsStudy> byApplication(
            @PathVariable UUID applicationId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.byApplication(applicationId));
    }

    @PostMapping("/studies")
    public ApiResponse<PacsStudy> register(
            @Valid @RequestBody RegisterStudyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.register(req, actor));
    }

    @PostMapping("/studies/{studyInstanceUid}/status")
    public ApiResponse<PacsStudy> updateStatus(
            @PathVariable String studyInstanceUid,
            @Valid @RequestBody UpdateStudyStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.updateStatus(studyInstanceUid, req, actor));
    }
}
