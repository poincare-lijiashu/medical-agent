package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.HealthExamItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackage;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackageItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamRegistration;
import com.medassist.clinical.domain.ClinicalModel.HealthExamReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 体检中心契约（G37）。
 *
 * <p>请求只接受业务意图：套餐项目由收费项目编码引用，价格/名称由服务端从主数据取快照；
 * 体检状态、项目状态与报告状态全部由服务端状态机推进。
 */
public final class HealthExamDtos {

    private HealthExamDtos() {
    }

    public record PackageItemReq(@NotBlank @Size(max = 64) String itemCode, UUID deptId) {
    }

    public record CreatePackageReq(
            @NotBlank @Size(max = 32) String packageCode,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 500) String description,
            @NotEmpty @Size(max = 50) List<@Valid PackageItemReq> items) {
    }

    /** 套餐编码不可改；仅草稿可更新名称/说明/项目。 */
    public record UpdatePackageReq(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 500) String description,
            @NotEmpty @Size(max = 50) List<@Valid PackageItemReq> items) {
    }

    public record PackageView(HealthExamPackage pkg, List<HealthExamPackageItem> items) {
    }

    public record CreateRegistrationReq(
            @NotNull UUID packageId,
            @NotNull UUID patientId,
            @Size(max = 500) String note) {
    }

    public record RegistrationSummaryView(UUID id, String examNo, String packageName,
                                          UUID patientId, String patientNo, String patientName,
                                          String gender, LocalDate birthDate, String status,
                                          int itemTotal, int itemCompleted, int abnormalCount,
                                          String reportStatus, Instant createdAt) {
    }

    public record RegistrationView(HealthExamRegistration registration,
                                   List<HealthExamItem> items, HealthExamReport report) {
    }

    /** 项目结果：COMPLETED 必须有结论；SKIPPED 必须写未检原因。 */
    public record ItemResultReq(
            @NotBlank @Size(max = 16) String status,
            @Size(max = 500) String resultSummary,
            @Size(max = 500) String resultNote,
            Boolean abnormal) {
    }

    public record ReportDraftReq(
            @Size(max = 2000) String conclusion,
            @Size(max = 2000) String suggestion) {
    }

    /** 原因是否为空由服务层校验，保证返回体检域专用错误码。 */
    public record CancelReq(@Size(max = 500) String reason) {
    }

    public record BoardView(long registered, long inProgress, long examDone, long reported,
                            long cancelled, long abnormalItems, long totalToday) {
    }
}
