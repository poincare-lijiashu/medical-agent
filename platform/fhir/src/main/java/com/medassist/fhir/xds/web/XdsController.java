package com.medassist.fhir.xds.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.fhir.xds.XdsCall;
import com.medassist.fhir.xds.XdsDtos.DeprecateRequest;
import com.medassist.fhir.xds.XdsDtos.DocumentSummary;
import com.medassist.fhir.xds.XdsDtos.GenerateDischargeRequest;
import com.medassist.fhir.xds.XdsDtos.GenerateLabRequest;
import com.medassist.fhir.xds.XdsDtos.ProvideRequest;
import com.medassist.fhir.xds.XdsDtos.ProvideResult;
import com.medassist.fhir.xds.XdsDtos.QueryResult;
import com.medassist.fhir.xds.XdsDtos.Retrieval;
import com.medassist.fhir.xds.XdsDtos.XdsSummary;
import com.medassist.fhir.xds.XdsService;
import com.medassist.fhir.xds.XdsValidation;
import com.medassist.fhir.web.FhirScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * IHE XDS.b 文档共享接口（REST/JSON 子集）。
 *
 * <p>路径同时支持 {@code /fhir/xds/**}（直连）与 {@code /api/v2/fhir/xds/**}（经网关），
 * 与 FHIR 开放层一致，避免网关改写路径后返回的文档链接不可回查。
 *
 * <p>权限切分：
 * <ul>
 *   <li>{@code fhir:xds:read}：检索注册表、调阅文档；</li>
 *   <li>{@code fhir:xds:phi}：注册表返回患者号明文（否则掩码）；</li>
 *   <li>{@code fhir:xds:register}：ITI-41 注册外部文档；</li>
 *   <li>{@code fhir:xds:generate}：从院内数据生成出院小结/检验报告；</li>
 *   <li>{@code fhir:xds:manage}：ITI-62 停用元数据（保留内容与审计）。</li>
 * </ul>
 */
@RestController
@RequestMapping({"/fhir/xds", "/api/v2/fhir/xds"})
public class XdsController {

    private static final String READ = "fhir:xds:read";
    private static final String PHI = "fhir:xds:phi";
    private static final String REGISTER = "fhir:xds:register";
    private static final String GENERATE = "fhir:xds:generate";
    private static final String MANAGE = "fhir:xds:manage";

    private final XdsService service;

    public XdsController(XdsService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ 运维汇总

    @GetMapping("/summary")
    public ApiResponse<XdsSummary> summary(HttpServletRequest request) {
        XdsCall call = readCall(request);
        return ApiResponse.ok(service.summary(call), call.traceId());
    }

    // ------------------------------------------------------------ ITI-41

    @PostMapping("/provide-and-register")
    public ApiResponse<ProvideResult> provideAndRegister(
            @RequestBody ProvideRequest body, HttpServletRequest request) {
        XdsCall call = call(request);
        Permissions.require(call.permissions(), REGISTER);
        requireActor(call.actor());
        return ApiResponse.ok(service.provide(body, call), call.traceId());
    }

    // ------------------------------------------------------------ ITI-18

    @GetMapping("/registry/stored-query")
    public ApiResponse<QueryResult> storedQuery(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String uniqueId,
            @RequestParam(required = false) String classCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String creationTimeFrom,
            @RequestParam(required = false) String creationTimeTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        XdsCall call = readCall(request);
        return ApiResponse.ok(service.query(patient, uniqueId, classCode, status,
                creationTimeFrom, creationTimeTo, q, limit, offset, call), call.traceId());
    }

    // ------------------------------------------------------------ ITI-43

    @GetMapping("/retrieve")
    public ResponseEntity<byte[]> retrieve(@RequestParam String uniqueId,
                                           HttpServletRequest request) {
        XdsCall call = readCall(request);
        Retrieval retrieval = service.retrieve(uniqueId, call);
        String filename = safeFilename(retrieval.uniqueId()) + extension(retrieval.mimeType());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(retrieval.mimeType()))
                .contentLength(retrieval.content().length)
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .header("X-XDS-Hash", retrieval.hashSha256())
                .header("X-XDS-Size", String.valueOf(retrieval.sizeBytes()))
                .header("X-XDS-Status", retrieval.availabilityStatus())
                .body(retrieval.content());
    }

    // ------------------------------------------------------------ 文档生成

    @PostMapping("/documents/discharge-summary")
    public ApiResponse<ProvideResult> generateDischargeSummary(
            @RequestBody GenerateDischargeRequest body, HttpServletRequest request) {
        XdsCall call = call(request);
        Permissions.require(call.permissions(), GENERATE);
        requireActor(call.actor());
        UUID visitId = XdsValidation.parseUuid(body.visitId(), "visitId");
        return ApiResponse.ok(service.generateDischargeSummary(visitId, call), call.traceId());
    }

    @PostMapping("/documents/lab-report")
    public ApiResponse<ProvideResult> generateLabReport(
            @RequestBody GenerateLabRequest body, HttpServletRequest request) {
        XdsCall call = call(request);
        Permissions.require(call.permissions(), GENERATE);
        requireActor(call.actor());
        UUID reportId = XdsValidation.parseUuid(body.reportId(), "reportId");
        return ApiResponse.ok(service.generateLabReport(reportId, call), call.traceId());
    }

    // ------------------------------------------------------------ ITI-62

    @PostMapping("/documents/{uniqueId}/deprecate")
    public ApiResponse<DocumentSummary> deprecate(@PathVariable String uniqueId,
                                                  @RequestBody DeprecateRequest body,
                                                  HttpServletRequest request) {
        XdsCall call = call(request);
        Permissions.require(call.permissions(), MANAGE);
        requireActor(call.actor());
        return ApiResponse.ok(service.deprecate(uniqueId, body, call), call.traceId());
    }

    // ------------------------------------------------------------ 内部

    private static XdsCall readCall(HttpServletRequest request) {
        XdsCall call = call(request);
        Permissions.require(call.permissions(), READ);
        requireActor(call.actor());
        return call;
    }

    private static XdsCall call(HttpServletRequest request) {
        String permissions = request.getHeader(AuthHeaders.PERMISSIONS);
        String actor = request.getHeader(AuthHeaders.ACTOR);
        String clientIp = request.getHeader(AuthHeaders.CLIENT_IP);
        String traceId = request.getHeader(AuthHeaders.TRACE_ID);
        return new XdsCall(
                actor == null ? null : actor.trim(),
                permissions,
                Permissions.has(permissions, PHI),
                FhirScopes.campusFilter(request.getHeader(AuthHeaders.CAMPUS)),
                clientIp == null || clientIp.isBlank() ? request.getRemoteAddr() : clientIp,
                traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.XDS_ACTOR_REQUIRED);
        }
    }

    private static String safeFilename(String uniqueId) {
        String value = uniqueId == null ? "" : uniqueId.replaceAll("[^0-9A-Za-z._-]", "_");
        if (value.length() > 80) {
            value = value.substring(value.length() - 80);
        }
        return value.isBlank() ? "document" : value;
    }

    private static String extension(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("pdf")) {
            return ".pdf";
        }
        if (mime.contains("xml")) {
            return ".xml";
        }
        return ".txt";
    }
}
