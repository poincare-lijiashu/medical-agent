package com.medassist.clinical.service;

import com.medassist.clinical.config.NursingIndicatorProperties;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.NursingAdmissionStay;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorDef;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorEvent;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorEventCount;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorReport;
import com.medassist.clinical.domain.ClinicalModel.NursingNurseDayCount;
import com.medassist.clinical.domain.ClinicalModel.NursingShift;
import com.medassist.clinical.domain.ClinicalModel.NursingShiftEventCount;
import com.medassist.clinical.domain.ClinicalModel.NursingShiftNurseCount;
import com.medassist.clinical.domain.ClinicalModel.NursingTimelinessCount;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorBatchReq;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorItemReq;
import com.medassist.clinical.web.NursingIndicatorDtos.EventReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportGenerateReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ReviewReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * 护理敏感指标：跨日班次归属、分母诚实、上报状态机与职责分离。
 */
class NursingIndicatorServiceTest {

    private ClinicalRepository repo;
    private NursingIndicatorService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZoneId zone = BusinessTime.zone();
    private final UUID deptId = UUID.randomUUID();
    private final LocalDate today = BusinessTime.today();
    /**
     * 事件时间必须早于"当前时间"（服务层拒绝未来事件）。
     * 用固定钟点写"今天"会在凌晨跑测试时变成未来时间——这是测试自身的时间炸弹，
     * 用两天前保证任何时刻执行都落在过去，同时不影响班次归属断言。
     */
    private final LocalDate eventDate = today.minusDays(2);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new NursingIndicatorService(repo, objectMapper,
                new NursingIndicatorProperties(24, 365, 92));
    }

    // ---------------------------------------------------------------- fixtures

    private Dept dept() {
        return new Dept(deptId, "D01", "内科病区", "WARD", true);
    }

    private static NursingShift shift(String code, String name, String start, String end,
                                      boolean crossMidnight) {
        return new NursingShift(UUID.randomUUID(), code, name, LocalTime.parse(start),
                LocalTime.parse(end), crossMidnight, true, 0);
    }

    private static NursingShift nightCross() {
        return shift("NIGHT", "夜班", "22:00", "06:00", true);
    }

    private static NursingShift day() {
        return shift("DAY", "白班", "08:00", "16:00", false);
    }

    private static NursingIndicatorDef def(String code, String name, String calcType,
                                           String denomType, String deviceType, String unit,
                                           String direction) {
        return new NursingIndicatorDef(UUID.randomUUID(), code, name, "SAFETY", calcType,
                denomType, deviceType, unit, direction, null, 10, true, 0);
    }

    private static NursingIndicatorDef fallDef() {
        return def("FALL_RATE", "跌倒/坠床发生率", "EVENT_RATE", "PATIENT_DAYS", null, "‰",
                "LOWER_BETTER");
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(zone).toInstant();
    }

    private EventReq eventReq(Instant occurredAt, String shiftCode, String severity) {
        return new EventReq("FALL_RATE", deptId, null, null, "P001", occurredAt, null,
                shiftCode, severity, Map.of("place", "卫生间"), null);
    }

    private NursingIndicatorReport report(String status, String submittedBy, long version) {
        return new NursingIndicatorReport(UUID.randomUUID(), "NR20261111000001", deptId,
                "内科病区", "MONTH", "2026-11", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), status, BigDecimal.TEN, 2, 1, BigDecimal.valueOf(30),
                "[]", "[]", "nurse01", Instant.now(), submittedBy,
                submittedBy == null ? null : Instant.now(), null, null, null, version);
    }

    private void stubDeptAndDefs() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        when(repo.findNursingIndicatorDef("FALL_RATE")).thenReturn(Optional.of(fallDef()));
    }

    // ---------------------------------------------------------------- 班次归属

    @Test
    @DisplayName("跨午夜夜班：02:30 的事件归属前一天业务日")
    void crossMidnightEventBelongsToShiftStartDate() {
        stubDeptAndDefs();
        when(repo.listNursingShifts()).thenReturn(List.of(nightCross(), day()));
        when(repo.nextNursingIndicatorEventNo()).thenReturn("NE20261111000001");

        service.reportEvent(eventReq(at(eventDate, 2, 30), null, "NO_HARM"), "nurse01");

        ArgumentCaptor<NursingIndicatorEvent> captor =
                ArgumentCaptor.forClass(NursingIndicatorEvent.class);
        verify(repo).insertNursingIndicatorEvent(captor.capture());
        NursingIndicatorEvent saved = captor.getValue();
        assertThat(saved.shiftCode()).isEqualTo("NIGHT");
        assertThat(saved.shiftDate()).isEqualTo(eventDate.minusDays(1));
        assertThat(saved.shiftSource()).isEqualTo(NursingIndicatorEvent.SHIFT_AUTO);
        assertThat(saved.detail()).contains("卫生间");
    }

    @Test
    @DisplayName("自动匹配白班：09:00 归属当天 DAY")
    void autoResolvesDayShift() {
        stubDeptAndDefs();
        when(repo.listNursingShifts()).thenReturn(List.of(nightCross(), day()));
        when(repo.nextNursingIndicatorEventNo()).thenReturn("NE20261111000002");

        service.reportEvent(eventReq(at(eventDate, 9, 0), null, "MILD"), "nurse01");

        ArgumentCaptor<NursingIndicatorEvent> captor =
                ArgumentCaptor.forClass(NursingIndicatorEvent.class);
        verify(repo).insertNursingIndicatorEvent(captor.capture());
        assertThat(captor.getValue().shiftCode()).isEqualTo("DAY");
        assertThat(captor.getValue().shiftDate()).isEqualTo(eventDate);
    }

    @Test
    @DisplayName("发生时间不在任何班次窗内 → 422，不能随手挂班")
    void rejectsTimeOutsideShifts() {
        stubDeptAndDefs();
        when(repo.listNursingShifts()).thenReturn(List.of(nightCross(), day()));

        assertThatThrownBy(() -> service.reportEvent(
                eventReq(at(eventDate, 7, 0), null, "NO_HARM"), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID);
        verify(repo, never()).insertNursingIndicatorEvent(any());
    }

    @Test
    @DisplayName("指定班次与发生时间不匹配 → 422，防止人为挪班")
    void rejectsSpecifiedShiftMismatch() {
        stubDeptAndDefs();
        when(repo.listNursingShifts()).thenReturn(List.of(nightCross(), day()));

        assertThatThrownBy(() -> service.reportEvent(
                eventReq(at(eventDate, 9, 0), "NIGHT", "NO_HARM"), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID);
    }

    @Test
    @DisplayName("结构类指标不能录入事件")
    void rejectsEventForStructureIndicator() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        when(repo.findNursingIndicatorDef("BED_NURSE_RATIO")).thenReturn(Optional.of(
                def("BED_NURSE_RATIO", "床护比", "STRUCTURE", "NURSE_COUNT", null, "床/护",
                        "NONE")));

        EventReq req = new EventReq("BED_NURSE_RATIO", deptId, null, null, null,
                at(today, 9, 0), null, null, "NO_HARM", null, null);
        assertThatThrownBy(() -> service.reportEvent(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_EVENT_INVALID);
    }

    @Test
    @DisplayName("住院登记不属于所选科室 → 422")
    void rejectsAdmissionFromOtherDept() {
        stubDeptAndDefs();
        UUID admissionId = UUID.randomUUID();
        UUID otherWardId = UUID.randomUUID();
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A001", UUID.randomUUID(), UUID.randomUUID(), "P001", otherWardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now().minusSeconds(3600),
                null, 0L)));
        when(repo.findWard(otherWardId)).thenReturn(Optional.of(new Ward(otherWardId, "W9",
                "其他病区", UUID.randomUUID(), true)));

        EventReq req = new EventReq("FALL_RATE", deptId, null, admissionId, null,
                at(today, 9, 0), null, null, "NO_HARM", null, null);
        assertThatThrownBy(() -> service.reportEvent(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_EVENT_INVALID);
    }

    // ---------------------------------------------------------------- 指标计算

    @Test
    @DisplayName("看板：发生率为 null 而非 0；床护比/护患比/及时率按口径计算")
    void boardComputesRatesAndKeepsMissingDenominatorNull() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        when(repo.listNursingIndicatorDefs()).thenReturn(List.of(
                fallDef(),
                def("UNPLANNED_EXTUBATION_RATE", "非计划拔管发生率", "EVENT_RATE",
                        "DEVICE_DAYS", "VENTILATOR", "‰", "LOWER_BETTER"),
                def("REPORT_TIMELINESS", "护理不良事件上报及时率", "TIMELINESS",
                        "EVENT_COUNT", null, "%", "HIGHER_BETTER"),
                def("BED_NURSE_RATIO", "床护比", "STRUCTURE", "NURSE_COUNT", null, "床/护",
                        "NONE"),
                def("NURSE_PATIENT_RATIO", "护患比", "STRUCTURE", "NURSE_COUNT", null, "患/护",
                        "LOWER_BETTER")));
        when(repo.countNursingIndicatorEvents(eq(deptId), any(), any())).thenReturn(List.of(
                new NursingIndicatorEventCount("FALL_RATE", 3, 2),
                new NursingIndicatorEventCount("UNPLANNED_EXTUBATION_RATE", 1, 1)));
        when(repo.countNursingTimeliness(eq(deptId), any(), any(), eq(24)))
                .thenReturn(new NursingTimelinessCount(4, 3));
        when(repo.listNursingAdmissionStays(eq(deptId), any(), any())).thenReturn(List.of(
                new NursingAdmissionStay(at(today, 10, 0), null)));
        when(repo.listNursingNurseDays(deptId, today, today)).thenReturn(List.of(
                new NursingNurseDayCount(today, 4)));
        when(repo.countDeptBeds(deptId)).thenReturn(10);
        when(repo.sumNursingDeviceDays(eq(deptId), eq(today), eq(today), eq("VENTILATOR")))
                .thenReturn(BigDecimal.ZERO);
        when(repo.listNursingShifts()).thenReturn(List.of(nightCross(), day()));
        when(repo.countNursingShiftNurses(deptId, today, today)).thenReturn(List.of(
                new NursingShiftNurseCount("DAY", 4)));
        when(repo.countNursingShiftEvents(deptId, today, today)).thenReturn(List.of(
                new NursingShiftEventCount("DAY", 2, 1)));

        var board = service.board(deptId, today, today);

        assertThat(board.days()).isEqualTo(1);
        assertThat(board.patientDays()).isEqualByComparingTo("1.00");
        assertThat(board.avgNurses()).isEqualByComparingTo("4.00");
        assertThat(board.bedNurseRatio()).isEqualByComparingTo("2.50");
        assertThat(board.nursePatientRatio()).isEqualByComparingTo("0.25");
        assertThat(board.timelyRate()).isEqualByComparingTo("75.0000");

        var fall = board.metrics().stream()
                .filter(m -> m.code().equals("FALL_RATE")).findFirst().orElseThrow();
        assertThat(fall.value()).isEqualByComparingTo("2000.0000");
        assertThat(fall.dataComplete()).isTrue();

        var extubation = board.metrics().stream()
                .filter(m -> m.code().equals("UNPLANNED_EXTUBATION_RATE"))
                .findFirst().orElseThrow();
        assertThat(extubation.value()).isNull();
        assertThat(extubation.dataComplete()).isFalse();
        assertThat(board.warnings()).anyMatch(w -> w.contains("缺少设备日数据"));
        assertThat(board.shifts()).hasSize(2);
        assertThat(board.shifts().stream().filter(s -> s.shiftCode().equals("DAY"))
                .findFirst().orElseThrow().eventsPerNurseShift())
                .isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("看板：未来日期与超长区间拒绝")
    void boardRejectsFutureAndTooLongRange() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        assertThatThrownBy(() -> service.board(deptId, today, today.plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.board(deptId, today.minusDays(92), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 上报

    @Test
    @DisplayName("生成上报：DRAFT + 指标快照；已提交周期拒绝覆盖")
    void generateReportCreatesDraftAndRejectsSubmittedPeriod() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        when(repo.listNursingIndicatorDefs()).thenReturn(List.of(fallDef()));
        when(repo.countNursingIndicatorEvents(eq(deptId), any(), any())).thenReturn(List.of());
        when(repo.countNursingTimeliness(eq(deptId), any(), any(), anyInt()))
                .thenReturn(new NursingTimelinessCount(0, 0));
        when(repo.listNursingAdmissionStays(eq(deptId), any(), any())).thenReturn(List.of());
        when(repo.listNursingNurseDays(eq(deptId), any(), any())).thenReturn(List.of());
        when(repo.countDeptBeds(deptId)).thenReturn(0);
        when(repo.listNursingShifts()).thenReturn(List.of());
        when(repo.nextNursingIndicatorReportNo()).thenReturn("NR20261111000001");

        var view = service.generateReport(new ReportGenerateReq(deptId, "MONTH", "2026-11"),
                "nurse01");

        assertThat(view.status()).isEqualTo(NursingIndicatorReport.DRAFT);
        assertThat(view.metrics()).hasSize(1);
        assertThat(view.warnings()).isNotEmpty();
        verify(repo).insertNursingIndicatorReport(any());

        NursingIndicatorReport submitted = report(NursingIndicatorReport.SUBMITTED, "nurse01", 2);
        when(repo.findNursingIndicatorReportByPeriod(deptId, "MONTH", "2026-11"))
                .thenReturn(Optional.of(submitted));
        assertThatThrownBy(() -> service.generateReport(
                new ReportGenerateReq(deptId, "MONTH", "2026-11"), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_REPORT_STATUS_INVALID);
    }

    @Test
    @DisplayName("提交：仅 DRAFT 可提交；版本冲突 409")
    void submitOnlyFromDraft() {
        NursingIndicatorReport draft = report(NursingIndicatorReport.DRAFT, null, 1);
        UUID id = draft.id();
        when(repo.findNursingIndicatorReport(id)).thenReturn(Optional.of(draft));
        when(repo.submitNursingIndicatorReport(id, "nurse01", 1)).thenReturn(false);
        assertThatThrownBy(() -> service.submit(id, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        NursingIndicatorReport accepted = report(NursingIndicatorReport.ACCEPTED, "nurse01", 3);
        when(repo.findNursingIndicatorReport(id)).thenReturn(Optional.of(accepted));
        assertThatThrownBy(() -> service.submit(id, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_REPORT_STATUS_INVALID);
    }

    @Test
    @DisplayName("审核：提交人不能自审；退回必须写原因")
    void reviewRequiresDutySeparationAndReturnReason() {
        NursingIndicatorReport submitted = report(NursingIndicatorReport.SUBMITTED, "nurse01", 2);
        when(repo.findNursingIndicatorReport(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.review(submitted.id(),
                new ReviewReq("ACCEPT", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_SELF_REVIEW);

        assertThatThrownBy(() -> service.review(submitted.id(),
                new ReviewReq("RETURN", " "), "nurse02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_REPORT_RETURN_REASON_REQUIRED);
        verify(repo, never()).reviewNursingIndicatorReport(any(), anyString(), anyString(),
                any(), anyLong());
    }

    // ---------------------------------------------------------------- 设备日

    @Test
    @DisplayName("设备日：类型不合法 422；合法录入按批次计数")
    void denominatorValidation() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept()));
        DenominatorItemReq bad = new DenominatorItemReq(deptId, today, "ECMO",
                BigDecimal.ONE, null);
        assertThatThrownBy(() -> service.recordDenominators(
                new DenominatorBatchReq(List.of(bad)), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NURSING_INDICATOR_DENOMINATOR_INVALID);

        DenominatorItemReq ok = new DenominatorItemReq(deptId, today, "ventilator",
                BigDecimal.valueOf(3), "白班");
        var result = service.recordDenominators(new DenominatorBatchReq(List.of(ok)), "nurse01");
        assertThat(result.created()).isEqualTo(1);
        verify(repo).insertNursingIndicatorDenominator(any(), eq(deptId), eq(today),
                eq("VENTILATOR"), eq(new BigDecimal("3.00")), eq("白班"), eq("nurse01"));
    }
}
