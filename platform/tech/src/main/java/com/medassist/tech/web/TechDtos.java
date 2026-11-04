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

    /**
     * 临床医嘱转申请（仅内网接口使用）。
     *
     * <p>与手工开单的差别：必须带 {@code orderId}（幂等键），{@code appliedBy} 为下医嘱医师。
     */
    public record CreateFromOrderReq(
            @NotNull UUID orderId,
            @NotNull UUID patientId,
            UUID visitId,
            @Size(max = 64) String visitNo,
            UUID deptId,
            @Size(max = 64) String appliedBy,
            @NotBlank @Size(max = 64) String itemCode,
            @Size(max = 64) String bodyPart,
            @Pattern(regexp = "ROUTINE|URGENT|STAT", message = "优先级只能是 ROUTINE/URGENT/STAT")
            String priority,
            @Size(max = 256) String clinicalDiag) {
    }

    /**
     * 医嘱转申请结果。
     *
     * @param duplicate true=该医嘱此前已生成过申请（幂等返回，未新建）
     */
    public record OrderApplicationView(UUID applicationId, String applicationNo, UUID orderId,
                                       boolean duplicate, String category, String itemName,
                                       String status) {
    }

    /** 按医嘱作废申请（医嘱撤销/停嘱联动）。 */
    public record CancelFromOrderReq(
            @NotNull UUID orderId,
            @Size(max = 256) String reason,
            @Size(max = 64) String actor) {
    }

    /**
     * 申请作废结果。
     *
     * @param cancelled true=本次或此前已作废；false=该医嘱没有申请（未转送过）
     */
    public record ApplicationCancelView(UUID applicationId, String applicationNo,
                                        String status, boolean cancelled) {
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

    /**
     * 书写报告（DRAFT）；criticalLines 非空时自动创建危急值（PENDING）。
     *
     * <p>G35：可选结构化内容——templateId 必须是已发布模板，structured 以字段 key 为键；
     * 模板必填字段缺失时拒绝保存。
     */
    public record WriteReportReq(
            @NotBlank @Size(max = 4000) String findings,
            @NotBlank @Size(max = 4000) String conclusion,
            List<@Valid CriticalLine> criticalLines,
            java.util.UUID templateId,
            java.util.Map<String, Object> structured) {
    }

    /** 审核签发。 */
    public record ReviewReportReq(
            @NotNull Boolean approve,
            @Size(max = 256) String reason) {
    }

    /** 修订报告（追加版本）；结构化内容随新版本保存，旧版本不受影响。 */
    public record AmendReportReq(
            @NotBlank @Size(max = 4000) String findings,
            @NotBlank @Size(max = 4000) String conclusion,
            @Size(max = 256) String reason,
            java.util.UUID templateId,
            java.util.Map<String, Object> structured) {
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

    /**
     * LIS ORU 入站处理结果。
     *
     * @param status    PROCESSED / IGNORED / FAILED
     * @param duplicate true 表示命中幂等（同一 messageControlId 已处理过），返回首次结果
     */
    public record OruResult(UUID messageId, String messageControlId, String status,
                            String applicationNo, UUID applicationId, UUID reportId,
                            String errorMessage, boolean duplicate) {
    }

    /** 项目字典视图。 */
    public record ExamItemView(ExamItem item) {
    }
}
