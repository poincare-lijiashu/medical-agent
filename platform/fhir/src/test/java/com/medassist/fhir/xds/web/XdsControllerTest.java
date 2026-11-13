package com.medassist.fhir.xds.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.fhir.xds.XdsDtos.DocumentSummary;
import com.medassist.fhir.xds.XdsDtos.ProvideResult;
import com.medassist.fhir.xds.XdsDtos.QueryResult;
import com.medassist.fhir.xds.XdsDtos.Retrieval;
import com.medassist.fhir.xds.XdsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * XDS 控制器契约测试（standalone MockMvc，不连数据库）。
 *
 * <p>锁住对外可观测行为：权限分离、真实操作人、统一 ApiResponse 错误壳、
 * 调阅的哈希/大小响应头与缺失文档 404。
 */
class XdsControllerTest {

    private static final String READ = "fhir:xds:read";
    private static final String REGISTER = "fhir:xds:register";
    private static final String GENERATE = "fhir:xds:generate";
    private static final String MANAGE = "fhir:xds:manage";
    private static final String ACTOR = "tester";

    private XdsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(XdsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new XdsController(service))
                .setControllerAdvice(new XdsExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("无 fhir:xds:read 权限 → 403 ApiResponse（不是 FHIR OperationOutcome）")
    void queryForbidden() throws Exception {
        mvc.perform(get("/fhir/xds/registry/stored-query").param("patient", "P001")
                        .header("X-Actor", ACTOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }

    @Test
    @DisplayName("缺少真实操作人 → 403 XDS_ACTOR_REQUIRED")
    void actorRequired() throws Exception {
        mvc.perform(get("/fhir/xds/registry/stored-query").param("patient", "P001")
                        .header("X-Permissions", READ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.XDS_ACTOR_REQUIRED.code()));
    }

    @Test
    @DisplayName("检索：返回统一信封与分页字段")
    void queryOk() throws Exception {
        when(service.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new QueryResult(List.of(), 0, 20, 0));

        mvc.perform(get("/fhir/xds/registry/stored-query")
                        .param("patient", "P001")
                        .param("limit", "20")
                        .header("X-Permissions", READ)
                        .header("X-Actor", ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.limit").value(20));

        verify(service).query(eq("P001"), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(20), isNull(), any());
    }

    @Test
    @DisplayName("注册：只有 read 权限 → 403；register 权限 → 200")
    void providePermission() throws Exception {
        String body = "{\"patientId\":\"P001\"}";
        mvc.perform(post("/fhir/xds/provide-and-register")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", READ).header("X-Actor", ACTOR))
                .andExpect(status().isForbidden());

        when(service.provide(any(), any())).thenReturn(new ProvideResult("ss-1",
                "urn:uuid:ss-1", "doc-1", "urn:uuid:doc-1", "a".repeat(64), 10, "APPROVED"));
        mvc.perform(post("/fhir/xds/provide-and-register")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", REGISTER).header("X-Actor", ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uniqueId").value("urn:uuid:doc-1"))
                .andExpect(jsonPath("$.data.sizeBytes").value(10));
    }

    @Test
    @DisplayName("调阅：返回字节流、Content-Type 与哈希/大小/状态头")
    void retrieveOk() throws Exception {
        byte[] content = "<ClinicalDocument/>".getBytes(StandardCharsets.UTF_8);
        Retrieval retrieval = new Retrieval("urn:uuid:doc-1", "出院小结", "text/xml",
                "b".repeat(64), content.length, "APPROVED", Instant.parse("2026-11-13T01:00:00Z"),
                content);
        when(service.retrieve(eq("urn:uuid:doc-1"), any())).thenReturn(retrieval);

        mvc.perform(get("/fhir/xds/retrieve")
                        .param("uniqueId", "urn:uuid:doc-1")
                        .header("X-Permissions", READ).header("X-Actor", ACTOR))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/xml")))
                .andExpect(header().string("X-XDS-Hash", "b".repeat(64)))
                .andExpect(header().string("X-XDS-Size", String.valueOf(content.length)))
                .andExpect(header().string("X-XDS-Status", "APPROVED"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(content));
    }

    @Test
    @DisplayName("调阅不存在文档 → 404 + XDS_DOCUMENT_NOT_FOUND")
    void retrieveNotFound() throws Exception {
        when(service.retrieve(eq("1.2.3.4"), any()))
                .thenThrow(new BusinessException(ErrorCode.XDS_DOCUMENT_NOT_FOUND));

        mvc.perform(get("/fhir/xds/retrieve")
                        .param("uniqueId", "1.2.3.4")
                        .header("X-Permissions", READ).header("X-Actor", ACTOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.XDS_DOCUMENT_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("生成：register 权限不能生成；generate 权限可以")
    void generatePermission() throws Exception {
        String body = "{\"visitId\":\"11111111-1111-1111-1111-111111111111\"}";
        mvc.perform(post("/fhir/xds/documents/discharge-summary")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", REGISTER).header("X-Actor", ACTOR))
                .andExpect(status().isForbidden());

        when(service.generateDischargeSummary(any(), any())).thenReturn(new ProvideResult(
                "ss-1", "urn:uuid:ss-1", "doc-1", "urn:uuid:doc-1", "c".repeat(64), 12,
                "APPROVED"));
        mvc.perform(post("/fhir/xds/documents/discharge-summary")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", GENERATE).header("X-Actor", ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uniqueId").value("urn:uuid:doc-1"));
    }

    @Test
    @DisplayName("停用：需要 manage 权限，返回停用后的元数据")
    void deprecatePermission() throws Exception {
        String body = "{\"reason\":\"患者信息登记错误\",\"rowVersion\":0}";
        mvc.perform(post("/fhir/xds/documents/1.2.3.4/deprecate")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", READ).header("X-Actor", ACTOR))
                .andExpect(status().isForbidden());

        DocumentSummary summary = new DocumentSummary("doc-1", "1.2.3.4", "1.2.3.4", 1,
                "11111111-1111-1111-1111-111111111111", "P2026****01", "18842-5",
                "Discharge summary", "34133-9", "Summarization of episode note",
                "urn:hl7-org:sdwg:ccda-structuredBody:2.1", "CDA R2 structured body",
                List.of(), "出院小结", null, "王医生", "呼吸内科", "N", "zh-CN",
                "text/xml", 100, "d".repeat(64), "DEPRECATED", Instant.now(), null, null,
                Instant.now(), 1);
        when(service.deprecate(eq("1.2.3.4"), any(), any())).thenReturn(summary);

        mvc.perform(post("/fhir/xds/documents/1.2.3.4/deprecate")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Permissions", MANAGE).header("X-Actor", ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availabilityStatus").value("DEPRECATED"));
    }
}
