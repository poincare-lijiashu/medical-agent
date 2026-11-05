package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.PacsAccessLog;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.ReadingQueueItem;
import com.medassist.tech.domain.TechModel.WorklistItem;
import com.medassist.tech.service.PacsDicomService;
import com.medassist.tech.service.PacsReadingService;
import com.medassist.tech.service.PacsService;
import com.medassist.tech.service.TechAccessGuard;
import com.medassist.tech.web.PacsDtos.InstancePage;
import com.medassist.tech.web.PacsDtos.ReadingNoteReq;
import com.medassist.tech.web.PacsDtos.ReadingTaskView;
import com.medassist.tech.web.PacsDtos.RegisterStudyReq;
import com.medassist.tech.web.PacsDtos.SeriesResponse;
import com.medassist.tech.web.PacsDtos.UpdateStudyStatusReq;
import com.medassist.tech.web.PacsDtos.ViewerLink;
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
 * 影像（PACS）集成 API：登记回传（设备侧） + 阅片队列（医师侧） + DICOMweb 调阅。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code tech:pacs:read} —— 查询检查/队列/序列、取影像（技师/医师/管理员）；</li>
 *   <li>{@code tech:pacs:write} —— 登记与状态回传（PACS 对接账号使用 TECHNICIAN）；</li>
 *   <li>{@code tech:report:write} —— 领取/释放/完成阅片（写报告的医师本人）；</li>
 *   <li>{@code tech:pacs:audit} —— 查看调阅审计（合规岗位）。</li>
 * </ul>
 *
 * <p>写操作要求 {@code X-Actor} 必填（网关漏注入直接 400），保证登记/回传/阅片都能追责。
 */
@RestController
@RequestMapping("/tech/pacs")
public class PacsController {

    private static final String READ = "tech:pacs:read";
    private static final String WRITE = "tech:pacs:write";
    private static final String READING = "tech:report:write";
    private static final String AUDIT = "tech:pacs:audit";

    private final PacsService service;
    private final PacsReadingService readingService;
    private final PacsDicomService dicomService;
    private final TechAccessGuard guard;

    public PacsController(PacsService service, PacsReadingService readingService,
                          PacsDicomService dicomService, TechAccessGuard guard) {
        this.service = service;
        this.readingService = readingService;
        this.dicomService = dicomService;
        this.guard = guard;
    }

    /** 设备 Worklist：默认当天、全部设备类型；按优先级（STAT→URGENT→ROUTINE）排序。 */
    @GetMapping("/worklist")
    public ApiResponse<List<WorklistItem>> worklist(
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        // 设备调度也需要患者号对账：设备技术账号应授 phi:read；低权账号按 P0-S2 掩码
        return ApiResponse.ok(PhiMask.worklist(
                service.worklist(modality, date, limit, scope.campusFilter()), scope));
    }

    @GetMapping("/studies/{studyInstanceUid}")
    public ApiResponse<PacsStudy> byUid(
            @PathVariable String studyInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(service.byUid(studyInstanceUid));
    }

    @GetMapping("/applications/{applicationId}")
    public ApiResponse<PacsStudy> byApplication(
            @PathVariable UUID applicationId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireApplication(scope, applicationId);
        return ApiResponse.ok(service.byApplication(applicationId));
    }

    @PostMapping("/studies")
    public ApiResponse<PacsStudy> register(
            @Valid @RequestBody RegisterStudyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireApplication(scope, req.applicationId());
        return ApiResponse.ok(service.register(req, actor));
    }

    @PostMapping("/studies/{studyInstanceUid}/status")
    public ApiResponse<PacsStudy> updateStatus(
            @PathVariable String studyInstanceUid,
            @Valid @RequestBody UpdateStudyStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(service.updateStatus(studyInstanceUid, req, actor));
    }

    // ---------------------------------------------------------------- 阅片（医师侧）

    /** 阅片队列：默认全部已完成检查；state=MINE 只看自己领取的。 */
    @GetMapping("/reading/queue")
    public ApiResponse<List<ReadingQueueItem>> readingQueue(
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(PhiMask.readingQueue(
                readingService.queue(modality, state, date, limit, actor, scope.campusFilter()),
                scope));
    }

    @PostMapping("/reading/{studyInstanceUid}/claim")
    public ApiResponse<ReadingTaskView> claim(
            @PathVariable String studyInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor,
            DataScope scope) {
        Permissions.require(permissions, READING);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(readingService.claim(studyInstanceUid, actor));
    }

    @PostMapping("/reading/{studyInstanceUid}/release")
    public ApiResponse<ReadingTaskView> release(
            @PathVariable String studyInstanceUid,
            @RequestBody(required = false) ReadingNoteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor,
            DataScope scope) {
        Permissions.require(permissions, READING);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(readingService.release(studyInstanceUid, req, actor));
    }

    @PostMapping("/reading/{studyInstanceUid}/complete")
    public ApiResponse<ReadingTaskView> complete(
            @PathVariable String studyInstanceUid,
            @RequestBody(required = false) ReadingNoteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor,
            DataScope scope) {
        Permissions.require(permissions, READING);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(readingService.complete(studyInstanceUid, req, actor));
    }

    // ---------------------------------------------------------------- DICOMweb 调阅

    /**
     * 查询参数版 DICOMweb 入口（G31）：Study/Series UID 含点号，部分网关对
     * 点号路径段返回 400，因此为前端提供不带点号路径段的别名；语义与原路径接口一致。
     */
    @GetMapping("/dicom/series")
    public ApiResponse<SeriesResponse> seriesByQuery(
            @RequestParam String studyUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyUid);
        return ApiResponse.ok(dicomService.listSeries(studyUid, actor));
    }

    @GetMapping("/dicom/instances")
    public ApiResponse<InstancePage> instancesByQuery(
            @RequestParam String studyUid,
            @RequestParam String seriesUid,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyUid);
        return ApiResponse.ok(dicomService.listInstances(studyUid, seriesUid, page, size, actor));
    }

    @GetMapping("/dicom/rendered")
    public ResponseEntity<byte[]> renderedByQuery(
            @RequestParam String studyUid,
            @RequestParam String seriesUid,
            @RequestParam String sopUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyUid);
        PacsDicomService.InstancePayload payload =
                dicomService.fetchRendered(studyUid, seriesUid, sopUid, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(payload.bytes());
    }

    @GetMapping("/dicom/viewer")
    public ApiResponse<ViewerLink> viewerByQuery(
            @RequestParam String studyUid,
            @RequestParam(required = false) String seriesUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyUid);
        return ApiResponse.ok(dicomService.viewerLink(studyUid, seriesUid));
    }

    @GetMapping("/dicom/access-log")
    public ApiResponse<List<PacsAccessLog>> accessLogByQuery(
            @RequestParam String studyUid,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, AUDIT);
        guard.requireStudy(scope, studyUid);
        return ApiResponse.ok(readingService.accessLog(studyUid, limit));
    }

    /** 序列列表：PACS 实时（QIDO-RS），不可用时回退本地缓存并标注 CACHED。 */
    @GetMapping("/studies/{studyInstanceUid}/series")
    public ApiResponse<SeriesResponse> series(
            @PathVariable String studyInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(dicomService.listSeries(studyInstanceUid, actor));
    }

    /** 序列内影像分页（QIDO-RS limit/offset）；序列必须已在本检查的缓存中。 */
    @GetMapping("/studies/{studyInstanceUid}/series/{seriesInstanceUid}/instances")
    public ApiResponse<InstancePage> instances(
            @PathVariable String studyInstanceUid,
            @PathVariable String seriesInstanceUid,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(dicomService.listInstances(studyInstanceUid, seriesInstanceUid,
                page, size, actor));
    }

    /** 浏览器预览图（WADO-RS rendered，image/jpeg）；不支持 rendered 的 PACS 返回 404。 */
    @GetMapping("/studies/{studyInstanceUid}/series/{seriesInstanceUid}/instances/{sopInstanceUid}/rendered")
    public ResponseEntity<byte[]> rendered(
            @PathVariable String studyInstanceUid,
            @PathVariable String seriesInstanceUid,
            @PathVariable String sopInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        PacsDicomService.InstancePayload payload = dicomService.fetchRendered(
                studyInstanceUid, seriesInstanceUid, sopInstanceUid, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(payload.bytes());
    }

    /** 查看器深链（OHIF 等）：未配置时 configured=false，前端隐藏入口。 */
    @GetMapping("/studies/{studyInstanceUid}/viewer")
    public ApiResponse<ViewerLink> viewer(
            @PathVariable String studyInstanceUid,
            @RequestParam(required = false) String seriesUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(dicomService.viewerLink(studyInstanceUid, seriesUid));
    }

    /** 取单张影像（WADO-RS）：返回 application/dicom 字节流；未配置 PACS 时 503。 */
    @GetMapping("/studies/{studyInstanceUid}/series/{seriesInstanceUid}/instances/{sopInstanceUid}")
    public ResponseEntity<byte[]> instance(
            @PathVariable String studyInstanceUid,
            @PathVariable String seriesInstanceUid,
            @PathVariable String sopInstanceUid,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireStudy(scope, studyInstanceUid);
        PacsDicomService.InstancePayload payload =
                dicomService.fetchInstance(studyInstanceUid, seriesInstanceUid, sopInstanceUid, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(payload.bytes());
    }

    /** 调阅审计：谁在何时看过该检查的序列/影像。 */
    @GetMapping("/studies/{studyInstanceUid}/access-log")
    public ApiResponse<List<PacsAccessLog>> accessLog(
            @PathVariable String studyInstanceUid,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, AUDIT);
        guard.requireStudy(scope, studyInstanceUid);
        return ApiResponse.ok(readingService.accessLog(studyInstanceUid, limit));
    }
}
