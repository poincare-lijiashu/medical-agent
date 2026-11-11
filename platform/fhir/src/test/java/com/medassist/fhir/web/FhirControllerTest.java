package com.medassist.fhir.web;

import com.medassist.fhir.audit.FhirAuditRepository;
import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.map.FhirMapper;
import com.medassist.fhir.repo.FhirRepository.Page;
import com.medassist.fhir.repo.FhirRows.PatientRow;
import com.medassist.fhir.service.FhirReadService;
import com.medassist.fhir.service.FhirReadService.PatientWithCrosswalks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FHIR 控制器契约测试（standalone MockMvc，不连数据库）。
 *
 * <p>覆盖对外可观测行为：权限、错误壳（OperationOutcome）、分页 Bundle、
 * 格式协商与未实现资源的 404。SQL 语义由真实库手工验证，不在这里 mock。
 */
class FhirControllerTest {

    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String READ = "fhir:read";
    private static final String READ_PHI = "fhir:read_phi";

    private FhirReadService service;
    private FhirAuditRepository audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FhirReadService.class);
        audit = mock(FhirAuditRepository.class);
        FhirFormats formats = new FhirFormats();
        FhirController controller = new FhirController(service, new FhirMapper(), formats,
                new FhirCapabilities(), new FhirBundleFactory(), audit, new FhirProperties(),
                new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(controller, new FhirFallbackController(formats))
                .setControllerAdvice(new FhirExceptionHandler(formats))
                .build();
    }

    @Test
    @DisplayName("检索患者：返回 searchset Bundle（total/entry/fullUrl），并写审计")
    void searchPatients() throws Exception {
        when(service.searchPatients(any())).thenReturn(
                new Page<>(List.of(new PatientWithCrosswalks(patientRow(), List.of())), 1));

        mvc.perform(get("/fhir/Patient")
                        .header("X-Permissions", READ + "," + READ_PHI)
                        .header("X-Actor", "tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("application/fhir+json")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].fullUrl",
                        containsString("/fhir/Patient/" + PATIENT_ID)))
                .andExpect(jsonPath("$.entry[0].resource.resourceType").value("Patient"));

        verify(audit).record(eq("tester"), eq("SEARCH"), eq("Patient"), isNull(),
                any(), eq(1), eq("OK"), any(), isNull());
    }

    @Test
    @DisplayName("无 fhir:read 权限 → 403 OperationOutcome，而不是平台私有错误壳")
    void forbiddenWithoutPermission() throws Exception {
        mvc.perform(get("/fhir/Patient").header("X-Actor", "tester"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("forbidden"));
    }

    @Test
    @DisplayName("缺少真实操作人（X-Actor）→ 403，禁止 system 兜底读 PHI")
    void forbiddenWithoutActor() throws Exception {
        mvc.perform(get("/fhir/Patient").header("X-Permissions", READ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issue[0].code").value("forbidden"))
                .andExpect(content().string(containsString("操作人")));
    }

    @Test
    @DisplayName("读取不存在的患者 → 404 not-found，并记录 NOT_FOUND 审计")
    void readPatientNotFound() throws Exception {
        when(service.readPatient(any())).thenReturn(Optional.empty());
        UUID id = UUID.randomUUID();

        mvc.perform(get("/fhir/Patient/" + id)
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.issue[0].code").value("not-found"));

        verify(audit).record(eq("tester"), eq("READ"), eq("Patient"), eq(id.toString()),
                isNull(), eq(0), eq("NOT_FOUND"), any(), isNull());
    }

    @Test
    @DisplayName("非法 UUID → 400 invalid，不返回 500")
    void invalidPatientId() throws Exception {
        mvc.perform(get("/fhir/Patient/not-a-uuid")
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issue[0].code").value("invalid"));
    }

    @Test
    @DisplayName("_format=xml → application/fhir+xml，满足 Connectathon 的 XML 端点检查")
    void xmlFormat() throws Exception {
        when(service.searchPatients(any())).thenReturn(new Page<>(List.of(), 0));

        mvc.perform(get("/fhir/Patient")
                        .param("_format", "xml")
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("application/fhir+xml")))
                .andExpect(content().string(containsString("<Bundle")));
    }

    @Test
    @DisplayName("metadata：声明 4 个已实现资源与 search-type 交互")
    void metadata() throws Exception {
        mvc.perform(get("/fhir/metadata")
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("CapabilityStatement"))
                .andExpect(jsonPath("$.fhirVersion").value("4.0.1"))
                .andExpect(jsonPath("$.rest[0].resource.length()").value(4))
                .andExpect(jsonPath("$.rest[0].resource[0].type").value("Patient"))
                .andExpect(jsonPath("$.rest[0].resource[0].interaction[1].code")
                        .value("search-type"));
    }

    @Test
    @DisplayName("未实现的资源 → 404 not-supported，而不是 Whitelabel JSON")
    void unsupportedResource() throws Exception {
        mvc.perform(get("/fhir/ImagingStudy")
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("not-supported"));
    }

    @Test
    @DisplayName("非法的 _count（0）→ 400")
    void invalidCount() throws Exception {
        mvc.perform(get("/fhir/Patient")
                        .param("_count", "0")
                        .header("X-Permissions", READ)
                        .header("X-Actor", "tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issue[0].code").value("invalid"));
    }

    private static PatientRow patientRow() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T09:30:00+08:00");
        return new PatientRow(PATIENT_ID, "P20261111001", "张三", "MALE",
                LocalDate.parse("1980-01-02"), null, null, "ACTIVE", null, 1,
                now, now);
    }
}
