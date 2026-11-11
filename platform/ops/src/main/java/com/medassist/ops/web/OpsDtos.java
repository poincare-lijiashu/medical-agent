package com.medassist.ops.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 运营 API 请求体。
 *
 * <p>报告人（reporter）不来自请求体，而来自网关注入的 {@code X-Actor}：
 * 否则任何人都能冒名替别人建单，工单的"谁报的"就失去意义。
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    public record ServiceReq(
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "PLATFORM|INFRA|SECURITY|INTEGRATION|OTHER") String domain,
            @Size(max = 64) String owner,
            @NotBlank @Size(max = 64) String supportGroup,
            @Size(max = 500) String description,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @Min(1) @Max(9999) Integer sortNo) {
    }

    public record CalendarReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String timezone,
            @NotEmpty List<@Min(1) @Max(7) Integer> workdays,
            LocalTime startTime,
            LocalTime endTime,
            List<LocalDate> holidays) {
    }

    public record SlaPolicyReq(
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @Min(1) @Max(525600) int responseMinutes,
            @Min(1) @Max(525600) int resolveMinutes,
            @Min(0) @Max(525600) Integer escalationMinutes,
            boolean businessHours,
            @Size(max = 64) String calendarCode,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record TicketCreateReq(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @Size(max = 64) String reporterDept,
            @Pattern(regexp = "PHONE|EMAIL|PORTAL|MONITOR|ONCALL|OTHER") String source,
            List<@Size(max = 32) String> tags) {
    }

    public record AssignReq(
            @NotBlank @Size(max = 64) String assignee,
            @Size(max = 64) String assigneeGroup) {
    }

    public record NoteReq(
            @NotBlank @Size(min = 2, max = 2000) String note) {
    }

    public record PendingReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record ResolveReq(
            @NotBlank @Size(min = 2, max = 2000) String resolution) {
    }

    public record CloseReq(
            @Min(1) @Max(5) Integer satisfaction,
            @Size(max = 500) String note) {
    }

    public record ReopenReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record CancelReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record PriorityReq(
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }
}
