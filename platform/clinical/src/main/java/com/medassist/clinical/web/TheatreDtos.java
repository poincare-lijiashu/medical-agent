package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.AnesthesiaRecord;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaVital;
import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 手术间/排台/麻醉/术中护理请求体。 */
public final class TheatreDtos {

    private TheatreDtos() {
    }

    /** 新建手术间。{@code code} 用于看板与对接，限制字符集。 */
    public record CreateRoomReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}", message = "编码只能是字母/数字/下划线/中划线")
            String code,
            @NotBlank @Size(max = 64) String name,
            UUID deptId,
            @Size(max = 120) String location) {
    }

    /** 手术间状态迁移（需版本号 CAS）。 */
    public record RoomStatusReq(
            @NotBlank @Pattern(regexp = "ACTIVE|MAINTENANCE|INACTIVE", message = "状态不合法")
            String status,
            @NotNull Long version) {
    }

    /** 排台：同一手术间同一时段只能有一台手术。 */
    public record ScheduleReq(
            @NotNull UUID roomId,
            @NotNull Instant plannedStart,
            @NotNull Instant plannedEnd) {
    }

    /** 取消排台：必须写原因。 */
    public record CancelScheduleReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }

    /**
     * 麻醉记录（首次保存不带 version；再次保存必须带 version 做 CAS）。
     */
    public record AnesthesiaReq(
            @Pattern(regexp = "GENERAL|REGIONAL|LOCAL|SEDATION|NONE", message = "麻醉方式不合法")
            String method,
            @Pattern(regexp = "I|II|III|IV|V|VI", message = "ASA 分级不合法") String asaGrade,
            @Size(max = 64) String anesthesiologist,
            @Pattern(regexp = "ETT|LMA|MASK|NONE", message = "气道方式不合法") String airway,
            Instant startedAt,
            Instant endedAt,
            @Size(max = 2000) String notes,
            Long version) {
    }

    /** 术中生命体征（同一时间点只能一条）。 */
    public record VitalReq(
            @NotNull Instant recordedAt,
            @Min(0) @Max(300) Integer heartRate,
            @Min(0) @Max(300) Integer sbp,
            @Min(0) @Max(200) Integer dbp,
            @Min(0) @Max(100) Integer spo2,
            BigDecimal etco2,
            BigDecimal temperature,
            @Size(max = 256) String note) {
    }

    /** 术中护理记录。 */
    public record NursingReq(
            @Size(max = 64) String scrubNurse,
            @Size(max = 64) String circulatingNurse,
            @Pattern(regexp = "SUPINE|PRONE|LATERAL|SITTING|LITHOTOMY|OTHER", message = "体位不合法")
            String patientPosition,
            @Size(max = 256) String skinPrep,
            Instant tourniquetStart,
            Instant tourniquetEnd,
            @Min(0) @Max(999) Integer specimenCount,
            @Size(max = 2000) String notes,
            Long version) {
    }

    /** 麻醉记录视图（记录 + 生命体征时间线）。 */
    public record AnesthesiaView(AnesthesiaRecord record, List<AnesthesiaVital> vitals) {
    }

    /**
     * 单日利用率。
     *
     * @param occupiedMinutes 开放时段内的排台占用分钟（已取消排台不计）
     * @param availableMinutes 当日开放时段总分钟
     * @param turnoverMinutes 接台间隙合计（相邻两台之间的空闲分钟）
     * @param utilizationRate 占用 ÷ 可用，0~1，保留 4 位
     */
    public record DailyUtilization(LocalDate date, int scheduledCount, int completedCount,
                                   int cancelledCount, long occupiedMinutes,
                                   long availableMinutes, long turnoverMinutes,
                                   int turnoverCount, BigDecimal utilizationRate) {
    }

    /** 手术间利用率报表（逐日 + 合计）。 */
    public record RoomUtilization(UUID roomId, String roomCode, String roomName, LocalDate from,
                                  LocalDate to, String openFrom, String openTo,
                                  List<DailyUtilization> days, int totalScheduled,
                                  int totalCompleted, int totalCancelled,
                                  long totalOccupiedMinutes, long totalAvailableMinutes,
                                  long totalTurnoverMinutes, int turnoverCount,
                                  BigDecimal utilizationRate) {
    }

    /**
     * 排台看板响应：某天全部房间的排台 + 房间清单。
     *
     * <p>一次请求替代前端的 2N 次并发（N 个房间各查排台 + 各查利用率），
     * 既减少 504 放大，也避免把网关的限流桶吃满。
     */
    public record SurgeryBoardView(LocalDate date, int roomCount, int truncated,
                                   List<SurgeryBoardRow> rows) {
    }
}
