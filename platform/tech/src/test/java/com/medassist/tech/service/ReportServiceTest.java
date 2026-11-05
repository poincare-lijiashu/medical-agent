package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.config.TechProperties;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.AmendReportReq;
import com.medassist.tech.web.TechDtos.CriticalLine;
import com.medassist.tech.web.TechDtos.CriticalView;
import com.medassist.tech.web.TechDtos.WriteReportReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告与危急值：草稿/签发/修订版本、危急值必须"确认→关闭"两段式。
 */
class ReportServiceTest {

    private TechRepository repo;
    private ReportService service;

    private final UUID appId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        TechProperties props = new TechProperties(
                new TechProperties.Empi("http://127.0.0.1:8082", "token", Duration.ofSeconds(2)), 30, null);
        service = new ReportService(repo, props, JsonMapper.builder().build());
        when(repo.nextReportNo()).thenReturn("RP20261031000001");
        when(repo.listReports(any())).thenReturn(List.of());
        when(repo.listCriticalValues(any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    private Application app(String status, long version) {
        return new Application(appId, "AP20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", "LAB", "EXM-CBC", "血常规", null, "ROUTINE",
                null, "全血", status, version, Instant.now(), null);
    }

    private Report report(String status, int revision) {
        return new Report(reportId, "RP20261031000001", appId, revision, true,
                "白细胞升高", "考虑感染", "tech01", null, null, status, false, Instant.now(),
                null, null);
    }

    private WriteReportReq writeReq(List<CriticalLine> criticals) {
        return new WriteReportReq("白细胞 18.2×10^9/L", "考虑细菌感染", criticals, null, null);
    }

    private static final UUID templateId = UUID.randomUUID();
    private static final String SCHEMA = """
            {"fields":[
              {"key":"lesion","label":"病灶大小","type":"TEXT","required":true},
              {"key":"side","label":"侧别","type":"SELECT","required":false,"options":["左","右"]}
            ]}
            """;

    private ReportTemplate template(String status) {
        return new ReportTemplate(templateId, "CT-CHEST", "胸部 CT", "CT", SCHEMA, status,
                "tech01", Instant.now(), null, null, 0L);
    }

    @Test
    @DisplayName("书写报告：申请必须处于执行中")
    void writeRequiresInProgress() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.RECEIVED, 2L)));

        assertThatThrownBy(() -> service.write(appId, writeReq(null), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_APPLICATION_STATUS_INVALID);
    }

    @Test
    @DisplayName("书写报告：已有报告时拒绝（改稿走 rewrite，改结论走 amend）")
    void writeRejectsWhenReportExists() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS, 3L)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));

        assertThatThrownBy(() -> service.write(appId, writeReq(null), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("书写报告：带危急值时自动创建 PENDING 危急值")
    void writeCreatesCriticalValues() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS, 3L)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.empty());
        when(repo.nextReportRevision(appId)).thenReturn(1);
        when(repo.findReport(any())).thenAnswer(inv -> Optional.of(report(Report.DRAFT, 1)));
        when(repo.findCriticalValue(any())).thenAnswer(inv -> Optional.of(
                new CriticalValue(inv.getArgument(0), appId, reportId, "血钾", "7.2 mmol/L",
                        "3.5-5.3", "CRITICAL", CriticalValue.PENDING, "护士站", Instant.now(),
                        null, null, null, Instant.now())));

        var view = service.write(appId, writeReq(List.of(
                new CriticalLine("血钾", "7.2 mmol/L", "3.5-5.3", "CRITICAL", "护士站"))), "tech01");

        assertThat(view.report().status()).isEqualTo(Report.DRAFT);
        assertThat(view.criticals()).hasSize(1);
        verify(repo).insertCriticalValue(any(CriticalValue.class));
    }

    @Test
    @DisplayName("审核通过：报告 FINAL，申请 REPORTED")
    void reviewApprovesAndReports() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 2)));
        when(repo.finalizeReport(eq(reportId), eq("chief01"), eq(2L))).thenReturn(true);
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS, 5L)));
        when(repo.updateApplicationStatus(appId, Application.IN_PROGRESS,
                Application.REPORTED, 5L)).thenReturn(true);

        service.review(reportId, true, null, "chief01");

        verify(repo).finalizeReport(reportId, "chief01", 2L);
        verify(repo).updateApplicationStatus(appId, Application.IN_PROGRESS,
                Application.REPORTED, 5L);
    }

    @Test
    @DisplayName("审核驳回：报告保持草稿，只落审计（不销毁技师的工作）")
    void reviewRejectKeepsDraft() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 2)));

        service.review(reportId, false, "结论与所见不符", "chief01");

        verify(repo, never()).finalizeReport(any(), anyString(), anyLong());
        verify(repo).logChange(eq("chief01"), eq("REPORT_REJECT"), eq("REPORT"),
                eq(reportId.toString()), anyString());
    }

    @Test
    @DisplayName("修订：旧版本置为非当前，新版本 revision+1 且落 DRAFT 等他人审核")
    void amendCreatesDraftRevision() {
        when(repo.findReport(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (reportId.equals(id)) {
                return Optional.of(report(Report.FINAL, 1));
            }
            return Optional.of(new Report(id, "RP20261031000001", appId, 2, true,
                    "复查所见", "结论修正", "tech01", null, null,
                    Report.DRAFT, false, Instant.now(), null, null));
        });
        when(repo.nextReportRevision(appId)).thenReturn(2);
        when(repo.listCriticalValuesByApplication(appId)).thenReturn(List.of());

        service.amend(reportId, new AmendReportReq("复查所见", "结论修正", "补充复查",
                null, null), "tech01");

        ArgumentCaptor<Report> inserted = ArgumentCaptor.forClass(Report.class);
        verify(repo).insertReport(inserted.capture());
        // G67：修订版不再自审自签——落草稿、无审核人，必须由他人 review
        assertThat(inserted.getValue().status()).isEqualTo(Report.DRAFT);
        assertThat(inserted.getValue().revision()).isEqualTo(2);
        assertThat(inserted.getValue().reviewer()).isNull();
        verify(repo).clearCurrentReport(appId);
        verify(repo).logChange(eq("tech01"), eq("REPORT_AMEND"), eq("REPORT"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("修订版签发：同一书写人不得自审自签")
    void reviewRejectsSelfReviewOfAmendment() {
        UUID amendedId = UUID.randomUUID();
        Report amended = new Report(amendedId, "RP20261031000001", appId, 2, true,
                "复查所见", "结论修正", "tech01", null, null, Report.DRAFT, false,
                Instant.now(), null, null);
        when(repo.findReport(amendedId)).thenReturn(Optional.of(amended));

        assertThatThrownBy(() -> service.review(amendedId, true, null, "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repo, never()).finalizeReport(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("修订版签发：他人审核放行，且不重复推进申请状态")
    void reviewAmendedByOtherSignsWithoutTouchingApplication() {
        UUID amendedId = UUID.randomUUID();
        Report amended = new Report(amendedId, "RP20261031000001", appId, 2, true,
                "复查所见", "结论修正", "tech01", null, null, Report.DRAFT, false,
                Instant.now(), null, null);
        when(repo.findReport(amendedId)).thenReturn(Optional.of(amended));
        when(repo.finalizeReport(amendedId, "tech02", 2)).thenReturn(true);
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.REPORTED, 5L)));
        when(repo.listCriticalValuesByApplication(appId)).thenReturn(List.of());

        service.review(amendedId, true, null, "tech02");

        verify(repo).finalizeReport(amendedId, "tech02", 2);
        verify(repo, never()).updateApplicationStatus(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("危急值：未确认不得直接关闭")
    void closeCriticalRequiresAcknowledge() {
        UUID criticalId = UUID.randomUUID();
        when(repo.findCriticalValue(criticalId)).thenReturn(Optional.of(
                new CriticalValue(criticalId, appId, reportId, "血钾", "7.2", "3.5-5.3",
                        "CRITICAL", CriticalValue.PENDING, null, null, null, null, null, Instant.now())));

        assertThatThrownBy(() -> service.closeCritical(criticalId, null, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_CRITICAL_STATUS_INVALID);
        verify(repo, never()).closeCritical(any(), any());
    }

    @Test
    @DisplayName("危急值：确认后状态 ACKNOWLEDGED，并落审计")
    void acknowledgeCritical() {
        UUID criticalId = UUID.randomUUID();
        CriticalValue pending = new CriticalValue(criticalId, appId, reportId, "血钾", "7.2", "3.5-5.3",
                "CRITICAL", CriticalValue.PENDING, null, null, null, null, null, Instant.now());
        CriticalValue acknowledged = new CriticalValue(criticalId, appId, reportId, "血钾", "7.2", "3.5-5.3",
                "CRITICAL", CriticalValue.ACKNOWLEDGED, null, null, "doctor01",
                Instant.now(), "已通知医师", Instant.now());
        // 第一次读取用于校验状态，第二次读取用于返回最新视图
        when(repo.findCriticalValue(criticalId))
                .thenReturn(Optional.of(pending), Optional.of(acknowledged));
        when(repo.acknowledgeCritical(criticalId, "doctor01", "已通知医师")).thenReturn(true);

        var view = service.acknowledge(criticalId, "已通知医师", "doctor01");

        assertThat(view.critical().status()).isEqualTo(CriticalValue.ACKNOWLEDGED);
        verify(repo).acknowledgeCritical(criticalId, "doctor01", "已通知医师");
        verify(repo).logChange(eq("doctor01"), eq("CRITICAL_ACK"), eq("CRITICAL"),
                eq(criticalId.toString()), anyString());
    }

    @Test
    @DisplayName("危急值超时：PENDING 超过时限标记 overdue")
    void criticalOverdueFlag() {
        UUID criticalId = UUID.randomUUID();
        CriticalValue old = new CriticalValue(criticalId, appId, reportId, "血钾", "7.2",
                "3.5-5.3", "CRITICAL", CriticalValue.PENDING, null, null, null, null, null,
                Instant.now().minus(Duration.ofMinutes(90)));
        when(repo.listCriticalValues(any(), any(), any(), anyInt())).thenReturn(List.of(old));

        List<CriticalView> list = service.criticals(null, null, 10);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).overdue()).isTrue();
    }

    @Test
    @DisplayName("报告明细的危急值按申请维度查询（修订后仍能看到，不再随 revision 断链）")
    void detailQueriesCriticalsByApplication() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));
        CriticalValue critical = new CriticalValue(UUID.randomUUID(), appId, reportId, "血钾",
                "7.2", "3.5-5.3", "CRITICAL", CriticalValue.PENDING, null, null, null, null,
                null, Instant.now());
        when(repo.listCriticalValuesByApplication(appId)).thenReturn(List.of(critical));

        var view = service.detail(reportId);

        assertThat(view.criticals()).hasSize(1);
        assertThat(view.criticals().get(0).itemName()).isEqualTo("血钾");
        verify(repo).listCriticalValuesByApplication(appId);
        verify(repo, never()).listCriticalValues(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("重写草稿：已闭环的危急值不得复活成 PENDING（P0-D1 回归）")
    void rewriteDoesNotResurrectClosedCriticals() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));
        when(repo.updateDraftReport(eq(reportId), anyString(), anyString(), eq(true),
                any(), any())).thenReturn(true);
        // 本报告已有危急值（已 CLOSED）：重写不得再插入 PENDING，否则申请永远无法关闭
        CriticalValue closed = new CriticalValue(UUID.randomUUID(), appId, reportId, "血钾",
                "7.2", "3.5-5.3", "CRITICAL", CriticalValue.CLOSED, null, Instant.now(),
                "nurse01", Instant.now(), "已处理", Instant.now());
        when(repo.listCriticalValuesByApplication(appId)).thenReturn(List.of(closed));

        var view = service.rewrite(reportId, writeReq(List.of(
                new CriticalLine("血钾", "7.2 mmol/L", "3.5-5.3", "CRITICAL", "护士站"))), "tech01");

        verify(repo, never()).insertCriticalValue(any());
        assertThat(view.criticals()).hasSize(1);
        assertThat(view.criticals().get(0).status()).isEqualTo(CriticalValue.CLOSED);
    }

    @Test
    @DisplayName("重写草稿：本报告无危急值时才新增")
    void rewriteInsertsOnlyWhenNoneExist() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));
        when(repo.updateDraftReport(eq(reportId), anyString(), anyString(), eq(true),
                any(), any())).thenReturn(true);
        when(repo.listCriticalValuesByApplication(appId)).thenReturn(List.of());
        when(repo.findCriticalValue(any())).thenAnswer(inv -> Optional.of(
                new CriticalValue(inv.getArgument(0), appId, reportId, "血钾", "7.2 mmol/L",
                        "3.5-5.3", "CRITICAL", CriticalValue.PENDING, "护士站", Instant.now(),
                        null, null, null, Instant.now())));

        service.rewrite(reportId, writeReq(List.of(
                new CriticalLine("血钾", "7.2 mmol/L", "3.5-5.3", "CRITICAL", "护士站"))), "tech01");

        verify(repo).insertCriticalValue(any());
        verify(repo).listCriticalValuesByApplication(appId);
    }

    // ---------------------------------------------------------------- 结构化报告（G35）

    private void stubWritableApplication() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS, 3L)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.empty());
        when(repo.nextReportRevision(appId)).thenReturn(1);
        when(repo.findReport(any())).thenAnswer(inv -> Optional.of(report(Report.DRAFT, 1)));
    }

    @Test
    @DisplayName("结构化：选择未发布模板 → 422")
    void writeRejectsUnpublishedTemplate() {
        stubWritableApplication();
        when(repo.findReportTemplate(templateId)).thenReturn(Optional.of(template(ReportTemplate.DRAFT)));

        assertThatThrownBy(() -> service.write(appId, new WriteReportReq("所见", "结论", null,
                templateId, Map.of("lesion", "2cm")), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_REPORT_STRUCTURED_INVALID);
        verify(repo, never()).insertReport(any());
    }

    @Test
    @DisplayName("结构化：必填字段缺失 → 422")
    void writeRejectsMissingRequiredField() {
        stubWritableApplication();
        when(repo.findReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.PUBLISHED)));

        assertThatThrownBy(() -> service.write(appId, new WriteReportReq("所见", "结论", null,
                templateId, Map.of("side", "左")), "tech01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("病灶大小");
        verify(repo, never()).insertReport(any());
    }

    @Test
    @DisplayName("结构化：出现模板外字段 → 422")
    void writeRejectsUnknownStructuredField() {
        stubWritableApplication();
        when(repo.findReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.PUBLISHED)));

        assertThatThrownBy(() -> service.write(appId, new WriteReportReq("所见", "结论", null,
                templateId, Map.of("lesion", "2cm", "extra", "x")), "tech01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("extra");
    }

    @Test
    @DisplayName("结构化：未选模板却传内容 → 422")
    void writeRejectsStructuredWithoutTemplate() {
        stubWritableApplication();

        assertThatThrownBy(() -> service.write(appId, new WriteReportReq("所见", "结论", null,
                null, Map.of("lesion", "2cm")), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_REPORT_STRUCTURED_INVALID);
    }

    @Test
    @DisplayName("结构化：校验通过后随报告快照落库")
    void writePersistsStructuredJson() {
        stubWritableApplication();
        when(repo.findReportTemplate(templateId))
                .thenReturn(Optional.of(template(ReportTemplate.PUBLISHED)));

        service.write(appId, new WriteReportReq("所见", "结论", null, templateId,
                Map.of("lesion", "2.3cm", "side", "左")), "tech01");

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(repo).insertReport(captor.capture());
        assertThat(captor.getValue().templateId()).isEqualTo(templateId);
        assertThat(captor.getValue().structuredJson()).contains("2.3cm").contains("lesion");
    }
}
