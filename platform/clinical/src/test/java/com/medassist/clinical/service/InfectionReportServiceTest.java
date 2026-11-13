package com.medassist.clinical.service;

import com.medassist.clinical.config.InfectionMetrics;
import com.medassist.clinical.domain.ClinicalModel.InfectionDetection;
import com.medassist.clinical.domain.ClinicalModel.InfectionDiagnosisCandidate;
import com.medassist.clinical.domain.ClinicalModel.InfectionDisease;
import com.medassist.clinical.domain.ClinicalModel.InfectionReport;
import com.medassist.clinical.domain.ClinicalModel.InfectionSummary;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 传染病上报闭环（§294）。
 *
 * <p>锁住：诊断匹配幂等、甲类 2 小时/乙丙 24 小时截止、提交必须写报告人、
 * 已提交不可作废、手工补录不重复。
 */
class InfectionReportServiceTest {

    private ClinicalRepository repo;
    private InfectionReportService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ClinicalRepository.class);
        service = new InfectionReportService(repo, new ObjectMapper(),
                Mockito.mock(InfectionMetrics.class));
        when(repo.listInfectionDiseases(true)).thenReturn(List.of(tb()));
        when(repo.infectionScanWatermark()).thenReturn(Optional.of(Instant.now().minusSeconds(3600)));
        when(repo.nextInfectionReportNo()).thenReturn("I20261113000001");
    }

    @Test
    @DisplayName("自动扫描：命中病种生成一张卡，未命中/已存在跳过")
    void detectCreatesOnlyMatchingNewCards() {
        UUID visitA = UUID.randomUUID();
        UUID visitC = UUID.randomUUID();
        when(repo.listInfectionCandidates(any(), anyInt())).thenReturn(List.of(
                candidate(visitA, "A15.900", "继发性肺结核"),
                candidate(UUID.randomUUID(), "J06.900", "急性上呼吸道感染"),
                candidate(visitC, "A16.200", "肺结核")));
        when(repo.findActiveInfectionReport(visitA, "15")).thenReturn(Optional.empty());
        when(repo.findActiveInfectionReport(visitC, "15")).thenReturn(Optional.of(report(
                InfectionReport.SUBMITTED, 1L)));
        when(repo.insertInfectionReport(any())).thenReturn(true);

        InfectionDetection result = service.detect("system");

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        ArgumentCaptor<InfectionReport> captor = ArgumentCaptor.forClass(InfectionReport.class);
        verify(repo).insertInfectionReport(captor.capture());
        assertThat(captor.getValue().diseaseCode()).isEqualTo("15");
        assertThat(captor.getValue().status()).isEqualTo(InfectionReport.DRAFT);
        verify(repo).insertInfectionEvent(captor.getValue().id(), "CREATE", null,
                InfectionReport.DRAFT, "system", "来源：AUTO");
        verify(repo).updateInfectionScanState(any(), anyInt());
    }

    @Test
    @DisplayName("提交必须写报告人，且只有草稿/退回可提交")
    void submitRequiresReporterAndDraftStatus() {
        InfectionReport draft = report(InfectionReport.DRAFT, 3L);
        when(repo.findInfectionReport(draft.id())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submit(draft.id(), 3, " ", null, "doc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.submit(draft.id(), 3, "张医生", "呼吸科", "doc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("已提交的报告卡不能作废（法定报告是既成事实）")
    void submittedCannotBeVoided() {
        InfectionReport submitted = report(InfectionReport.SUBMITTED, 2L);
        when(repo.findInfectionReport(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.voidCard(submitted.id(), 2, "录错了", "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).voidInfectionReport(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("手工补录：同就诊同病种已有有效卡时拒绝")
    void manualCreateRejectsDuplicate() {
        UUID visitId = UUID.randomUUID();
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit(visitId)));
        when(repo.findInfectionDisease("15")).thenReturn(Optional.of(tb()));
        when(repo.findActiveInfectionReport(visitId, "15"))
                .thenReturn(Optional.of(report(InfectionReport.SUBMITTED, 1L)));

        assertThatThrownBy(() -> service.createManual(visitId, "15", LocalDate.now(),
                "A15.900", "继发性肺结核", "呼吸科", "doc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("汇总映射：待报/逾期/及时率直接来自 SQL 聚合")
    void summaryMapsAggregates() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("draft", 4L);
        raw.put("overdue", 2L);
        raw.put("submittedToday", 3L);
        raw.put("accepted", 10L);
        raw.put("returned", 1L);
        raw.put("totalActive", 15L);
        raw.put("timelyRate", new BigDecimal("96.50"));
        when(repo.infectionSummaryRaw(anyString())).thenReturn(raw);

        InfectionSummary summary = service.summary();

        assertThat(summary.draft()).isEqualTo(4);
        assertThat(summary.overdue()).isEqualTo(2);
        assertThat(summary.timelyRate()).isEqualByComparingTo("96.50");
    }

    // ---------------------------------------------------------------- fixtures

    private static InfectionDisease tb() {
        return new InfectionDisease(UUID.randomUUID(), "15", "肺结核", "B",
                List.of("A15", "A16"), 24, true, 150, null, 0);
    }

    private static InfectionDiagnosisCandidate candidate(UUID visitId, String code, String name) {
        return new InfectionDiagnosisCandidate(UUID.randomUUID(), visitId, UUID.randomUUID(),
                "P0001", code, name, Instant.now());
    }

    private static InfectionReport report(String status, long version) {
        Instant now = Instant.now();
        return new InfectionReport(UUID.randomUUID(), "I20261113000001", UUID.randomUUID(),
                UUID.randomUUID(), "P0001", "15", "肺结核", "B", "A15.900", "继发性肺结核",
                LocalDate.now(), now, now.plusSeconds(86400), status, InfectionReport.SOURCE_AUTO,
                "张医生", "呼吸科", InfectionReport.DRAFT.equals(status) ? null : "张医生",
                InfectionReport.DRAFT.equals(status) ? null : now, null, null, null, null,
                "doc01", "doc01", now, now, version);
    }

    private static com.medassist.clinical.domain.ClinicalModel.Visit visit(UUID id) {
        return new com.medassist.clinical.domain.ClinicalModel.Visit(id, "V001",
                UUID.randomUUID(), "P0001", UUID.randomUUID(), "doc01", null, "OUTPATIENT",
                "IN_TREATMENT", LocalDate.now(), 0L);
    }
}
