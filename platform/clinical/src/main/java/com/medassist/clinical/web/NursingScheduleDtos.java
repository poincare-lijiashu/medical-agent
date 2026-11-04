package com.medassist.clinical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 护理排班契约（G26）。 */
public final class NursingScheduleDtos {

    private NursingScheduleDtos() {
    }

    public record ShiftView(UUID id, String code, String name, String startTime,
                            String endTime, boolean crossMidnight, boolean enabled) {
    }

    /** 单条排班录入：同一批次内重复条目会被跳过而不是整批失败。 */
    public record AssignmentReq(
            @NotNull UUID deptId,
            @NotBlank @Size(max = 16) String shiftCode,
            @NotNull LocalDate workDate,
            @NotBlank @Size(max = 64) String nurseName,
            @Size(max = 32) String nurseNo,
            @Size(max = 200) String note) {
    }

    public record BatchScheduleReq(
            @NotEmpty @Size(max = 100) List<@Valid AssignmentReq> items) {
    }

    public record ScheduleView(UUID id, UUID deptId, String deptName, String shiftCode,
                               String shiftName, LocalDate workDate, String nurseName,
                               String nurseNo, String status, String note, String createdBy,
                               Instant createdAt, String cancelReason, Instant cancelledAt,
                               long version) {
    }

    public record BatchResult(int created, int skipped) {
    }

    public record PublishResult(int published) {
    }

    public record CancelReq(@NotBlank @Size(max = 200) String reason) {
    }

    public record NurseLoadView(String nurseName, String nurseNo, long shifts,
                                long nightShifts) {
    }

    public record CoverageView(LocalDate workDate, String shiftCode, long count) {
    }

    public record WorkloadView(List<NurseLoadView> nurses, List<CoverageView> coverage) {
    }
}
