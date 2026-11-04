package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 移动护理与条码闭环契约（G39）。
 *
 * <p>接口只接受扫描到的条码值，不返回任务期望条码，避免客户端"照抄期望值"绕过核对。
 */
public final class MobileNursingDtos {

    private MobileNursingDtos() {
    }

    /** 从住院长期药品医嘱生成当日给药核对任务。 */
    public record GenerateFromOrdersReq(UUID admissionId, String admissionNo, LocalDate taskDate) {
    }

    public record ManualTaskReq(
            UUID admissionId,
            String admissionNo,
            @NotBlank @Size(max = 16) String taskType,
            @NotBlank @Size(max = 64) String itemCode,
            @NotBlank @Size(max = 128) String itemName,
            @NotBlank @Size(max = 128) String expectedItemBarcode,
            @Size(max = 128) String sourceRef,
            @Size(max = 500) String note) {
    }

    public record VerifyReq(
            @NotBlank @Size(max = 128) String patientBarcode,
            @NotBlank @Size(max = 128) String itemBarcode) {
    }

    public record ExceptionReq(@Size(max = 500) String reason) {
    }

    /** 任务视图：不返回 expectedPatientBarcode / expectedItemBarcode。 */
    public record TaskView(UUID id, String taskNo, LocalDate taskDate, String taskType,
                           UUID patientId, String patientNo, String patientName, String wardName,
                           String bedNo, String itemCode, String itemName, String sourceType,
                           String sourceRef, String status, int attempts, Instant lastScannedAt,
                           String verifiedBy, Instant verifiedAt, String exceptionReason,
                           String exceptionBy, Instant exceptionAt, String note,
                           Instant createdAt, long version) {
    }

    public record VerificationResult(boolean matched, boolean patientMatched,
                                     boolean itemMatched, String message, TaskView task) {
    }

    public record ScanView(UUID id, UUID taskId, String scanType, String scannedValue,
                           boolean matched, String scannedBy, Instant scannedAt, String note) {
    }

    public record GenerateResult(int created, int skipped, List<TaskView> tasks) {
    }

    public record BoardView(long pending, long verifiedToday, long exceptionsToday,
                            long totalToday) {
    }
}
