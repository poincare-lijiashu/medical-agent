package com.medassist.clinical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 护理敏感质量指标契约（§266）。 */
public final class NursingIndicatorDtos {

    private NursingIndicatorDtos() {
    }

    public record IndicatorDefView(UUID id, String code, String name, String category,
                                   String calcType, String denominatorType, String deviceType,
                                   String unit, String direction, BigDecimal targetValue,
                                   int sortNo) {
    }

    /**
     * 不良事件上报。
     *
     * <p>{@code shiftCode} 可不填：服务端按发生时间自动匹配班次（跨午夜归属班次开始日）；
     * 填了则必须与发生时间落在同一班次窗内，否则拒绝，避免人为挪班。
     */
    public record EventReq(
            @NotBlank @Size(max = 64) String indicatorCode,
            @NotNull UUID deptId,
            UUID wardId,
            UUID admissionId,
            @Size(max = 32) String patientNo,
            @NotNull Instant occurredAt,
            Instant discoveredAt,
            @Size(max = 16) String shiftCode,
            @NotBlank @Size(max = 16) String severity,
            Map<String, Object> detail,
            @Size(max = 500) String note) {
    }

    public record EventView(UUID id, String eventNo, String indicatorCode, String indicatorName,
                            UUID deptId, String deptName, UUID wardId, String wardName,
                            UUID admissionId, String patientNo, Instant occurredAt,
                            Instant discoveredAt, Instant reportedAt, LocalDate shiftDate,
                            String shiftCode, String shiftName, String shiftSource,
                            String severity, String detail, String note, String reportedBy,
                            boolean timely) {
    }

    /** 设备日（分母）录入：按日期 + 类型追加，修正也是追加。 */
    public record DenominatorItemReq(
            @NotNull UUID deptId,
            @NotNull LocalDate statDate,
            @NotBlank @Size(max = 32) String deviceType,
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 2) BigDecimal count,
            @Size(max = 200) String note) {
    }

    public record DenominatorBatchReq(
            @NotEmpty @Size(max = 100) List<@Valid DenominatorItemReq> items) {
    }

    public record DenominatorBatchResult(int created) {
    }

    public record DenominatorView(UUID id, UUID deptId, LocalDate statDate, String deviceType,
                                  BigDecimal count, String note, String recordedBy,
                                  Instant recordedAt) {
    }

    /** 单指标计算结果：value 为 null 表示分母缺失，不可当作 0。 */
    public record IndicatorMetricView(String code, String name, String calcType,
                                      BigDecimal numerator, BigDecimal denominator,
                                      BigDecimal value, String unit, String direction,
                                      BigDecimal targetValue, Boolean meetsTarget,
                                      boolean dataComplete) {
    }

    /** 班次维度汇总（周期内）：事件密度 = 可计数事件 / 排班人次。 */
    public record ShiftStatView(String shiftCode, String shiftName, boolean crossMidnight,
                                long nurseShifts, long events, long countableEvents,
                                BigDecimal eventsPerNurseShift) {
    }

    public record IndicatorBoardView(UUID deptId, LocalDate from, LocalDate to, int days,
                                     BigDecimal patientDays, BigDecimal avgCensus, int bedCount,
                                     BigDecimal nurseDays, BigDecimal avgNurses,
                                     BigDecimal bedNurseRatio, BigDecimal nursePatientRatio,
                                     long eventCount, long timelyCount, BigDecimal timelyRate,
                                     List<IndicatorMetricView> metrics,
                                     List<ShiftStatView> shifts, List<String> warnings) {
    }

    public record ShiftNurseView(String nurseName, String nurseNo, String status) {
    }

    public record ShiftDetailView(LocalDate shiftDate, String shiftCode, String shiftName,
                                  String startTime, String endTime, boolean crossMidnight,
                                  List<ShiftNurseView> nurses, long events, long countableEvents,
                                  BigDecimal eventsPerNurse) {
    }

    /** 某业务日的班次明细：跨午夜夜班的事件归到班次开始日，此处按业务日汇总。 */
    public record ShiftDayView(UUID deptId, LocalDate date, int totalNurses, long totalEvents,
                               List<ShiftDetailView> shifts, List<EventView> events) {
    }

    public record ReportGenerateReq(
            @NotNull UUID deptId,
            @NotBlank @Size(max = 16) String periodType,
            @NotBlank @Size(max = 16) String periodKey) {
    }

    public record ReportView(UUID id, String reportNo, UUID deptId, String deptName,
                             String periodType, String periodKey, LocalDate periodStart,
                             LocalDate periodEnd, String status, BigDecimal patientDays,
                             long eventCount, long timelyCount, BigDecimal nurseDays,
                             List<IndicatorMetricView> metrics, List<String> warnings,
                             String generatedBy, Instant generatedAt, String submittedBy,
                             Instant submittedAt, String reviewedBy, Instant reviewedAt,
                             String reviewNote, long version) {
    }

    public record ReportSummaryView(UUID id, String reportNo, UUID deptId, String deptName,
                                    String periodType, String periodKey, LocalDate periodStart,
                                    LocalDate periodEnd, String status, long eventCount,
                                    String generatedBy, Instant generatedAt,
                                    String submittedBy, Instant submittedAt,
                                    String reviewedBy, Instant reviewedAt, long version) {
    }

    /** 审核：ACCEPT 通过 / RETURN 退回（退回必须写原因）。 */
    public record ReviewReq(@NotBlank @Size(max = 16) String decision,
                            @Size(max = 500) String note) {
    }
}
