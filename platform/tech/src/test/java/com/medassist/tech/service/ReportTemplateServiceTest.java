package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceDtos.CreateReportTemplateReq;
import com.medassist.tech.web.DeviceDtos.FieldSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构化报告模板：编码唯一、字段定义、发布后不可改、停用。
 */
class ReportTemplateServiceTest {

    private TechRepository repo;
    private ReportTemplateService service;

    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        service = new ReportTemplateService(repo, JsonMapper.builder().build());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private CreateReportTemplateReq req(List<FieldSpec> fields) {
        return new CreateReportTemplateReq("ct-chest", "胸部 CT", "CT", fields);
    }

    private FieldSpec text(String key, boolean required) {
        return new FieldSpec(key, key + " 标签", "TEXT", required, null, null);
    }

    private ReportTemplate template(String status, long version) {
        return new ReportTemplate(templateId, "CT-CHEST", "胸部 CT", "CT",
                "{\"fields\":[{\"key\":\"lesion\",\"label\":\"病灶\",\"type\":\"TEXT\",\"required\":true}]}",
                status, "tech01", Instant.now(), null, null, version);
    }

    @Test
    @DisplayName("创建：编码规范化后重复 → 409")
    void createRejectsDuplicateCode() {
        when(repo.findReportTemplateByCode("CT-CHEST"))
                .thenReturn(Optional.of(template(ReportTemplate.DRAFT, 0L)));

        assertCode(() -> service.create(req(List.of(text("lesion", true))), "tech01"),
                ErrorCode.TECH_REPORT_TEMPLATE_DUPLICATE);
        verify(repo, never()).insertReportTemplate(any());
    }

    @Test
    @DisplayName("创建：字段 key 重复 → 422")
    void createRejectsDuplicateFieldKey() {
        when(repo.findReportTemplateByCode(anyString())).thenReturn(Optional.empty());

        assertCode(() -> service.create(req(List.of(text("lesion", true), text("lesion", false))),
                "tech01"), ErrorCode.TECH_REPORT_TEMPLATE_INVALID);
        verify(repo, never()).insertReportTemplate(any());
    }

    @Test
    @DisplayName("创建：SELECT 字段无选项 → 422")
    void createRejectsSelectWithoutOptions() {
        when(repo.findReportTemplateByCode(anyString())).thenReturn(Optional.empty());

        assertCode(() -> service.create(req(List.of(
                new FieldSpec("side", "侧别", "SELECT", true, List.of(), null))), "tech01"),
                ErrorCode.TECH_REPORT_TEMPLATE_INVALID);
    }

    @Test
    @DisplayName("创建：规范编码为 DRAFT 并保存 schema")
    void createSucceeds() {
        when(repo.findReportTemplateByCode(anyString())).thenReturn(Optional.empty());
        when(repo.findReportTemplate(any())).thenAnswer(inv ->
                Optional.of(template(ReportTemplate.DRAFT, 0L)));

        service.create(req(List.of(text("lesion", true))), "tech01");

        ArgumentCaptor<ReportTemplate> captor = ArgumentCaptor.forClass(ReportTemplate.class);
        verify(repo).insertReportTemplate(captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("CT-CHEST");
        assertThat(captor.getValue().status()).isEqualTo(ReportTemplate.DRAFT);
        assertThat(captor.getValue().schemaJson()).contains("\"lesion\"");
        verify(repo).logChange(eq("tech01"), eq("TEMPLATE_CREATE"), eq("REPORT_TEMPLATE"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("发布：非草稿 → 409")
    void publishRejectsNonDraft() {
        when(repo.lockReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.PUBLISHED, 1L)));

        assertCode(() -> service.publish(templateId, "tech01"),
                ErrorCode.TECH_REPORT_TEMPLATE_STATUS_INVALID);
    }

    @Test
    @DisplayName("发布：CAS 未命中 → 409")
    void publishCasConflict() {
        when(repo.lockReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.DRAFT, 0L)));
        when(repo.publishReportTemplate(templateId, "tech01", 0L)).thenReturn(false);

        assertCode(() -> service.publish(templateId, "tech01"), ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("发布：DRAFT → PUBLISHED 并写审计")
    void publishSucceeds() {
        when(repo.lockReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.DRAFT, 0L)));
        when(repo.publishReportTemplate(templateId, "tech01", 0L)).thenReturn(true);
        when(repo.findReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.PUBLISHED, 1L)));

        var view = service.publish(templateId, "tech01");

        assertThat(view.template().status()).isEqualTo(ReportTemplate.PUBLISHED);
        verify(repo).logChange(eq("tech01"), eq("TEMPLATE_PUBLISH"), eq("REPORT_TEMPLATE"),
                eq(templateId.toString()), anyString());
    }

    @Test
    @DisplayName("停用：只有已发布可停用")
    void disableRequiresPublished() {
        when(repo.lockReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.DRAFT, 0L)));

        assertCode(() -> service.disable(templateId, "tech01"),
                ErrorCode.TECH_REPORT_TEMPLATE_STATUS_INVALID);
        verify(repo, never()).disableReportTemplate(any(), anyLong());
    }

    @Test
    @DisplayName("列表：非法状态 422，limit 上限 200；已发布查询只返回 PUBLISHED")
    void listNormalizesParams() {
        assertCode(() -> service.list("BAD", null, 10), ErrorCode.VALIDATION_FAILED);

        service.list(" published ", "ct", 9999);
        verify(repo).listReportTemplates("PUBLISHED", "CT", 200);

        service.published("CT");
        verify(repo, org.mockito.Mockito.times(2))
                .listReportTemplates(ReportTemplate.PUBLISHED, "CT", 200);
    }
}
