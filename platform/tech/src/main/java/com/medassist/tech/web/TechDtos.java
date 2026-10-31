package com.medassist.tech.web;

import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.ExamItem;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.domain.TechModel.Specimen;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 医技请求/响应契约。
 *
 * <p>请求体只接受业务意图（患者、项目、所见、结论），不接受状态与版本——状态由服务端状态机推进。
 */
public final class TechDtos {

    private TechDtos() {
    }

    /** 开立检查/检验申请。检验项目必须能确定标本类型（由字典带出）。 */
    public record CreateApplicationReq(
            @NotNull UUID patientId,
            UUID visitId,
            @Size(max = 64) String visitNo,
            UUID deptId,
            @NotBlank @Size(max = 64) String itemCode,
            @Size(max = 64) String bodyPart,
            @Pattern(regexp = "ROUTINE|URGENT|STAT", message = "优先级只能是 ROUTINE/URGENT/STAT")
            String priority,
            @Size(max = 256) String clinicalDiag) {
    }

    /** 采集标本（检验）。 */
    public record CollectSpecimenReq(@Size(max = 32) String specimenType) {
    }

    /** 接收/拒收标本。 */
    public record ReceiveSpecimenReq(
            @NotNull Boolean accept,
            @Size(max = 256) String reason) {
    }

    /** 报告中的危急值。 */
    public record CriticalLine(
            @NotBlank @Size(max = 64) String itemName,
            @NotBlank @Size(max = 64) String resultValue,
            @Size(max = 64) String referenceRange,
            @Pattern(regexp = "LOW|HIGH|CRITICAL", message = "严重程度只能是 LOW/HIGH/CRITICAL")
            String severity,
            @Size(max = 64) String notifiedTo) {
    }

    /** 书写报告（DRAFT）；criticalLines 非空时自动创建危急值（PENDING）。 */
    public record WriteReportReq(
            @NotBlank @Size(max = 4000) String findings,
            @NotBlank @Size(max = 4000) String conclusion,
            List<@Valid CriticalLine> criticalLines) {
    }

    /** 审核签发。 */
    public record ReviewReportReq(
            @NotNull Boolean approve,
            @Size(max = 256) String reason) {
    }

    /** 修订报告（追加版本）。 */
    public record AmendReportReq(
            @NotBlank @Size(max = 4000) String findings,
            @NotBlank @Size(max = 4000) String conclusion,
            @Size(max = 256) String reason) {
    }

    /** 危急值确认。 */
    public record AckCriticalReq(@Size(max = 256) String note) {
    }

    /** 关闭危急值。 */
    public record CloseCriticalReq(@Size(max = 256) String note) {
    }

    /** 申请视图（含标本）。 */
    public record ApplicationView(Application application, Specimen specimen) {
    }

    /** 报告视图（含该报告下全部危急值）。 */
    public record ReportView(Report report, List<CriticalValue> criticals, List<Report> history) {
    }

    /** 危急值视图（overdue = 超过确认时限仍未确认）。 */
    public record CriticalView(CriticalValue critical, boolean overdue) {
    }

    /** 项目字典视图。 */
    public record ExamItemView(ExamItem item) {
    }
}
