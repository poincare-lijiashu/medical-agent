package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.LisInboundMessage;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.CriticalLine;
import com.medassist.tech.web.TechDtos.OruResult;
import com.medassist.tech.web.TechDtos.ReportView;
import com.medassist.tech.web.TechDtos.WriteReportReq;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LIS ORU 回写：幂等、状态分支、草稿写入与危急值生成。
 */
class LisInboundServiceTest {

    private static final String CR = "\r";
    private static final String MSG = "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|MC-1|P|2.5" + CR
            + "OBR|1|AP20261101000001|LAB001^血常规|" + CR
            + "OBX|1|NM|LAB-K^血清钾||4.2|mmol/L|3.5-5.3|N|||F" + CR;

    private TechRepository repo;
    private ApplicationService applicationService;
    private ReportService reportService;
    private LisInboundService service;

    private final UUID appId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        applicationService = mock(ApplicationService.class);
        reportService = mock(ReportService.class);
        service = new LisInboundService(repo, applicationService, reportService,
                JsonMapper.builder().build());
        when(repo.insertLisMessage(any())).thenReturn(true);
        when(repo.findLisMessageByControlId(anyString())).thenReturn(Optional.empty());
    }

    private Application app(String status) {
        return new Application(appId, "AP20261101000001", UUID.randomUUID(), "P001",
                null, null, null, "doctor01", "LAB", "LAB-K", "血清钾", null, "ROUTINE",
                null, "血清", status, 0L, Instant.now(), null);
    }

    private Report report(String status) {
        return new Report(reportId, "RP001", appId, 1, true, "所见", "结论",
                "lis", null, null, status, false, Instant.now(), null, null);
    }

    @Test
    @DisplayName("报文不可解析：422 且不落库")
    void rejectsUnparsableMessage() {
        assertThatThrownBy(() -> service.ingest("MSH|^~\\&|LIS"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertLisMessage(any());
    }

    @Test
    @DisplayName("同一控制 ID 重复投递：命中幂等，返回首次结果且不重复处理")
    void duplicateMessageIsIdempotent() {
        LisInboundMessage existing = new LisInboundMessage(UUID.randomUUID(), "MC-1", "ORU^R01",
                "LIS", "AP20261101000001", appId, reportId, LisInboundMessage.PROCESSED,
                null, MSG, "hash", "LIS", Instant.now());
        when(repo.findLisMessageByControlId("MC-1")).thenReturn(Optional.of(existing));

        OruResult result = service.ingest(MSG);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.status()).isEqualTo(LisInboundMessage.PROCESSED);
        assertThat(result.reportId()).isEqualTo(reportId);
        verify(repo, never()).insertLisMessage(any());
        verify(reportService, never()).write(any(), any(), anyString());
    }

    @Test
    @DisplayName("申请不存在：落 FAILED 并返回 200 结果")
    void unknownApplicationRecordedAsFailed() {
        when(repo.findApplicationByNo("AP20261101000001")).thenReturn(Optional.empty());

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.FAILED);
        assertThat(result.errorMessage()).contains("申请号不存在");
        ArgumentCaptor<LisInboundMessage> captor = ArgumentCaptor.forClass(LisInboundMessage.class);
        verify(repo).insertLisMessage(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(LisInboundMessage.FAILED);
        assertThat(captor.getValue().messageHash()).hasSize(64);
    }

    @Test
    @DisplayName("申请已关闭/已报告：IGNORED，不写报告")
    void closedApplicationIgnored() {
        when(repo.findApplicationByNo(anyString())).thenReturn(Optional.of(app(Application.CLOSED)));

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.IGNORED);
        verify(reportService, never()).write(any(), any(), anyString());
    }

    @Test
    @DisplayName("申请 RECEIVED：先走状态机开始执行，再写草稿报告")
    void receivedApplicationStartsThenWritesDraft() {
        when(repo.findApplicationByNo(anyString()))
                .thenReturn(Optional.of(app(Application.RECEIVED)))
                .thenReturn(Optional.of(app(Application.IN_PROGRESS)));
        when(repo.findApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.empty());
        when(reportService.write(any(), any(), anyString()))
                .thenReturn(new ReportView(report(Report.DRAFT), List.of(), List.of()));

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.PROCESSED);
        assertThat(result.reportId()).isEqualTo(reportId);
        verify(applicationService).start(appId, "LIS");
        ArgumentCaptor<WriteReportReq> req = ArgumentCaptor.forClass(WriteReportReq.class);
        verify(reportService).write(eq(appId), req.capture(), eq("LIS"));
        assertThat(req.getValue().findings()).contains("血清钾").contains("4.2");
        assertThat(req.getValue().conclusion()).contains("未见明显异常");
        assertThat(req.getValue().criticalLines()).isEmpty();
    }

    @Test
    @DisplayName("HH 异常标志：自动生成危急值（PENDING 由临床确认）")
    void criticalFlagCreatesCriticalLine() {
        String msg = MSG.replace("|N|||F", "|HH|||F");
        when(repo.findApplicationByNo(anyString())).thenReturn(Optional.of(app(Application.IN_PROGRESS)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.empty());
        when(reportService.write(any(), any(), anyString()))
                .thenReturn(new ReportView(report(Report.DRAFT), List.of(), List.of()));

        service.ingest(msg);

        ArgumentCaptor<WriteReportReq> req = ArgumentCaptor.forClass(WriteReportReq.class);
        verify(reportService).write(eq(appId), req.capture(), eq("LIS"));
        assertThat(req.getValue().criticalLines()).hasSize(1);
        CriticalLine line = req.getValue().criticalLines().get(0);
        assertThat(line.itemName()).isEqualTo("血清钾");
        assertThat(line.resultValue()).isEqualTo("4.2 mmol/L");
        assertThat(line.severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("已有草稿：走重写，不新增报告版本")
    void existingDraftIsRewritten() {
        when(repo.findApplicationByNo(anyString())).thenReturn(Optional.of(app(Application.IN_PROGRESS)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.of(report(Report.DRAFT)));

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.PROCESSED);
        verify(reportService).rewrite(eq(reportId), any(), eq("LIS"));
        verify(reportService, never()).write(any(), any(), anyString());
    }

    @Test
    @DisplayName("报告已签发：IGNORED，不覆盖已审核结果")
    void finalizedReportIgnored() {
        when(repo.findApplicationByNo(anyString())).thenReturn(Optional.of(app(Application.IN_PROGRESS)));
        when(repo.findCurrentReport(appId)).thenReturn(Optional.of(report(Report.FINAL)));

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.IGNORED);
        assertThat(result.errorMessage()).contains("已签发");
        verify(reportService, never()).rewrite(any(), any(), anyString());
    }

    @Test
    @DisplayName("状态机拒绝开始执行：落 FAILED 而不是 500")
    void startFailureRecordedAsFailed() {
        when(repo.findApplicationByNo(anyString()))
                .thenReturn(Optional.of(app(Application.SPECIMEN_COLLECTED)));
        when(applicationService.start(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID, "状态不允许"));

        OruResult result = service.ingest(MSG);

        assertThat(result.status()).isEqualTo(LisInboundMessage.FAILED);
        assertThat(result.errorMessage()).contains("回写失败");
    }
}
