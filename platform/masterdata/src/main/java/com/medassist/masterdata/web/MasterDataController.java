package com.medassist.masterdata.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.masterdata.domain.MasterDataModel.AuditLog;
import com.medassist.masterdata.domain.MasterDataModel.ChangeRequest;
import com.medassist.masterdata.domain.MasterDataModel.DomainSummary;
import com.medassist.masterdata.domain.MasterDataModel.ExternalSystem;
import com.medassist.masterdata.domain.MasterDataModel.ImportBatch;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.ItemDetail;
import com.medassist.masterdata.domain.MasterDataModel.MappingLookup;
import com.medassist.masterdata.domain.MasterDataModel.QualityReport;
import com.medassist.masterdata.domain.MasterDataModel.VersionChainResult;
import com.medassist.masterdata.service.MasterDataImportService;
import com.medassist.masterdata.service.MasterDataImportService.ItemSpec;
import com.medassist.masterdata.service.MasterDataImportService.MappingSpec;
import com.medassist.masterdata.service.MasterDataService;
import com.medassist.masterdata.service.MasterDataSyncService;
import com.medassist.masterdata.web.MasterDataDtos.ApproveReq;
import com.medassist.masterdata.web.MasterDataDtos.ImportItemReq;
import com.medassist.masterdata.web.MasterDataDtos.ImportMappingReq;
import com.medassist.masterdata.web.MasterDataDtos.ItemCreateReq;
import com.medassist.masterdata.web.MasterDataDtos.ItemStatusReq;
import com.medassist.masterdata.web.MasterDataDtos.JsonImportReq;
import com.medassist.masterdata.web.MasterDataDtos.MappingDeleteReq;
import com.medassist.masterdata.web.MasterDataDtos.MappingReq;
import com.medassist.masterdata.web.MasterDataDtos.RejectReq;
import jakarta.validation.Valid;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主数据治理 API。
 *
 * <p>权限切分（auth V87）：
 * <ul>
 *   <li>{@code mdm:read}：查询、导出、质量看板、版本校验；</li>
 *   <li>{@code mdm:write}：提交变更申请（条目的增改停、映射的增改停）；</li>
 *   <li>{@code mdm:approve}：审批/驳回；提交人不能审批自己的申请（服务层强制）；</li>
 *   <li>{@code mdm:import}：源表同步、JSON/CSV 批量导入；</li>
 *   <li>{@code mdm:audit:read}：审计查询。</li>
 * </ul>
 *
 * <p>所有写操作都要求真实操作人（{@code X-Actor}），缺失时 403，禁止匿名改动主数据。
 */
@RestController
@RequestMapping("/mdm")
public class MasterDataController {

    private static final String READ = "mdm:read";
    private static final String WRITE = "mdm:write";
    private static final String APPROVE = "mdm:approve";
    private static final String IMPORT = "mdm:import";
    private static final String AUDIT_READ = "mdm:audit:read";

    private final MasterDataService service;
    private final MasterDataImportService importService;
    private final MasterDataSyncService syncService;

    public MasterDataController(MasterDataService service, MasterDataImportService importService,
                                MasterDataSyncService syncService) {
        this.service = service;
        this.importService = importService;
        this.syncService = syncService;
    }

    // ------------------------------------------------------------------ 域与系统

    @GetMapping("/domains")
    public ApiResponse<List<DomainSummary>> domains(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listDomains());
    }

    @GetMapping("/external-systems")
    public ApiResponse<List<ExternalSystem>> externalSystems(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listExternalSystems());
    }

    /** 从源表同步（dryRun=true 只算差异不落库）。 */
    @PostMapping("/domains/{domainCode}/sync")
    public ApiResponse<Object> sync(
            @PathVariable String domainCode,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, IMPORT);
        return ApiResponse.ok(syncService.sync(domainCode, dryRun, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 条目

    @GetMapping("/items")
    public ApiResponse<PageResult<Item>> items(
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean hasMapping,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listItems(domainCode, keyword, status, hasMapping, page, size));
    }

    @GetMapping("/items/{domainCode}/{localCode}")
    public ApiResponse<ItemDetail> item(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.itemDetail(domainCode, localCode));
    }

    @GetMapping("/items/{domainCode}/{localCode}/versions")
    public ApiResponse<PageResult<com.medassist.masterdata.domain.MasterDataModel.ItemVersion>> versions(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listVersions(domainCode, localCode, page, size));
    }

    /** 版本链完整性校验（重算哈希链）。 */
    @GetMapping("/items/{domainCode}/{localCode}/versions/verify")
    public ApiResponse<VersionChainResult> verifyVersions(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.verifyChain(domainCode, localCode, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 变更申请

    @PostMapping("/change-requests/item-create/{domainCode}/{localCode}")
    public ApiResponse<ChangeRequest> submitCreate(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @Valid @RequestBody ItemCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", req.name());
        fields.put("alias", req.alias());
        fields.put("category", req.category());
        fields.put("spec", req.spec());
        fields.put("unit", req.unit());
        fields.put("status", req.status());
        fields.put("effectiveFrom", dateText(req.effectiveFrom()));
        fields.put("effectiveTo", dateText(req.effectiveTo()));
        if (req.attributes() != null) {
            fields.put("attributes", req.attributes());
        }
        return ApiResponse.ok(service.submit(ChangeRequest.ITEM_CREATE, domainCode, localCode,
                fields, req.reason(), actor, clientIp, traceId));
    }

    /**
     * 修改条目：请求体是字段补丁（未出现的字段保持不变）。
     *
     * <p>用 Map 而不是定型 DTO 是为了区分"未提供"与"显式置空"；
     * 具体字段校验在服务层完成（与批量导入共用同一套规则）。
     */
    @PostMapping("/change-requests/item-update/{domainCode}/{localCode}")
    public ApiResponse<ChangeRequest> submitUpdate(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        Map<String, Object> fields = new LinkedHashMap<>(body);
        Object reason = fields.remove("reason");
        return ApiResponse.ok(service.submit(ChangeRequest.ITEM_UPDATE, domainCode, localCode,
                fields, reason instanceof String text ? text : null, actor, clientIp, traceId));
    }

    @PostMapping("/change-requests/item-status/{domainCode}/{localCode}")
    public ApiResponse<ChangeRequest> submitStatus(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @Valid @RequestBody ItemStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.submit(ChangeRequest.ITEM_STATUS, domainCode, localCode,
                Map.of("status", req.status()), req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/change-requests/mapping-upsert/{domainCode}/{localCode}")
    public ApiResponse<ChangeRequest> submitMapping(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @Valid @RequestBody MappingReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("systemCode", req.systemCode());
        fields.put("externalCode", req.externalCode());
        fields.put("externalName", req.externalName());
        fields.put("mapType", req.mapType());
        fields.put("primary", req.primary());
        fields.put("status", req.status());
        fields.put("effectiveFrom", dateText(req.effectiveFrom()));
        fields.put("effectiveTo", dateText(req.effectiveTo()));
        fields.put("remark", req.remark());
        return ApiResponse.ok(service.submit(ChangeRequest.MAPPING_UPSERT, domainCode, localCode,
                fields, req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/change-requests/mapping-delete/{domainCode}/{localCode}")
    public ApiResponse<ChangeRequest> submitMappingDelete(
            @PathVariable String domainCode,
            @PathVariable String localCode,
            @Valid @RequestBody MappingDeleteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("systemCode", req.systemCode());
        fields.put("externalCode", req.externalCode());
        return ApiResponse.ok(service.submit(ChangeRequest.MAPPING_DELETE, domainCode, localCode,
                fields, req.reason(), actor, clientIp, traceId));
    }

    @GetMapping("/change-requests")
    public ApiResponse<List<ChangeRequest>> changeRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listChangeRequests(status, domainCode, changeType, limit));
    }

    @GetMapping("/change-requests/{id}")
    public ApiResponse<ChangeRequest> changeRequest(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.changeRequest(id));
    }

    @PostMapping("/change-requests/{id}/approve")
    public ApiResponse<ChangeRequest> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.approve(id, req == null ? null : req.comment(), actor,
                clientIp, traceId));
    }

    @PostMapping("/change-requests/{id}/reject")
    public ApiResponse<ChangeRequest> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.reject(id, req.reviewComment(), actor, clientIp, traceId));
    }

    @PostMapping("/change-requests/{id}/cancel")
    public ApiResponse<ChangeRequest> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.cancel(id, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 映射解析与导出

    @GetMapping("/mappings/resolve")
    public ApiResponse<MappingLookup> resolve(
            @RequestParam String systemCode,
            @RequestParam String externalCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.resolveMapping(systemCode, externalCode, actor, clientIp, traceId));
    }

    @GetMapping(value = "/mappings/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportMappings(
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String systemCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        byte[] body = service.exportMappingsCsv(domainCode, systemCode).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"mdm-mappings.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    // ------------------------------------------------------------------ 质量与审计

    @GetMapping("/quality")
    public ApiResponse<QualityReport> quality(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.quality());
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit(
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, AUDIT_READ);
        return ApiResponse.ok(service.listAudit(domainCode, actor, action, limit));
    }

    // ------------------------------------------------------------------ 导入

    @PostMapping("/imports/json/preview")
    public ApiResponse<ImportBatch> previewJson(
            @Valid @RequestBody JsonImportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, IMPORT);
        return ApiResponse.ok(importService.previewJson(req.domainCode(), req.fileName(),
                toSpecs(req.items()), actor, clientIp, traceId));
    }

    @PostMapping("/imports/json/apply")
    public ApiResponse<ImportBatch> applyJson(
            @RequestParam String previewBatchNo,
            @Valid @RequestBody JsonImportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, IMPORT);
        return ApiResponse.ok(importService.applyJson(req.domainCode(), req.fileName(),
                toSpecs(req.items()), previewBatchNo, actor, clientIp, traceId));
    }

    @PostMapping(value = "/imports/csv/preview",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ApiResponse<ImportBatch> previewCsv(
            @RequestBody String csv,
            @RequestParam(required = false) String fileName,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, IMPORT);
        return ApiResponse.ok(importService.previewCsv(csv, fileName, actor, clientIp, traceId));
    }

    @PostMapping(value = "/imports/csv/apply",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ApiResponse<ImportBatch> applyCsv(
            @RequestBody String csv,
            @RequestParam String previewBatchNo,
            @RequestParam(required = false) String fileName,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, IMPORT);
        return ApiResponse.ok(importService.applyCsv(csv, fileName, previewBatchNo, actor,
                clientIp, traceId));
    }

    @GetMapping("/imports")
    public ApiResponse<List<ImportBatch>> imports(
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(importService.listBatches(domainCode, format, limit));
    }

    @GetMapping("/imports/{id}")
    public ApiResponse<ImportBatch> importBatch(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(importService.importBatch(id, limit, offset));
    }

    // ------------------------------------------------------------------ 工具

    private static List<ItemSpec> toSpecs(List<ImportItemReq> items) {
        return items.stream().map(item -> new ItemSpec(
                item.localCode(), item.name(), item.alias(), item.category(), item.spec(),
                item.unit(), item.status(), dateText(item.effectiveFrom()),
                dateText(item.effectiveTo()), item.attributes(), toMappingSpecs(item.mappings())))
                .toList();
    }

    private static List<MappingSpec> toMappingSpecs(List<ImportMappingReq> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        return mappings.stream().map(mapping -> new MappingSpec(
                mapping.systemCode(), mapping.externalCode(), mapping.externalName(),
                mapping.mapType(), mapping.primary(), dateText(mapping.effectiveFrom()),
                dateText(mapping.effectiveTo()), mapping.remark())).toList();
    }

    private static String dateText(java.time.LocalDate date) {
        return date == null ? null : date.toString();
    }
}
