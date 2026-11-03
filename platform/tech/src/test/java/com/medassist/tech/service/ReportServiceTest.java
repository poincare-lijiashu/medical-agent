package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.config.TechProperties;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.AmendReportReq;
import com.medassist.tech.web.TechDtos.CriticalLine;
import com.medassist.tech.web.TechDtos.CriticalView;
import com.medassist.tech.web.TechDtos.WriteReportReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                new TechProperties.Empi("http://127.0.0.1:8082", "token", Duration.ofSeconds(2)), 30);
        service = new ReportService(repo, props, JsonMapper.builder().build());
        when(repo.nextReportNo()).thenReturn("RP20261031000001");
        when(repo.listReports(any())).thenReturn(List.of());
        when(repo.listCriticalValues(any(), any(), anyInt())).thenReturn(List.of());
    }

    private Application app(String status, long version) {
        return new Application(appId, "AP20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", "LAB", "EXM-CBC", "血常规", null, "ROUTINE",
                null, "全血", status, version, Instant.now(), null);
    }

    private Report report(String status, int revision) {
        return new Report(reportId, "RP20261031000001", appId, revision, true,
                "白细胞升高", "考虑感染", "tech01", null, null, status, false, Instant.now());
    }

    private WriteReportReq writeReq(List<CriticalLine> criticals) {
        return new WriteReportReq("白细胞 18.2×10^9/L", "考虑细菌感染", criticals);
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
    @DisplayName("修订：旧版本置为非当前，新版本 revision+1 且状态 AMENDED")
    void amendCreatesNewRevision() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.FINAL, 1)));
        when(repo.nextReportRevision(appId)).thenReturn(2);
        when(repo.findReport(any())).thenAnswer(inv -> Optional.of(
                new Report(inv.getArgument(0), "RP20261031000001", appId, 2, true,
                        "复查所见", "结论修正", "tech01", "tech01", Instant.now(),
                        Report.AMENDED, false, Instant.now())));

        service.amend(reportId, new AmendReportReq("复查所见", "结论修正", "补充复查"), "tech01");

        verify(repo).clearCurrentReport(appId);
        verify(repo).insertReport(any(Report.class));
        verify(repo).logChange(eq("tech01"), eq("REPORT_AMEND"), eq("REPORT"),
                anyString(), anyString());
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
        when(repo.listCriticalValues(any(), any(), anyInt())).thenReturn(List.of(old));

        List<CriticalView> list = service.criticals(null, null, 10);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).overdue()).isTrue();
    }

    @Test
    @DisplayName("报告明细的危急值按 reportId 定向查询（不再全库取 200 条后过滤）")
    void detailQueriesCriticalsByReport() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));
        CriticalValue critical = new CriticalValue(UUID.randomUUID(), appId, reportId, "血钾",
                "7.2", "3.5-5.3", "CRITICAL", CriticalValue.PENDING, null, null, null, null,
                null, Instant.now());
        when(repo.listCriticalValuesByReport(reportId)).thenReturn(List.of(critical));

        var view = service.detail(reportId);

        assertThat(view.criticals()).hasSize(1);
        assertThat(view.criticals().get(0).itemName()).isEqualTo("血钾");
        verify(repo).listCriticalValuesByReport(reportId);
        verify(repo, never()).listCriticalValues(any(), any(), anyInt());
    }

    @Test
    @DisplayName("重写草稿：已闭环的危急值不得复活成 PENDING（P0-D1 回归）")
    void rewriteDoesNotResurrectClosedCriticals() {
        when(repo.findReport(reportId)).thenReturn(Optional.of(report(Report.DRAFT, 1)));
        when(repo.updateDraftReport(eq(reportId), anyString(), anyString(), eq(true))).thenReturn(true);
        // 本报告已有危急值（已 CLOSED）：重写不得再插入 PENDING，否则申请永远无法关闭
        CriticalValue closed = new CriticalValue(UUID.randomUUID(), appId, reportId, "血钾",
                "7.2", "3.5-5.3", "CRITICAL", CriticalValue.CLOSED, null, Instant.now(),
                "nurse01", Instant.now(), "已处理", Instant.now());
        when(repo.listCriticalValuesByReport(reportId)).thenReturn(List.of(closed));

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
        when(repo.updateDraftReport(eq(reportId), anyString(), anyString(), eq(true))).thenReturn(true);
        when(repo.listCriticalValuesByReport(reportId)).thenReturn(List.of());
        when(repo.findCriticalValue(any())).thenAnswer(inv -> Optional.of(
                new CriticalValue(inv.getArgument(0), appId, reportId, "血钾", "7.2 mmol/L",
                        "3.5-5.3", "CRITICAL", CriticalValue.PENDING, "护士站", Instant.now(),
                        null, null, null, Instant.now())));

        service.rewrite(reportId, writeReq(List.of(
                new CriticalLine("血钾", "7.2 mmol/L", "3.5-5.3", "CRITICAL", "护士站"))), "tech01");

        verify(repo).insertCriticalValue(any());
        verify(repo).listCriticalValuesByReport(reportId);
    }
}
