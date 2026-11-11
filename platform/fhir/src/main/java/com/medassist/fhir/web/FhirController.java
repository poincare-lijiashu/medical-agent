package com.medassist.fhir.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.fhir.audit.FhirAuditRepository;
import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.map.FhirMapper;
import com.medassist.fhir.repo.FhirQueries.EncounterQuery;
import com.medassist.fhir.repo.FhirQueries.MedicationRequestQuery;
import com.medassist.fhir.repo.FhirQueries.ObservationQuery;
import com.medassist.fhir.repo.FhirQueries.PatientQuery;
import com.medassist.fhir.repo.FhirRepository.Page;
import com.medassist.fhir.service.FhirReadService;
import com.medassist.fhir.service.FhirReadService.PatientWithCrosswalks;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 只读 REST 接口。
 *
 * <p>路径同时支持 {@code /fhir/**}（服务直连）与 {@code /api/v2/fhir/**}（经网关）。
 * 之所以不剥掉 {@code /api/v2}，是因为 Bundle 里的 {@code fullUrl}/{@code self} 是
 * 对端回查的地址：路径一旦在网关被改写，返回的链接就指向一个不存在的路径。
 *
 * <p>每个请求都会落一条审计（见 {@code fhir_svc.access_log}）；审计写失败时请求失败，
 * 不会出现"读了 PHI 但没痕迹"。
 */
@RestController
@RequestMapping({"/fhir", "/api/v2/fhir"})
public class FhirController {

    private static final String METRIC_REQUESTS = "medassist.fhir.requests";

    private final FhirReadService service;
    private final FhirMapper mapper;
    private final FhirFormats formats;
    private final FhirCapabilities capabilities;
    private final FhirBundleFactory bundles;
    private final FhirAuditRepository auditRepository;
    private final FhirProperties properties;
    private final MeterRegistry meter;

    public FhirController(FhirReadService service, FhirMapper mapper, FhirFormats formats,
                          FhirCapabilities capabilities, FhirBundleFactory bundles,
                          FhirAuditRepository auditRepository, FhirProperties properties,
                          MeterRegistry meter) {
        this.service = service;
        this.mapper = mapper;
        this.formats = formats;
        this.capabilities = capabilities;
        this.bundles = bundles;
        this.auditRepository = auditRepository;
        this.properties = properties;
        this.meter = meter;
    }

    // ------------------------------------------------------------------ 元数据

    @GetMapping("/metadata")
    public ResponseEntity<String> metadata(
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();
        CapabilityStatement statement = capabilities.build(call.baseUrl());
        audit(call, "METADATA", "CapabilityStatement", null, FhirCall.paramSummary(request), 1);
        count("CapabilityStatement", "ok");
        return formats.response(statement, call.format());
    }

    // -------------------------------------------------------------------- 患者

    @GetMapping("/Patient")
    public ResponseEntity<String> searchPatients(
            @RequestParam(required = false) String identifier,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean active,
            @RequestParam(name = "_id", required = false) String id,
            @RequestParam(name = "_count", required = false) Integer count,
            @RequestParam(name = "_offset", required = false) Integer offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();

        FhirSearchParams.Identifier parsed = FhirSearchParams.identifier(identifier);
        PatientQuery query = new PatientQuery(
                id == null ? null : FhirSearchParams.uuid(id, "_id"),
                parsed == null ? null : parsed.system(),
                parsed == null ? null : parsed.value(),
                name,
                active,
                properties.pageSize(count),
                properties.offset(offset));

        Page<PatientWithCrosswalks> page = service.searchPatients(query);
        List<Patient> resources = page.rows().stream()
                .map(row -> mapper.toPatient(row.row(), row.crosswalks(), call.phiReadable()))
                .toList();
        Bundle bundle = bundles.search("Patient", resources, page.total(),
                properties.pageSize(count), properties.offset(offset), call, request);
        audit(call, "SEARCH", "Patient", null, FhirCall.paramSummary(request), page.total());
        count("Patient", "ok");
        return formats.response(bundle, call.format());
    }

    @GetMapping("/Patient/{id}")
    public ResponseEntity<String> readPatient(
            @PathVariable String id,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();
        UUID patientId = FhirSearchParams.uuid(id, "Patient.id");

        PatientWithCrosswalks found = service.readPatient(patientId).orElse(null);
        if (found == null) {
            audit(call, "READ", "Patient", patientId.toString(), null, 0, "NOT_FOUND");
            count("Patient", "not_found");
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该患者");
        }
        audit(call, "READ", "Patient", patientId.toString(), null, 1);
        count("Patient", "ok");
        return formats.response(
                mapper.toPatient(found.row(), found.crosswalks(), call.phiReadable()),
                call.format());
    }

    // -------------------------------------------------------------------- 就诊

    @GetMapping("/Encounter")
    public ResponseEntity<String> searchEncounters(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String status,
            @RequestParam(value = "date", required = false) List<String> date,
            @RequestParam(name = "_id", required = false) String id,
            @RequestParam(name = "_count", required = false) Integer count,
            @RequestParam(name = "_offset", required = false) Integer offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();

        EncounterQuery query = new EncounterQuery(
                id == null ? null : FhirSearchParams.uuid(id, "_id"),
                FhirSearchParams.patient(patient),
                FhirSearchParams.status(status, FhirSearchParams.ENCOUNTER_STATUS, "Encounter"),
                FhirSearchParams.date(date),
                properties.pageSize(count),
                properties.offset(offset));

        Page<com.medassist.fhir.repo.FhirRows.EncounterRow> page =
                service.searchEncounters(query, call.campusFilter());
        List<Encounter> resources = page.rows().stream().map(mapper::toEncounter).toList();
        Bundle bundle = bundles.search("Encounter", resources, page.total(),
                properties.pageSize(count), properties.offset(offset), call, request);
        audit(call, "SEARCH", "Encounter", null, FhirCall.paramSummary(request), page.total());
        count("Encounter", "ok");
        return formats.response(bundle, call.format());
    }

    @GetMapping("/Encounter/{id}")
    public ResponseEntity<String> readEncounter(
            @PathVariable String id,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();
        UUID encounterId = FhirSearchParams.uuid(id, "Encounter.id");

        var found = service.readEncounter(encounterId, call.campusFilter()).orElse(null);
        if (found == null) {
            audit(call, "READ", "Encounter", encounterId.toString(), null, 0, "NOT_FOUND");
            count("Encounter", "not_found");
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该就诊或住院记录");
        }
        audit(call, "READ", "Encounter", encounterId.toString(), null, 1);
        count("Encounter", "ok");
        return formats.response(mapper.toEncounter(found), call.format());
    }

    // ------------------------------------------------------------------ 观察

    @GetMapping("/Observation")
    public ResponseEntity<String> searchObservations(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(value = "date", required = false) List<String> date,
            @RequestParam(name = "_id", required = false) String id,
            @RequestParam(name = "_count", required = false) Integer count,
            @RequestParam(name = "_offset", required = false) Integer offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();

        ObservationQuery query = new ObservationQuery(
                id == null ? null : FhirSearchParams.uuid(id, "_id"),
                FhirSearchParams.patient(patient),
                FhirSearchParams.category(category),
                FhirSearchParams.status(status, FhirSearchParams.OBSERVATION_STATUS, "Observation"),
                FhirSearchParams.date(date),
                properties.pageSize(count),
                properties.offset(offset));

        Page<com.medassist.fhir.repo.FhirRows.ObservationRow> page =
                service.searchObservations(query, call.campusFilter());
        List<Observation> resources = page.rows().stream().map(mapper::toObservation).toList();
        Bundle bundle = bundles.search("Observation", resources, page.total(),
                properties.pageSize(count), properties.offset(offset), call, request);
        audit(call, "SEARCH", "Observation", null, FhirCall.paramSummary(request), page.total());
        count("Observation", "ok");
        return formats.response(bundle, call.format());
    }

    @GetMapping("/Observation/{id}")
    public ResponseEntity<String> readObservation(
            @PathVariable String id,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();
        UUID observationId = FhirSearchParams.uuid(id, "Observation.id");

        var found = service.readObservation(observationId, call.campusFilter()).orElse(null);
        if (found == null) {
            audit(call, "READ", "Observation", observationId.toString(), null, 0, "NOT_FOUND");
            count("Observation", "not_found");
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该检验指标");
        }
        audit(call, "READ", "Observation", observationId.toString(), null, 1);
        count("Observation", "ok");
        return formats.response(mapper.toObservation(found), call.format());
    }

    // ---------------------------------------------------------------- 处方明细

    @GetMapping("/MedicationRequest")
    public ResponseEntity<String> searchMedicationRequests(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String status,
            @RequestParam(value = "date", required = false) List<String> date,
            @RequestParam(name = "_id", required = false) String id,
            @RequestParam(name = "_count", required = false) Integer count,
            @RequestParam(name = "_offset", required = false) Integer offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();

        MedicationRequestQuery query = new MedicationRequestQuery(
                id == null ? null : FhirSearchParams.uuid(id, "_id"),
                FhirSearchParams.patient(patient),
                FhirSearchParams.status(status,
                        FhirSearchParams.MEDICATION_REQUEST_STATUS, "MedicationRequest"),
                FhirSearchParams.date(date),
                properties.pageSize(count),
                properties.offset(offset));

        Page<com.medassist.fhir.repo.FhirRows.MedicationRequestRow> page =
                service.searchMedicationRequests(query, call.campusFilter());
        List<MedicationRequest> resources = page.rows().stream()
                .map(mapper::toMedicationRequest).toList();
        Bundle bundle = bundles.search("MedicationRequest", resources, page.total(),
                properties.pageSize(count), properties.offset(offset), call, request);
        audit(call, "SEARCH", "MedicationRequest", null,
                FhirCall.paramSummary(request), page.total());
        count("MedicationRequest", "ok");
        return formats.response(bundle, call.format());
    }

    @GetMapping("/MedicationRequest/{id}")
    public ResponseEntity<String> readMedicationRequest(
            @PathVariable String id,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest request) {
        FhirCall call = FhirCall.from(request, format, properties, formats);
        call.requireRead();
        UUID requestId = FhirSearchParams.uuid(id, "MedicationRequest.id");

        var found = service.readMedicationRequest(requestId, call.campusFilter()).orElse(null);
        if (found == null) {
            audit(call, "READ", "MedicationRequest", requestId.toString(), null, 0, "NOT_FOUND");
            count("MedicationRequest", "not_found");
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该处方明细");
        }
        audit(call, "READ", "MedicationRequest", requestId.toString(), null, 1);
        count("MedicationRequest", "ok");
        return formats.response(mapper.toMedicationRequest(found), call.format());
    }

    // ------------------------------------------------------------------ 内部

    private void audit(FhirCall call, String action, String resourceType, String resourceId,
                       String summary, long resultCount) {
        audit(call, action, resourceType, resourceId, summary, resultCount, "OK");
    }

    private void audit(FhirCall call, String action, String resourceType, String resourceId,
                       String summary, long resultCount, String outcome) {
        auditRepository.record(call.actor(), action, resourceType, resourceId, summary,
                (int) Math.min(resultCount, Integer.MAX_VALUE), outcome,
                call.clientIp(), call.traceId());
    }

    private void count(String resourceType, String outcome) {
        meter.counter(METRIC_REQUESTS, "resource", resourceType, "outcome", outcome).increment();
    }
}
